package xyz.lark.app.core.gateway

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Wallet lifecycle (plan R5), the R16 network guard, mnemonic handling (R15), and send
 * semantics including BIP-321 destination resolution.
 */
class GatewayLarkCoreWalletTest {

    // --- Wallet lifecycle ---

    @Test
    fun createWalletProbesFirstThenCreatesWithExpectedNetwork() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.WALLET, BarkdScript.Json(HealthyFixtures.NO_WALLET))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertFalse(core.walletExists.value)
        assertEquals(0, script.countOf(Paths.BALANCE), "no wallet -> no wallet-state fetches")

        script.sticky(Paths.WALLET, BarkdScript.Json(BarkdFixtures.WALLET_EXISTS))
        script.enqueue(Paths.WALLET, BarkdScript.Json(HealthyFixtures.NO_WALLET)) // create's own probe
        core.createWallet()
        runCurrent()

        assertTrue(core.walletExists.value)
        assertEquals(1, script.countOf(Paths.CREATE))
        val body = script.bodyOf(Paths.CREATE)
        assertTrue(body.contains("\"network\":\"mutinynet\""), body)
        assertFalse(body.contains("mnemonic"), "create-without-mnemonic: $body")
        assertTrue(script.countOf(Paths.BALANCE) >= 1, "adoption triggers an immediate poll")
    }

    @Test
    fun createWalletAdoptsAnExistingWalletWithoutCreateCall() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.WALLET, BarkdScript.Json(HealthyFixtures.NO_WALLET))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertFalse(core.walletExists.value)

        script.sticky(Paths.WALLET, BarkdScript.Json(BarkdFixtures.WALLET_EXISTS))
        core.createWallet()
        runCurrent()
        assertTrue(core.walletExists.value)
        assertEquals(0, script.countOf(Paths.CREATE), "probe-first: existing wallet is adopted, never re-created")
    }

    @Test
    fun racedCreateAlreadyExistsErrorIsAbsorbedAsAdopt() = runTest {
        val script = BarkdScript()
        script.enqueue(Paths.WALLET, BarkdScript.Json(HealthyFixtures.NO_WALLET), times = 2)
        script.sticky(Paths.WALLET, BarkdScript.Json(BarkdFixtures.WALLET_EXISTS))
        script.sticky(
            Paths.CREATE,
            BarkdScript.Json("""{"error": "wallet already exists"}""", HttpStatusCode.Conflict),
        )
        val core = gatewayCore(barkdEngine(script))
        runCurrent() // consumes the first no-wallet probe

        core.createWallet() // probe: no wallet -> create: 409 -> re-probe: fingerprint -> adopt
        runCurrent()
        assertTrue(core.walletExists.value)
        assertEquals(1, script.countOf(Paths.CREATE))
    }

    @Test
    fun restoreWalletIsCreateEquivalentThisMilestone() = runTest {
        val script = BarkdScript()
        script.enqueue(Paths.WALLET, BarkdScript.Json(HealthyFixtures.NO_WALLET), times = 2)
        script.sticky(Paths.WALLET, BarkdScript.Json(BarkdFixtures.WALLET_EXISTS))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        core.restoreWallet()
        runCurrent()
        assertTrue(core.walletExists.value)
        assertEquals(1, script.countOf(Paths.CREATE))
        assertFalse(script.bodyOf(Paths.CREATE).contains("mnemonic"), "restore = create-without-mnemonic (R5)")
    }

    // --- R16 network guard ---

    @Test
    fun networkMismatchIsAHardTerminalErrorState() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.ARK_INFO, BarkdScript.Json(BarkdFixtures.ARK_INFO)) // reports signet
        val core = gatewayCore(barkdEngine(script), expectedNetwork = "mutinynet")
        runCurrent()

        assertEquals(HealthState.OFFLINE, core.health.value)
        assertEquals(GatewayOfflineReason.NETWORK_MISMATCH, core.offlineReason.value)
        assertEquals(0, script.countOf(Paths.BALANCE), "no wallet-state fetch on the wrong chain")

        assertEquals(SendResult.Failure, core.send("ark1qf7demo", 100))
        assertEquals(0, script.countOf(Paths.SEND))
        assertTrue(core.backupWords.isEmpty())
        runCurrent()
        assertEquals(0, script.countOf(Paths.MNEMONIC))
        assertEquals("", core.receiveCode)

        advanceThrough(120.seconds)
        assertEquals(0, script.countOf(Paths.BALANCE), "polling is dead: the error is non-recoverable")
        assertEquals(HealthState.OFFLINE, core.health.value)
    }

    // --- Backup words (R5/R15) ---

    @Test
    fun backupWordsFetchOnFirstAccessAndSplitTheMnemonic() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(0, script.countOf(Paths.MNEMONIC), "mnemonic is fetched on demand, not by the poll loop")

        assertTrue(core.backupWords.isEmpty(), "first access schedules the fetch")
        runCurrent()
        assertEquals(
            listOf(
                "tide", "margin", "ocean", "lens", "quiet", "ember",
                "ladder", "forest", "plum", "signal", "harbor", "wren",
            ),
            core.backupWords,
        )
        assertEquals(1, script.countOf(Paths.MNEMONIC), "fetched once, then held for the display path")
    }

    @Test
    fun mnemonic404YieldsWordsUnavailableNeverFakeWords() = runTest {
        val script = BarkdScript()
        script.sticky(Paths.MNEMONIC, BarkdScript.Json("""{"error": "not exposed"}""", HttpStatusCode.NotFound))
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        assertTrue(core.backupWords.isEmpty())
        runCurrent()
        assertTrue(core.backupWords.isEmpty())
        runCurrent()
        assertEquals(1, script.countOf(Paths.MNEMONIC), "404 = words-unavailable on this gateway; no refetch loop")
    }

    // --- Send ---

    @Test
    fun sendSuccessReturnsSuccessAndBalanceArrivesViaTheNextPoll() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()
        assertEquals(412_350L, core.balanceSats.value)

        script.sticky(Paths.BALANCE, BarkdScript.Json(BarkdFixtures.BALANCE.replace("412350", "411830")))
        assertEquals(SendResult.Success, core.send("ark1qf7demo", 520))
        val body = script.bodyOf(Paths.SEND)
        assertTrue(body.contains("\"destination\":\"ark1qf7demo\""), body)
        assertTrue(body.contains("\"amount_sat\":520"), body)
        runCurrent()
        assertEquals(411_830L, core.balanceSats.value, "balance changes arrive via poll, never local mutation")
    }

    @Test
    fun sendHttpFailureReturnsFailureAndLeavesBalanceUntouched() = runTest {
        val script = BarkdScript()
        script.sticky(
            Paths.SEND,
            BarkdScript.Json("""{"error": "round failed"}""", HttpStatusCode.InternalServerError),
        )
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        assertEquals(SendResult.Failure, core.send("ark1qf7demo", 520))
        runCurrent()
        assertEquals(412_350L, core.balanceSats.value)
    }

    @Test
    fun sendRejectsNonPositiveAndOverBalanceLocally() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        assertEquals(SendResult.Failure, core.send("ark1qf7demo", 0))
        assertEquals(SendResult.Failure, core.send("ark1qf7demo", -5))
        assertEquals(SendResult.Failure, core.send("ark1qf7demo", core.balanceSats.value + 1))
        assertEquals(0, script.countOf(Paths.SEND), "local guards never reach the gateway")
    }

    @Test
    fun sendParsesBip321PreferringTheArkParam() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        val uri = "bitcoin:bc1qf7demo?lightning=lnbc210n1demo&ark=ark1preferred"
        assertEquals(SendResult.Success, core.send(uri, 100))
        assertTrue(script.bodyOf(Paths.SEND).contains("\"destination\":\"ark1preferred\""))
    }

    @Test
    fun sendFallsBackToBolt11WhenTheUriHasNoArkParam() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        val uri = "BITCOIN:bc1qf7demo?amount=0.001&lightning=lnbc210n1demo"
        assertEquals(SendResult.Success, core.send(uri, 100))
        assertTrue(script.bodyOf(Paths.SEND).contains("\"destination\":\"lnbc210n1demo\""))
    }

    @Test
    fun sendRejectsOnchainOnlyUrisThisMilestone() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        assertEquals(SendResult.Failure, core.send("bitcoin:bc1qf7demo", 100))
        assertEquals(SendResult.Failure, core.send("bitcoin:bc1qf7demo?amount=0.001", 100))
        assertEquals(0, script.countOf(Paths.SEND))
    }

    @Test
    fun resolveSendDestinationPassesRawDestinationsThrough() {
        assertEquals("ark1qf7demo", resolveSendDestination("  ark1qf7demo "))
        assertEquals("lnbc210n1demo", resolveSendDestination("lnbc210n1demo"))
        assertEquals("maya@zaprite.com", resolveSendDestination("maya@zaprite.com"))
        assertNull(resolveSendDestination("   "))
    }
}
