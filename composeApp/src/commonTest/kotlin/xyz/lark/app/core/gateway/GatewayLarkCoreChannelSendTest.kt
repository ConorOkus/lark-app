package xyz.lark.app.core.gateway

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Channel sends and their settlement (plan U4, R1/R4/R5/R6/R10/R11/R14).
 *
 * The channel path is the first send path in this app that can prove a payment settled, so most
 * of these tests are about refusing to claim more than was observed — and about never paying an
 * invoice twice when a failure's meaning is ambiguous.
 */
class GatewayLarkCoreChannelSendTest {

    /** 500u on signet = 50,000 sats, matching [SATS]. */
    private val invoice = "lntbs500u1qqqqsyqcyq5rqwzqfqypq"
    private val arkAddress = "ark1qf7demoaddressvalue"

    private companion object {
        const val SATS = 50_000L
        const val NOT_INITIALIZED = """{"message": "Failed to pay invoice: LDK node not initialized"}"""
    }

    private fun forkScript(): BarkdScript = BarkdScript(BarkdScript.forkDefaults)

    /** One usable channel with 500,000 sats of outbound liquidity — enough to carry [SATS]. */
    private fun BarkdScript.withUsableChannel(localMsat: Long = 500_000_000L) = apply {
        sticky(Paths.CHANNELS, BarkdScript.Json("[${channelJson("aa11bb22", localMsat = localMsat)}]"))
    }

