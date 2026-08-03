package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The landing for a send that was accepted but not observed to settle (plan U5, R5).
 *
 * Before this, the machine mapped anything-but-Success to "Didn't go through." — so a payment
 * that may still be in flight would have told the user it failed. The mapping is now exhaustive
 * over [SendResult], and each outcome gets its own screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingLandingTest {

    /** A core whose send always resolves to [result], to drive one landing at a time. */
    private class FixedOutcomeCore(
        private val result: SendResult,
        private val fake: FakeLarkCore = FakeLarkCore(startWithWallet = true),
    ) : LarkCore by fake {
        override suspend fun send(recipient: String, sats: Long): SendResult = result
    }

    private fun TestScope.machineWith(result: SendResult): AppStateMachine = AppStateMachine(
        core = FixedOutcomeCore(result),
        demo = null,
        scope = backgroundScope,
        nowMillis = { testScheduler.currentTime },
    )

    private fun AppStateMachine.sendOf(digits: String) {
        goSendAmount()
        digits.forEach { keyPress(it) }
        keypadConfirm()
        confirmSend()
    }

    @Test
    fun aPendingSendLandsOnItsOwnRouteRatherThanSentOrFailed() = runTest {
        val m = machineWith(SendResult.Pending)
        m.sendOf("520")
        runCurrent()

        assertEquals(Route.PENDING, m.model.value.route)
        assertNotEquals(Route.SENT, m.model.value.route, "settlement was never observed")
        assertNotEquals(Route.FAILED, m.model.value.route, "the money may still be moving")
    }

    /** The landing shows the confirmed snapshot, exactly as the sent screen does. */
    @Test
    fun thePendingLandingCarriesTheConfirmedAmount() = runTest {
        val m = machineWith(SendResult.Pending)
        m.sendOf("520")
        runCurrent()

        assertEquals("₿520", m.model.value.sentAmount)
        assertTrue(m.model.value.send.recipientName.isNotEmpty(), "the recipient line is populated")
    }

    @Test
    fun thePendingRouteIsLabelledAsInFlightNotAsSent() = runTest {
        val m = machineWith(SendResult.Pending)
        m.sendOf("520")
        runCurrent()

        val label = m.model.value.screenLabel
        assertEquals(Route.PENDING.screenLabel, label)
        assertNotEquals(Route.SENT.screenLabel, label)
    }

    /** Leaving the landing behaves like the sent screen: back is meaningful and lands home. */
    @Test
    fun backFromThePendingLandingIsStillMeaningful() = runTest {
        val m = machineWith(SendResult.Pending)
        m.sendOf("520")
        runCurrent()
        assertTrue(m.model.value.canGoBack)

        m.back()
        assertEquals(Route.HOME, m.model.value.route)
    }

    // --- Regressions: the other two outcomes are unmoved ---

    @Test
    fun successStillLandsSent() = runTest {
        val m = machineWith(SendResult.Success)
        m.sendOf("520")
        runCurrent()
        assertEquals(Route.SENT, m.model.value.route)
    }

    @Test
    fun failureStillLandsFailed() = runTest {
        val m = machineWith(SendResult.Failure)
        m.sendOf("520")
        runCurrent()
        assertEquals(Route.FAILED, m.model.value.route)
    }
}
