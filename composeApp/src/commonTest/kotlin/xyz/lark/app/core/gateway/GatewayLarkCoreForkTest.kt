package xyz.lark.app.core.gateway

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.ChannelState
import xyz.lark.app.core.model.HealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Capability-driven degradations on the fork surface (plan U3): against
 * [BarkdApiVariant.FORK_BETA6] the core long-polls nothing, treats backup words as
 * unavailable, mints its receive address client-side, speaks the fork create shape without
 * a wallet-existence probe, and extends the R16 identity check to `supports_channels` —
 * while the stock twins in the sibling test files stay untouched and green.
 */
class GatewayLarkCoreForkTest {

    private fun forkScript(): BarkdScript = BarkdScript(BarkdScript.forkDefaults)

    private fun TestScope.forkCore(script: BarkdScript): GatewayLarkCore =
        gatewayCore(barkdEngine(script), variant = BarkdApiVariant.FORK_BETA6)

    /** Fork daemons are probe-less: the wallet only becomes ours through `createWallet()`. */
    private fun TestScope.settledForkCore(script: BarkdScript): GatewayLarkCore {
        val core = forkCore(script)
        runCurrent()
        core.createWallet()
        runCurrent()
        return core
    }

    // --- Notifications: poll cadence alone ---

    @Test
    fun forkNeverIssuesANotificationsWaitRequest() = runTest {
        val script = forkScript()
        val core = settledForkCore(script)
        assertEquals(HealthState.READY, core.health.value)

        script.sticky(Paths.BALANCE, BarkdScript.Json(BarkdFixtures.FORK_BALANCE.replace("412350", "500000")))
        advanceThrough(15.seconds)
        assertEquals(500_000L, core.balanceSats.value, "poll cadence alone keeps the state fresh")
        advanceThrough(120.seconds)
        assertEquals(0, script.countOf(Paths.WAIT), "the fork has no notifications endpoint: never long-polled")
    }

    // --- Backup words ---

    @Test
    fun forkBackupWordsAreImmediatelyUnavailableWithoutAMnemonicRequest() = runTest {
        val script = forkScript()
        val core = settledForkCore(script)

        assertTrue(core.backupWords.isEmpty(), "no mnemonic endpoint: words-unavailable, never fake words")
        runCurrent()
        assertTrue(core.backupWords.isEmpty())
        assertEquals(0, script.countOf(Paths.MNEMONIC), "the fork mnemonic route must never be requested")
    }

    // --- Receive: client-side minting ---

    @Test
    fun forkMintsOneReceiveAddressPerSessionAndBuildsTheUriClientSide() = runTest {
        val script = forkScript()
        val core = settledForkCore(script)

        assertEquals("bitcoin:?ark=ark1qf2knext", core.receiveCode)
        assertEquals(1, script.countOf(Paths.NEXT_ADDRESS))
        assertEquals(0, script.countOf(Paths.BIP321), "the fork has no bip321 endpoint")
        assertEquals("", core.depositAddress, "no onchain URI source: honestly absent, never faked")

        advanceThrough(45.seconds)
        assertEquals(1, script.countOf(Paths.NEXT_ADDRESS), "one address per session: later cycles hit the cache")
        assertEquals("bitcoin:?ark=ark1qf2knext", core.receiveCode)
    }

    @Test
    fun forkReceiveRejectsAUriBreakingAddressIntoTheNoCodeState() = runTest {
        val script = forkScript()
        script.sticky(Paths.NEXT_ADDRESS, BarkdScript.Json("""{"address": "ark1qbad&extra=1"}"""))
        val core = settledForkCore(script)

        assertEquals("", core.receiveCode, "a URI-breaking address must never be embedded")
        assertEquals(HealthState.READY, core.health.value, "a bogus address degrades receive, not health")

        advanceThrough(45.seconds)
        assertEquals("", core.receiveCode)
        assertEquals(1, script.countOf(Paths.NEXT_ADDRESS), "rejection must not re-mint an address every cycle")
    }

    // --- Create / adopt (no wallet-existence probe on the fork) ---

    @Test
    fun forkCreateOnFreshDaemonPostsTheForkShapeAndLandsReady() = runTest {
        val script = forkScript()
        val core = settledForkCore(script)

        assertTrue(core.walletExists.value)
        assertEquals(HealthState.READY, core.health.value)
        assertEquals(0, script.countOf(Paths.WALLET), "the fork has no wallet-existence probe")
        assertEquals(1, script.countOf(Paths.CREATE))
        val body = script.bodyOf(Paths.CREATE)
        assertTrue(body.contains("\"network\":\"signet\""), body)
        assertTrue(body.contains("\"ark_server\":\"http://captaind.test:3535\""), body)
        assertTrue(body.contains("\"chain_source\":{\"esplora\":{\"url\":\"http://esplora.test:3003\"}}"), body)
        assertFalse(body.contains("mnemonic"), "create-without-mnemonic: $body")
    }