    private fun BarkdScript.ldkPayReplies(vararg statuses: String) = apply {
        sticky(Paths.LDK_PAY, BarkdScript.Json(BarkdFixtures.forkLdkPayment(statuses.first())))
        statuses.drop(1).forEach { status ->
            enqueue(Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH), BarkdScript.Json(BarkdFixtures.forkLdkPayment(status)))
        }
    }

    /** A settled fork core with channels already polled into the routing snapshot. */
    private fun TestScope.settledForkCore(script: BarkdScript): GatewayLarkCore {
        val core = gatewayCore(barkdEngine(script), variant = BarkdApiVariant.FORK_BETA6)
        runCurrent()
        core.createWallet()
        runCurrent()
        return core
    }

    // --- Terminal outcomes ---

    @Test
    fun aPaymentThatReachesSentIsASuccess() = runTest {
        val script = forkScript().withUsableChannel().ldkPayReplies("sent")
        val core = settledForkCore(script)

        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertEquals(1, script.countOf(Paths.LDK_PAY))
        assertEquals(0, script.countOf(Paths.SEND), "a channel send must not also go over Ark")
    }

    @Test
    fun aPaymentThatReachesClaimedIsASuccess() = runTest {
        val script = forkScript().withUsableChannel().ldkPayReplies("claimed")
        assertEquals(SendResult.Success, settledForkCore(script).send(invoice, SATS))
    }

    @Test
    fun aFailedPaymentIsAFailureAndLeavesTheBalanceAlone() = runTest {
        val script = forkScript().withUsableChannel().ldkPayReplies("failed")
        val core = settledForkCore(script)
        val before = core.balanceSats.value

        assertEquals(SendResult.Failure, core.send(invoice, SATS))
        assertEquals(before, core.balanceSats.value, "the balance is never mutated locally")
    }

    @Test
    fun aClaimablePaymentThatLaterSettlesIsASuccess() = runTest {
        // Accepted as claimable, then two polls: still claimable, then sent.
        val script = forkScript().withUsableChannel().ldkPayReplies("claimable", "claimable", "sent")
        val core = settledForkCore(script)

        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertTrue(
            script.countOf(Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH)) >= 2,
            "settlement polls until the status is terminal",
        )
    }

    // --- The honest middle state ---

    @Test
    fun aPaymentStuckPendingPastTheBudgetIsPendingNotSuccessOrFailure() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Json(BarkdFixtures.forkLdkPayment("pending")))
        script.sticky(
            Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH),
            BarkdScript.Json(BarkdFixtures.forkLdkPayment("pending")),
        )
        val core = settledForkCore(script)

        assertEquals(SendResult.Pending, core.send(invoice, SATS))
        assertEquals(
            3,
            script.countOf(Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH)),
            "the settlement budget is bounded, not open-ended",
        )
    }

    /** A bounded wait must release the send mutex, or one stuck HTLC wedges every later send. */
    @Test
    fun aPendingSendLeavesTheSendPathUsable() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Json(BarkdFixtures.forkLdkPayment("pending")))
        script.sticky(
            Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH),
            BarkdScript.Json(BarkdFixtures.forkLdkPayment("pending")),
        )
        val core = settledForkCore(script)
        assertEquals(SendResult.Pending, core.send(invoice, SATS))

        script.sticky(Paths.LDK_PAY, BarkdScript.Json(BarkdFixtures.forkLdkPayment("sent")))
        assertEquals(SendResult.Success, core.send(invoice, SATS), "a later send still works")
    }

    /** An unknown status is not a verdict: keep waiting rather than inventing an outcome. */
    @Test
    fun anUnrecognizedStatusKeepsPollingAndEndsPending() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Json(BarkdFixtures.forkLdkPayment("quantum-tunnelling")))
        script.sticky(
            Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH),
            BarkdScript.Json(BarkdFixtures.forkLdkPayment("quantum-tunnelling")),
        )
        val core = settledForkCore(script)

        assertEquals(SendResult.Pending, core.send(invoice, SATS))
        assertEquals(3, script.countOf(Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH)))
    }

    /** A failed poll costs an attempt but is not mistaken for a verdict. */
    @Test
    fun aFailingSettlementPollDoesNotResolveThePayment() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Json(BarkdFixtures.forkLdkPayment("pending")))
        script.sticky(Paths.ldkPayment(BarkdFixtures.LDK_PAYMENT_HASH), BarkdScript.Broken("poll refused"))
        val core = settledForkCore(script)

        assertEquals(SendResult.Pending, core.send(invoice, SATS))
    }

    // --- The double-pay guard ---

    /**
     * Not-initialized proves the daemon has no LDK node and attempted nothing, so this send may
     * still complete over Ark — and every later send skips the LDK routes entirely.
     */
    @Test
    fun anUninitializedLdkFallsBackToArkInPlaceAndStopsRoutingThere() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Json(NOT_INITIALIZED, HttpStatusCode.InternalServerError))
        val core = settledForkCore(script)

        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertEquals(1, script.countOf(Paths.LDK_PAY))
        assertEquals(1, script.countOf(Paths.SEND), "the same send completed over Ark")

        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertEquals(1, script.countOf(Paths.LDK_PAY), "later sends no longer try the channel path")
        assertEquals(2, script.countOf(Paths.SEND))
    }

    /**
     * The guard that matters most: a generic failure cannot prove the payment was not attempted,
     * so retrying over Ark could pay the invoice twice. Refuse instead.
     */
    @Test
    fun aGenericLdkPayFailureNeverRetriesOverArk() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(
            Paths.LDK_PAY,
            BarkdScript.Json("""{"message": "route not found"}""", HttpStatusCode.InternalServerError),
        )
        val core = settledForkCore(script)

        assertEquals(SendResult.Failure, core.send(invoice, SATS))
        assertEquals(0, script.countOf(Paths.SEND), "no second payment attempt after an ambiguous failure")
    }

    @Test
    fun anUnreachableLdkPayNeverRetriesOverArk() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Broken("connection reset"))
        val core = settledForkCore(script)

        assertEquals(SendResult.Failure, core.send(invoice, SATS))
        assertEquals(0, script.countOf(Paths.SEND))
    }

    @Test
    fun anAcceptedPaymentWithNoUsableHashIsAFailureNotAnAcceptance() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.LDK_PAY, BarkdScript.Json("""{"payment_hash": "", "status": "pending"}"""))
        val core = settledForkCore(script)

        assertEquals(SendResult.Failure, core.send(invoice, SATS))
        assertEquals(0, script.countOf(Paths.SEND))
        assertEquals(0, script.countOf(Paths.ldkPayment("")))
    }

    // --- Route-aware spendability guard (R14) ---

    /**
     * `spendable_sat` counts VTXOs, not channel liquidity. A wallet with a funded channel and
     * almost no VTXOs must still be able to pay over the channel.
     */
    @Test
    fun aChannelSendAboveTheVtxoBalanceIsAllowedWhenOutboundLiquidityCoversIt() = runTest {
        val script = forkScript().withUsableChannel().ldkPayReplies("sent")
        script.sticky(Paths.BALANCE, BarkdScript.Json(BarkdFixtures.FORK_BALANCE.replace("412350", "1000")))
        val core = settledForkCore(script)
        assertEquals(1_000L, core.balanceSats.value)

        assertEquals(SendResult.Success, core.send(invoice, SATS), "the channel can carry it")
        assertEquals(1, script.countOf(Paths.LDK_PAY))
    }

    /** The same amount over Ark is still refused against the VTXO balance, unchanged. */
    @Test
    fun anArkSendAboveTheVtxoBalanceIsStillRefused() = runTest {
        val script = forkScript().withUsableChannel()
        script.sticky(Paths.BALANCE, BarkdScript.Json(BarkdFixtures.FORK_BALANCE.replace("412350", "1000")))
        val core = settledForkCore(script)

        assertEquals(SendResult.Failure, core.send(arkAddress, SATS))
        assertEquals(0, script.countOf(Paths.SEND), "the overdraw is refused before any request")
        assertEquals(0, script.countOf(Paths.LDK_PAY), "an ark address never reaches the channel path")
    }

    // --- Fallback routing never touches the LDK surface ---

    @Test
    fun anArkAddressOnTheForkNeverTouchesAnLdkEndpoint() = runTest {
        val script = forkScript().withUsableChannel()
        val core = settledForkCore(script)

        assertEquals(SendResult.Success, core.send(arkAddress, SATS))
        assertEquals(1, script.countOf(Paths.SEND))
        assertEquals(0, script.countOf(Paths.LDK_PAY))
    }

    @Test
    fun withNoUsableChannelTheInvoiceGoesOverArk() = runTest {
        // forkDefaults' channel fixture is deliberately not usable.
        val script = forkScript()
        val core = settledForkCore(script)

        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertEquals(0, script.countOf(Paths.LDK_PAY))
        assertEquals(1, script.countOf(Paths.SEND))
    }

    @Test
    fun anAmountThatDisagreesWithTheInvoiceGoesOverArk() = runTest {
        val script = forkScript().withUsableChannel()
        val core = settledForkCore(script)

        assertEquals(SendResult.Success, core.send(invoice, SATS + 1))
        assertEquals(0, script.countOf(Paths.LDK_PAY), "never pay a different amount than was approved")
        assertEquals(1, script.countOf(Paths.SEND))
    }

    // --- LDK unavailability: honest degradation, and no hammering (R10/R11) ---

    @Test
    fun anUninitializedLdkNodeStopsThePerCycleChannelPollingAndNeverAffectsHealth() = runTest {
        val script = forkScript()
        script.sticky(Paths.CHANNELS, BarkdScript.Json(NOT_INITIALIZED, HttpStatusCode.InternalServerError))
        val core = settledForkCore(script)

        assertEquals(HealthState.READY, core.health.value, "channel data is auxiliary, not liveness")
        val afterFirstCycle = script.countOf(Paths.CHANNELS)

        advanceThrough(15.seconds) // cycle 2 — skipped
        advanceThrough(15.seconds) // cycle 3 — skipped
        assertEquals(afterFirstCycle, script.countOf(Paths.CHANNELS), "no request every cycle any more")

        advanceThrough(15.seconds) // cycle 4 — the slow re-probe fires
        assertEquals(afterFirstCycle + 1, script.countOf(Paths.CHANNELS), "a later stack is still picked up")
        assertEquals(HealthState.READY, core.health.value)
    }

    @Test
    fun aChannelSnapshotStaysNullWhileTheLdkNodeIsUninitialized() = runTest {
        val script = forkScript()
        script.sticky(Paths.CHANNELS, BarkdScript.Json(NOT_INITIALIZED, HttpStatusCode.InternalServerError))
        val core = settledForkCore(script)

        assertEquals(null, core.channels.value, "never fetched, so the em-dash placeholder stands")
    }

    /** A recovered node resumes routing without a restart. */
    @Test
    fun anLdkNodeThatComesUpLaterIsPickedUpAndRoutesAgain() = runTest {
        val script = forkScript()
        script.sticky(Paths.CHANNELS, BarkdScript.Json(NOT_INITIALIZED, HttpStatusCode.InternalServerError))
        val core = settledForkCore(script)
        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertEquals(0, script.countOf(Paths.LDK_PAY), "routed over Ark while the node was down")

        script.withUsableChannel().ldkPayReplies("sent")
        advanceThrough(15.seconds)
        advanceThrough(15.seconds)
        advanceThrough(15.seconds) // the re-probe cycle lands and repopulates the channel list

        assertEquals(SendResult.Success, core.send(invoice, SATS))
        assertEquals(1, script.countOf(Paths.LDK_PAY), "the channel path is live again")
    }
}
