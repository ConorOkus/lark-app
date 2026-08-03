package xyz.lark.app.core.gateway

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.model.HealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Channel receive (plan U6, R7/R8/R9). The inbound-liquidity guard is the point: a channel the
 * wallet funded itself has no inbound capacity, so the honest answer is usually the plain ark
 * code. An invoice nobody can pay is worse than offering one destination fewer.
 */
class GatewayLarkCoreChannelReceiveTest {

    private companion object {
        const val SATS = 50_000L
        const val ARK_URI = "bitcoin:?ark=ark1qf2knext"
        const val NOT_INITIALIZED = """{"message": "Failed to create invoice: LDK node not initialized"}"""
        val INVOICE = BarkdFixtures.FORK_LDK_INVOICE
            .substringAfter("\"bolt11\": \"").substringBefore("\"")
    }

    private fun forkScript(): BarkdScript = BarkdScript(BarkdScript.forkDefaults)

    /**
     * A usable channel with [inboundSat] on the counterparty's side. Capacity minus our balance
     * is the only available source for inbound, so it is expressed that way here too.
     */
    private fun BarkdScript.withInbound(inboundSat: Long, capacitySat: Long = 1_000_000L) = apply {
        val localMsat = (capacitySat - inboundSat) * 1_000L
        sticky(
            Paths.CHANNELS,
            BarkdScript.Json("[${channelJson("aa11bb22", localMsat = localMsat, capacitySat = capacitySat)}]"),
        )
    }

    private fun TestScope.settledForkCore(script: BarkdScript): GatewayLarkCore {
        val core = gatewayCore(barkdEngine(script), variant = BarkdApiVariant.FORK_BETA6)
        runCurrent()
        core.createWallet()
        runCurrent()
        return core
    }

    // --- The channel receive path ---

    @Test
    fun anAmountWithinInboundLiquidityMintsAnInvoiceAndOffersBothDestinations() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        val core = settledForkCore(script)

        val code = core.requestReceiveCode(SATS)
        assertEquals("$ARK_URI&lightning=$INVOICE", code)
        assertEquals(1, script.countOf(Paths.LDK_INVOICE))
    }

    @Test
    fun theMintedInvoiceIsForExactlyTheRequestedAmountAndCarriesAnExplicitExpiry() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        val core = settledForkCore(script)
        core.requestReceiveCode(SATS)

        val body = script.bodyOf(Paths.LDK_INVOICE)
        assertTrue(body.contains("\"amount_sat\":$SATS"), body)
        assertTrue(body.contains("\"expiry_secs\":3600"), body)
    }

    /** `ark` must stay first: the app's own send-side parser prefers it over `lightning`. */
    @Test
    fun theComposedCodeKeepsArkAsThePreferredDestination() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        val code = settledForkCore(script).requestReceiveCode(SATS)

        assertTrue(code.indexOf("ark=") < code.indexOf("lightning="), code)
        assertEquals(SendRoute.OverChannel(INVOICE).bolt11, INVOICE, "sanity: the invoice is what was embedded")
    }

    // --- Honest degradation (R8/R9/R10) ---

    /** The expected live state: we funded the channel, so there is nothing to receive into. */
    @Test
    fun aFreshlyFundedChannelMintsNothingAndStaysArkOnly() = runTest {
        val script = forkScript().withInbound(inboundSat = 0L)
        val core = settledForkCore(script)

        assertEquals(ARK_URI, core.requestReceiveCode(SATS))
        assertEquals(0, script.countOf(Paths.LDK_INVOICE), "never mint an invoice nobody can pay")
    }

    @Test
    fun anAmountJustAboveInboundLiquidityStaysArkOnly() = runTest {
        val script = forkScript().withInbound(inboundSat = SATS - 1)
        val core = settledForkCore(script)

        assertEquals(ARK_URI, core.requestReceiveCode(SATS))
        assertEquals(0, script.countOf(Paths.LDK_INVOICE))
    }

    @Test
    fun exactlyEnoughInboundLiquidityIsEnough() = runTest {
        val script = forkScript().withInbound(inboundSat = SATS)
        val core = settledForkCore(script)

        assertTrue(core.requestReceiveCode(SATS).contains("lightning="))
    }

    @Test
    fun anAmountlessRequestStaysArkOnly() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        val core = settledForkCore(script)

        assertEquals(ARK_URI, core.requestReceiveCode(0L), "ldk-invoice requires an amount")
        assertEquals(ARK_URI, core.receiveCode, "the amountless code is unchanged")
        assertEquals(0, script.countOf(Paths.LDK_INVOICE))
    }

    @Test
    fun anUninitializedLdkNodeStaysArkOnlyAndNeverAffectsHealth() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        script.sticky(Paths.LDK_INVOICE, BarkdScript.Json(NOT_INITIALIZED, HttpStatusCode.InternalServerError))
        val core = settledForkCore(script)

        assertEquals(ARK_URI, core.requestReceiveCode(SATS))
        assertEquals(HealthState.READY, core.health.value, "channel data is auxiliary, not liveness")

        // The unavailability is remembered: a second request does not re-mint.
        assertEquals(ARK_URI, core.requestReceiveCode(SATS))
        assertEquals(1, script.countOf(Paths.LDK_INVOICE))
    }

    @Test
    fun aFailedMintLeavesTheCodeUsable() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        script.sticky(Paths.LDK_INVOICE, BarkdScript.Broken("connection reset"))
        val core = settledForkCore(script)

        assertEquals(ARK_URI, core.requestReceiveCode(SATS), "a scannable code, just ark-only")
    }

    /** A URI-breaking invoice must never be embedded — the same rule addresses already follow. */
    @Test
    fun aUriBreakingInvoiceIsNotEmbedded() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        script.sticky(
            Paths.LDK_INVOICE,
            BarkdScript.Json("""{"bolt11": "lntbs1&evil=1", "payment_hash": "ab"}"""),
        )
        val core = settledForkCore(script)

        assertEquals(ARK_URI, core.requestReceiveCode(SATS))
        assertFalse(core.requestReceiveCode(SATS).contains("evil"))
    }

    // --- Minting economy ---

    @Test
    fun theSameAmountRequestedTwiceMintsOnce() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        val core = settledForkCore(script)

        val first = core.requestReceiveCode(SATS)
        val second = core.requestReceiveCode(SATS)
        assertEquals(first, second)
        assertEquals(1, script.countOf(Paths.LDK_INVOICE))
    }

    @Test
    fun aDifferentAmountMintsAgain() = runTest {
        val script = forkScript().withInbound(inboundSat = 200_000L)
        val core = settledForkCore(script)

        core.requestReceiveCode(SATS)
        core.requestReceiveCode(SATS + 1_000L)
        assertEquals(2, script.countOf(Paths.LDK_INVOICE))
    }

    // --- Other surfaces are untouched (R13) ---

    @Test
    fun theStockSurfaceReturnsTodaysCodeAndNeverTouchesTheLdkRoute() = runTest {
        val script = BarkdScript()
        val core = gatewayCore(barkdEngine(script))
        runCurrent()

        val code = core.requestReceiveCode(SATS)
        assertEquals(core.receiveCode, code)
        assertEquals(0, script.countOf(Paths.LDK_INVOICE))
    }

    @Test
    fun theDemoCoreInheritsTheAmountlessCode() = runTest {
        val fake = FakeLarkCore(startWithWallet = true)
        assertEquals(fake.receiveCode, fake.requestReceiveCode(SATS))
    }
}