    @Test
    fun forkCreateAlreadyExistsErrorAdoptsTheSingleWallet() = runTest {
        val script = forkScript()
        script.sticky(
            Paths.CREATE,
            BarkdScript.Json("""{"message": "wallet already exists"}""", HttpStatusCode.InternalServerError),
        )
        val core = settledForkCore(script)

        assertTrue(core.walletExists.value, "single-wallet daemon: an existing wallet is ours to adopt")
        assertEquals(HealthState.READY, core.health.value)
        assertEquals(1, script.countOf(Paths.CREATE))
        assertEquals(0, script.countOf(Paths.WALLET), "no probe exists to consult, before or after create")
    }

    @Test
    fun forkCreateFailureWithoutAnAnsweringWalletIsNotAdopted() = runTest {
        val script = forkScript()
        script.sticky(
            Paths.CREATE,
            BarkdScript.Json("""{"message": "esplora unreachable"}""", HttpStatusCode.InternalServerError),
        )
        script.sticky(
            Paths.BALANCE,
            BarkdScript.Json("""{"message": "no wallet"}""", HttpStatusCode.InternalServerError),
        )
        val core = settledForkCore(script)

        assertFalse(core.walletExists.value, "a failed create on a walletless daemon must not fake adoption")
    }

    // --- R16 identity: network AND channel support ---

    @Test
    fun forkAgainstAServerWithoutChannelSupportHardFailsOffline() = runTest {
        val script = forkScript()
        // Stock-shaped ark-info: the right network, but no supports_channels field at all.
        val stockInfoOnSignet = HealthyFixtures.ARK_INFO_MUTINYNET.replace("mutinynet", "signet")
        script.sticky(Paths.ARK_INFO, BarkdScript.Json(stockInfoOnSignet))
        val core = settledForkCore(script)

        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.NETWORK_MISMATCH, core.offlineReason.value)
        assertEquals(0, script.countOf(Paths.BALANCE), "no wallet-state fetch on a server without channels")

