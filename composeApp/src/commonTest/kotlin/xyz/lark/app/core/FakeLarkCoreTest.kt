package xyz.lark.app.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fake-specific behavior: demo constants, [DemoControls.forceHealth], and the injected
 * prototype delays. Seam-level behavior shared with the gateway (send guards, wallet
 * lifecycle, backup flow) is pinned by [LarkCoreContractTest] via [FakeLarkCoreContractTest].
 */
class FakeLarkCoreTest {

    private fun core() = FakeLarkCore()

    // --- Send (offline + concurrency + timing; the guard set lives in the contract suite) ---

    @Test
    fun sendFailsWhenOffline() = runTest {
        val core = core()
        core.forceHealth(HealthState.OFFLINE)
        val result = core.send("jack@lark.money", 520)
        assertEquals(SendResult.Failure, result)
    }

    @Test
    fun sendLeavesBalanceUnchangedOnFailure() = runTest {
        val core = core()
        core.forceHealth(HealthState.OFFLINE)
        core.send("jack@lark.money", 520)
        assertEquals(412_350L, core.balanceSats.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun twoConcurrentSendsDebitExactlyTwice() = runTest {
        val core = core()
        val first = async { core.send("jack@lark.money", 520) }
        val second = async { core.send("maya@zaprite.com", 480) }
        advanceUntilIdle()
        assertEquals(SendResult.Success, first.await())
        assertEquals(SendResult.Success, second.await())
        assertEquals(412_350L - 1_000L, core.balanceSats.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentSendsCannotJointlyOverdraw() = runTest {
        // Read-check-debit must be serialized: only one of two racing near-full sends may land.
        val core = core()
        val first = async { core.send("jack@lark.money", 412_000L) }
        val second = async { core.send("maya@zaprite.com", 412_000L) }
        advanceUntilIdle()
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it == SendResult.Success })
        assertEquals(1, results.count { it == SendResult.Failure })
        assertEquals(412_350L - 412_000L, core.balanceSats.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendTakesTheInjectedDelayOnVirtualTime() = runTest {
        val core = core()
        val before = testScheduler.currentTime
        core.send("jack@lark.money", 520)
        assertEquals(1_500L, testScheduler.currentTime - before)
    }

    // --- Health & refresh ---

    @Test
    fun healthStartsReady() = runTest {
        assertEquals(HealthState.READY, core().health.value)
    }

    @Test
    fun forceHealthThenRefreshReturnsToReady() = runTest {
        val core = core()
        core.forceHealth(HealthState.STALE)
        assertEquals(HealthState.STALE, core.health.value)
        core.refresh()
        assertEquals(HealthState.READY, core.health.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshTakesTheInjectedDelayOnVirtualTime() = runTest {
        val core = core()
        core.forceHealth(HealthState.STALE)
        val before = testScheduler.currentTime
        core.refresh()
        assertEquals(1_500L, testScheduler.currentTime - before)
    }

    @Test
    fun advancedStatsReflectStaleVersusReady() = runTest {
        val core = core()
        core.forceHealth(HealthState.STALE)
        val stale = core.advancedStats()
        assertEquals("38 days ago", stale.funds.lastRefresh)
        assertEquals("block 916,980 · in 5 days", stale.funds.soonestExpiry)

        core.refresh()
        val ready = core.advancedStats()
        assertEquals("4 hours ago", ready.funds.lastRefresh)
        assertEquals("block 918,402 · in 27 days", ready.funds.soonestExpiry)
    }

    @Test
    fun advancedStatsReflectOffline() = runTest {
        val core = core()
        val ready = core.advancedStats()
        assertEquals("Connected", ready.network.arkServerStatus)
        assertEquals("in 41 seconds", ready.network.nextRound)

        core.forceHealth(HealthState.OFFLINE)
        val offline = core.advancedStats()
        assertEquals("Unreachable", offline.network.arkServerStatus)
        assertEquals("—", offline.network.nextRound)
    }

    @Test
    fun advancedStatsCarryThePrototypeConstants() = runTest {
        val stats = core().advancedStats()
        assertEquals(4, stats.funds.vtxoCount)
        assertEquals(412_350L, stats.funds.vtxoTotalSats)
        assertEquals(6_200L, stats.funds.onChainReserveSats)
        assertEquals("bc1qf7k29admq0wrx4lp8vun3zt6y", stats.funds.depositAddress)
        assertEquals("Open · 2 peers", stats.network.lightningBridge)
        assertEquals(916_214L, stats.network.chainTip)
    }

    // --- Health display copy (verbatim from the design's HEALTH map) ---

    @Test
    fun healthDisplayCopyMatchesTheDesign() {
        val ready = HealthState.READY.display
        assertEquals("Ready", ready.indicator.word)
        assertEquals("#6FE3A8", ready.indicator.dotColorHex)
        assertEquals("Everything’s ready.", ready.status.title)
        assertNull(ready.banner)
        assertNull(ready.status.actionLabel)
        assertEquals("Connected", ready.aspStatus)

        val tidying = HealthState.TIDYING.display
        assertEquals("Ready", tidying.indicator.word)
        assertNull(tidying.banner)

        val stale = HealthState.STALE.display
        assertEquals("Needs a moment", stale.indicator.word)
        assertEquals("#FF7A4D", stale.indicator.dotColorHex)
        assertEquals("Open me more often.", stale.status.title)
        assertEquals("Your wallet needs a moment", assertNotNull(stale.banner).title)
        assertEquals("One tap keeps everything spendable.", assertNotNull(stale.banner).body)
        assertEquals("Get it done", stale.status.actionLabel)
        assertEquals("Connected", stale.aspStatus)

        val offline = HealthState.OFFLINE.display
        assertEquals("Offline", offline.indicator.word)
        assertEquals("#FF7A4D", offline.indicator.dotColorHex)
        assertEquals("Can’t reach the network.", offline.status.title)
        assertEquals("Can’t reach the network", assertNotNull(offline.banner).title)
        assertEquals("Your money is safe. Payments resume when it’s back.", assertNotNull(offline.banner).body)
        assertEquals("Try again now", offline.status.actionLabel)
        assertEquals("Unreachable", offline.aspStatus)
    }

    // --- Wallet lifecycle (create/restore transitions live in the contract suite) ---

    @Test
    fun canStartWithAnExistingWallet() = runTest {
        assertTrue(FakeLarkCore(startWithWallet = true).walletExists.value)
    }

    // --- Backup ---

    @Test
    fun backupWordsMatchThePrototypeTwelve() {
        val words = core().backupWords
        assertEquals(12, words.size)
        assertEquals(
            listOf(
                "tide", "margin", "ocean", "lens", "quiet", "ember",
                "ladder", "forest", "plum", "signal", "harbor", "wren",
            ),
            words,
        )
    }

    // --- Static demo data ---

    @Test
    fun balanceAndRateMatchThePrototype() = runTest {
        val core = core()
        assertEquals(412_350L, core.balanceSats.value)
        assertEquals(10L, core.fiatRate.satsPerCent)
    }

    @Test
    fun activityMatchesThePrototype() {
        val activity = core().activity
        assertEquals(5, activity.size)
        assertEquals("Maya", activity[0].who)
        assertEquals("2 hours ago", activity[0].whenLabel)
        assertEquals(-14_200L, activity[0].sats)
        assertEquals("M", activity[0].initial)
        assertEquals(listOf(-14_200L, -520L, 250_000L, 180_000L, -9_800L), activity.map { it.sats })
        assertEquals(
            listOf("Maya", "Ferry Building Coffee", "Jack", "Added money", "Dani"),
            activity.map { it.who },
        )
        assertEquals(listOf("2 hours ago", "Yesterday", "Yesterday", "Mon", "Last week"), activity.map { it.whenLabel })
    }

    @Test
    fun recentsMatchThePrototype() {
        val recents = core().recents
        assertEquals(
            listOf("jack@lark.money", "maya@zaprite.com", "ferry@sq.link"),
            recents.map { it.handle },
        )
        assertEquals(listOf("Jack", "Maya", "Ferry Building Coffee"), recents.map { it.who })
        assertEquals(listOf("J", "M", "F"), recents.map { it.initial })
    }

    @Test
    fun codesAndNetworkLabelMatchTheSpec() {
        val core = core()
        assertEquals("ark1qf7…lark.money", core.receiveCode)
        assertEquals("bc1qf7k29admq0wrx4lp8vun3zt6y", core.depositAddress)
        assertEquals("mutinynet", core.networkLabel)
    }
}
