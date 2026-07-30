@file:OptIn(ExperimentalTime::class)

package xyz.lark.app.core.gateway

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import xyz.lark.app.core.model.HealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Data mapping through the poll loop (plan R5/R7/R8/R9): balance/history/receive-code
 * projections onto the seam's models, plus the pure mapper formatting decisions.
 */
class GatewayLarkCoreDataTest {

    @Test
    fun ae2BalanceAndHistoryMapToSeamModels() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.BALANCE, BarkdScript.Json(BarkdFixtures.BALANCE.replace("412350", "250000")))
        val received = movementJson(
            id = 2,
            intendedSat = 250_000,
            receivedOnValue = "maya@zaprite.com",
            destinationType = "lightning-address",
            createdAt = "2026-07-28T11:00:00Z",
        )
        val sent = movementJson(
            id = 1,
            intendedSat = -14_200,
            effectiveSat = -14_250,
            sentToValue = "ark1qf7demo",
            createdAt = "2026-07-28T10:00:00Z",
        )
        // Oldest first on the wire, to prove the mapping sorts newest first.
        script.sticky(Paths.HISTORY, BarkdScript.Json("[$sent, $received]"))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        assertEquals(250_000L, core.balanceSats.value)
        assertEquals(2, core.activity.size)
        val first = core.activity[0]
        assertEquals("maya@zaprite.com", first.who)
        assertEquals(250_000L, first.sats)
        assertEquals("1 hour ago", first.whenLabel)
        assertEquals("M", first.initial)
        assertFalse(first.isSent)
        val second = core.activity[1]
        assertEquals("ark1qf7demo", second.who)
        assertEquals(-14_250L, second.sats, "successful movements show the effective (fee-inclusive) delta")
        assertEquals("2 hours ago", second.whenLabel)
        assertTrue(second.isSent)
    }

    @Test
    fun historyOnlyChangeIsVisibleAfterTheNextPoll() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(1, core.activity.size)

        val extra = movementJson(
            id = 9,
            intendedSat = 1_000,
            receivedOnValue = "ark1qnew",
            createdAt = "2026-07-28T11:30:00Z",
        )
        script.sticky(Paths.HISTORY, BarkdScript.Json("[${BarkdFixtures.MOVEMENT}, $extra]"))
        advanceThrough(15.seconds)
        // Documented M1 staleness (R7): no StateFlow moved, but the seam property reflects the poll.
        assertEquals(2, core.activity.size)
        assertEquals(412_350L, core.balanceSats.value)
    }

    @Test
    fun receiveCodeAndDepositAddressFetchOncePerWalletSession() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        assertEquals("", core.receiveCode, "empty until the first cycle fetched it")
        runCurrent()
        assertEquals("bitcoin:bc1qf7demo?ark=ark1qf7demo", core.receiveCode)
        assertEquals("bc1qf7demo", core.depositAddress)
        assertEquals(2, script.countOf(Paths.BIP321))
        assertFalse(script.bodyOf(Paths.BIP321, 0).contains("onchain"), "receive code is the no-amount request")
        assertTrue(script.bodyOf(Paths.BIP321, 1).contains("\"onchain\":true"))

        advanceThrough(15.seconds)
        assertEquals(2, script.countOf(Paths.BIP321), "bip321 targets are cached for the session")
    }

    @Test
    fun recentsDeriveFromHistorySentToEntriesDedupedNewestFirst() = runTest {
        val script = BarkdScript()
        val movements = listOf(
            movementJson(id = 5, intendedSat = -100, sentToValue = "ark1recent-a", createdAt = "2026-07-28T11:00:00Z"),
            movementJson(id = 4, intendedSat = -100, sentToValue = "ark1recent-b", createdAt = "2026-07-28T10:00:00Z"),
            movementJson(id = 3, intendedSat = -100, sentToValue = "ark1recent-a", createdAt = "2026-07-28T09:00:00Z"),
            movementJson(id = 2, intendedSat = -100, sentToValue = "ark1recent-c", createdAt = "2026-07-28T08:00:00Z"),
            movementJson(id = 1, intendedSat = -100, sentToValue = "ark1recent-d", createdAt = "2026-07-28T07:00:00Z"),
        )
        script.sticky(Paths.HISTORY, BarkdScript.Json(movements.joinToString(",", "[", "]")))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        assertEquals(listOf("ark1recent-a", "ark1recent-b", "ark1recent-c"), core.recents.map { it.handle })
        assertEquals("ark1recent-a", core.recents[0].who, "short destinations stay unabbreviated")
        assertEquals("A", core.recents[0].initial)
    }

    @Test
    fun recentsAreEmptyWhenHistoryHasNoSends() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.HISTORY, BarkdScript.Json("[]"))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertTrue(core.recents.isEmpty())
        assertTrue(core.activity.isEmpty())
    }

    @Test
    fun advancedStatsMapGatewayDataWithEmDashPlaceholders() = runTest {
        val core = gatewayCore(barkdEngine())
        runCurrent()
        val stats = core.advancedStats()

        assertEquals(1, stats.funds.vtxoCount)
        assertEquals(103_087L, stats.funds.vtxoTotalSats)
        assertEquals("block 929,174 · in 90 days", stats.funds.soonestExpiry)
        assertEquals("—", stats.funds.lastRefresh, "not exposed by barkd 0.4.0")
        assertEquals(0L, stats.funds.onChainReserveSats, "not exposed; zero, never a fake number")
        assertEquals("bc1qf7demo", stats.funds.depositAddress)
        assertEquals("Connected", stats.network.arkServerStatus)
        assertEquals("—", stats.network.nextRound)
        assertEquals("—", stats.network.lightningBridge)
        assertEquals(916_214L, stats.network.chainTip)
    }

    @Test
    fun advancedStatsReflectOfflineHealth() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.BALANCE, BarkdScript.Broken())
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals("Unreachable", core.advancedStats().network.arkServerStatus)
    }

    @Test
    fun fiatRateAndNetworkLabelAreLocalConstantsAndBackedUpIsALocalFlag() = runTest {
        val core = gatewayCore(barkdEngine())
        runCurrent()
        // R8: barkd has no fiat endpoint; the demo rate stands in until a rate source exists.
        assertEquals(10L, core.fiatRate.satsPerCent)
        assertEquals("mutinynet", core.networkLabel)
        // barkd has no backed-up concept: purely local acknowledgement, like the fake.
        assertFalse(core.backedUp.value)
        core.markBackedUp()
        assertTrue(core.backedUp.value)
    }

    // --- Pure mapper formatting decisions ---

    @Test
    fun relativeTimeLabelsSpeakTheDemoLanguage() {
        assertEquals("Just now", relativeTimeLabel("2026-07-28T11:59:30Z", FIXED_NOW))
        assertEquals("45 minutes ago", relativeTimeLabel("2026-07-28T11:15:00Z", FIXED_NOW))
        assertEquals("1 hour ago", relativeTimeLabel("2026-07-28T11:00:00Z", FIXED_NOW))
        assertEquals("23 hours ago", relativeTimeLabel("2026-07-27T13:00:00Z", FIXED_NOW))
        assertEquals("Yesterday", relativeTimeLabel("2026-07-27T05:00:00Z", FIXED_NOW))
        assertEquals("3 days ago", relativeTimeLabel("2026-07-25T12:00:00Z", FIXED_NOW))
        assertEquals("Last week", relativeTimeLabel("2026-07-18T12:00:00Z", FIXED_NOW))
        assertEquals("4 weeks ago", relativeTimeLabel("2026-06-28T12:00:00Z", FIXED_NOW))
        assertEquals("—", relativeTimeLabel("not-a-timestamp", FIXED_NOW))
    }

    @Test
    fun soonestExpiryLabelCountsDownInBlocksAndDays() {
        val lenient = Json { ignoreUnknownKeys = true }
        fun vtxosAt(expiry: Long): List<WalletVtxoInfo> =
            lenient.decodeFromString(BarkdFixtures.VTXOS.replace("918402", expiry.toString()))

        assertEquals("block 916,358 · in 1 day", soonestExpiryLabel(vtxosAt(916_214 + 144), 916_214))
        assertEquals("block 916,226 · in 2 hours", soonestExpiryLabel(vtxosAt(916_214 + 12), 916_214))
        assertEquals("block 916,214 · expired", soonestExpiryLabel(vtxosAt(916_214), 916_214))
        assertEquals("—", soonestExpiryLabel(emptyList(), 916_214))
        assertEquals("—", soonestExpiryLabel(vtxosAt(916_500), 0), "no tip yet -> placeholder")
    }
}