        advanceThrough(120.seconds)
        assertEquals(0, script.countOf(Paths.BALANCE), "polling is dead: the error is non-recoverable")
        assertEquals(HealthState.OFFLINE, core.health.value)
    }

    @Test
    fun forkAgainstAServerReportingChannelSupportFalseHardFailsOffline() = runTest {
        val script = forkScript()
        script.sticky(
            Paths.ARK_INFO,
            BarkdScript.Json(
                BarkdFixtures.FORK_ARK_INFO.replace("\"supports_channels\": true", "\"supports_channels\": false"),
            ),
        )
        val core = settledForkCore(script)

        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.NETWORK_MISMATCH, core.offlineReason.value)
    }

    @Test
    fun forkNetworkMismatchStillHardFailsOffline() = runTest {
        val script = forkScript()
        // The daemon reports a different network id than the fixture's real-world signet.
        script.sticky(Paths.ARK_INFO, BarkdScript.Json(BarkdFixtures.FORK_ARK_INFO.replace("signet", "mutinynet")))
        val core = settledForkCore(script)

        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.NETWORK_MISMATCH, core.offlineReason.value)
    }

    @Test
    fun forkWalletlessUnreachableDaemonReadsOffline() = runTest {
        // Every endpoint down: onboarding against a dead gateway must not idle at READY.
        val script = BarkdScript(BarkdScript.forkDefaults.mapValues { BarkdScript.Broken("daemon down") })
        val core = forkCore(script)
        advanceThrough(15.seconds)

        assertEquals(HealthState.OFFLINE, core.health.value, "walletless reachability must classify")
        assertEquals(GatewayOfflineReason.UNREACHABLE, core.offlineReason.value)
        assertFalse(core.walletExists.value)
    }

    // --- Channels snapshot (plan U4) ---

    @Test
    fun forkPollCycleWithTwoChannelsPopulatesTheChannelsSnapshot() = runTest {
        val script = forkScript()
        val usable = channelJson(
            channelId = "741e8bd3".repeat(8),
            localMsat = 300_000_000L,
            capacitySat = 1_000_000L,
            expiryHeight = 916_214L + 144L,
        )
        val opening = channelJson(
            channelId = "9a9a9a9a",
            localMsat = 200_000_000L,
            capacitySat = 500_000L,
            isUsable = false,
            isChannelReady = false,
        )
        script.sticky(Paths.CHANNELS, BarkdScript.Json("[$usable, $opening]"))
        script.sticky(Paths.CHANNELS_BALANCE, BarkdScript.Json("""{"balance_sat": 500000}"""))
        val core = settledForkCore(script)

        val snapshot = assertNotNull(core.channels.value, "a successful poll must populate the snapshot")
        assertEquals(2, snapshot.channels.size)
        assertEquals(500_000L, snapshot.totalLocalSat, "the bridge total is the gateway's channels/balance figure")
        assertEquals(snapshot.totalLocalSat, snapshot.channels.sumOf { it.localSat })
        val first = snapshot.channels[0]
        assertEquals("741e8bd3…8bd3", first.shortId)
        assertEquals(300_000L, first.localSat)
        assertEquals(1_000_000L, first.capacitySat)
        assertEquals(ChannelState.USABLE, first.state)
        assertEquals("block 916,358 · in 1 day", first.expiryLabel, "countdown against the tip the harness serves")
        val second = snapshot.channels[1]
        assertEquals(ChannelState.OPENING, second.state, "not-ready reads as still opening")
        assertEquals("—", second.expiryLabel, "no expiry height: em-dash, never a fake countdown")
    }

    @Test
    fun forkChannelsSnapshotIsNullBeforeTheFirstPollAndEmptyAfterAZeroChannelPoll() = runTest {
        val script = forkScript()
        script.sticky(Paths.CHANNELS, BarkdScript.Json("[]"))
        script.sticky(Paths.CHANNELS_BALANCE, BarkdScript.Json("""{"balance_sat": 0}"""))
        val core = forkCore(script)
        assertNull(core.channels.value, "never fetched reads as null, not as zero channels")
        runCurrent()
        assertNull(core.channels.value, "no wallet yet: still never fetched")

        core.createWallet()
        runCurrent()
        val snapshot = assertNotNull(core.channels.value, "polled-and-zero-channels is non-null")
        assertTrue(snapshot.channels.isEmpty())
        assertEquals(0L, snapshot.totalLocalSat)
    }

    @Test
    fun forkChannelFetchFailureKeepsHealthAndThePreviousSnapshot() = runTest {
        val script = forkScript()
        val serverError = BarkdScript.Json("""{"message": "boom"}""", HttpStatusCode.InternalServerError)
        script.sticky(Paths.CHANNELS, serverError)
        val core = settledForkCore(script)

        assertNull(core.channels.value, "a failed fetch never fakes an empty snapshot")
        assertEquals(HealthState.READY, core.health.value, "channel data is auxiliary, not liveness")

        // Let one cycle succeed (the fork default serves one opening channel), then break it again.
        script.clearSticky(Paths.CHANNELS)
        advanceThrough(15.seconds)
        val populated = assertNotNull(core.channels.value)
        assertEquals(1, populated.channels.size)

        script.sticky(Paths.CHANNELS, serverError)
        script.sticky(Paths.CHANNELS_BALANCE, serverError)
        advanceThrough(45.seconds)
        assertEquals(populated, core.channels.value, "failed cycles keep the last good snapshot")
        assertEquals(HealthState.READY, core.health.value, "even a 5xx streak on channels never flips health")
    }

    @Test
    fun stockVariantKeepsTheChannelsSnapshotNullAndNeverCallsChannelEndpoints() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        advanceThrough(45.seconds)

        assertNull(core.channels.value, "stock has no channel surface: forever never-fetched")
        assertEquals(HealthState.READY, core.health.value)
        assertEquals(0, script.countOf(Paths.CHANNELS), "the stock core must never request channel paths")
        assertEquals(0, script.countOf(Paths.CHANNELS_BALANCE))
    }

    // --- networkLabel decoupling (R5: mutinynet identifies as signet on the wire) ---

    @Test
    fun networkLabelIsDecoupledFromTheExpectedNetwork() = runTest {
        val core = gatewayCore(
            barkdEngine(forkScript()),
            variant = BarkdApiVariant.FORK_BETA6,
            expectedNetwork = "signet",
            networkLabel = "mutinynet",
        )
        assertEquals("mutinynet", core.networkLabel)
    }

    // --- Pure mapper: the client-side receive URI guard ---

    @Test
    fun arkReceiveUriEmbedsOnlyBech32ShapedAddresses() {
        assertEquals("bitcoin:?ark=tark1q2v9lfmk", arkReceiveUri("tark1q2v9lfmk"))
        assertEquals("bitcoin:?ark=ark1qf2knext", arkReceiveUri("ark1qf2knext"))
        assertNull(arkReceiveUri(""))
        assertNull(arkReceiveUri("ARK1QUPPER"), "uppercase never embeds")
        assertNull(arkReceiveUri("ark1qbad&extra"), "URI-breaking characters never embed")
        assertNull(arkReceiveUri("ark1qbio"), "b/i/o sit outside the bech32 data charset")
        assertNull(arkReceiveUri("ark1"), "an empty data part is not an address")
        assertNull(arkReceiveUri("1qqqq"), "the human-readable part cannot be empty")
        assertNull(arkReceiveUri("qqqqqq"), "no separator, no address")
    }
}
