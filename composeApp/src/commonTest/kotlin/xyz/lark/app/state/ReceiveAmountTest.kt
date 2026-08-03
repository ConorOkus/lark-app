package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Get paid's requested amount reaching the core, and its code reaching the screen (plan U7).
 *
 * The receive keypad used to drop its digits on the way back — the amount annotated nothing and
 * the code never changed. Now it is the input to a code that can carry a Lightning invoice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiveAmountTest {

    /** Records the amounts asked for and answers with an amount-specific code. */
    private class RecordingReceiveCore(
        private val fake: FakeLarkCore = FakeLarkCore(startWithWallet = true),
    ) : LarkCore by fake {
        val asked = mutableListOf<Long>()

        override suspend fun requestReceiveCode(sats: Long): String {
            asked += sats
            return "${fake.receiveCode}&lightning=lntbs${sats}invoice"
        }
    }

    private fun TestScope.machineWith(core: LarkCore): AppStateMachine = AppStateMachine(
        core = core,
        demo = null,
        scope = backgroundScope,
        nowMillis = { testScheduler.currentTime },
    )

    private fun AppStateMachine.requestAmount(digits: String) {
        goReceiveAmount()
        digits.forEach { keyPress(it) }
        keypadConfirm()
    }

    // --- The amount reaches the core ---

    @Test
    fun confirmingAReceiveAmountAsksTheCoreForThatExactAmount() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        runCurrent()

        assertEquals(listOf(520L), core.asked)
    }

    @Test
    fun theRequestedAmountCodeIsWhatTheScreenRenders() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        runCurrent()

        assertEquals("${core.receiveCode}&lightning=lntbs520invoice", m.model.value.receive.code)
    }

    @Test
    fun theRequestedAmountIsShownAlongsideTheCode() = runTest {
        val m = machineWith(RecordingReceiveCore())

        m.requestAmount("520")
        runCurrent()

        assertEquals("₿520", m.model.value.receive.requestedAmount)
    }

    /** Confirming returns to Get paid — the screen the "Set amount" affordance is reached from. */
    @Test
    fun confirmingAReceiveAmountLandsBackOnGetPaid() = runTest {
        val m = machineWith(RecordingReceiveCore())
        m.go(Route.RECEIVE)
        m.goReceiveAmount()
        assertEquals(Route.AMOUNT, m.model.value.route)

        m.type("520")
        m.keypadConfirm()
        assertEquals(Route.RECEIVE, m.model.value.route)
    }

    // --- The amountless code is unchanged (R9) ---

    @Test
    fun withNoAmountTheCoreIsNeverAskedAndTheAmountlessCodeStands() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)

        assertEquals(core.receiveCode, m.model.value.receive.code)
        assertNull(m.model.value.receive.requestedAmount)
        assertTrue(core.asked.isEmpty())
    }

    @Test
    fun clearingTheAmountReturnsToTheAmountlessCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.requestAmount("520")
        runCurrent()
        assertEquals("₿520", m.model.value.receive.requestedAmount)

        m.clearReceiveAmount()
        runCurrent()

        assertNull(m.model.value.receive.requestedAmount)
        assertEquals(core.receiveCode, m.model.value.receive.code)
    }

    /** The single affordance sets an amount when there is none and drops it when there is. */
    @Test
    fun theAmountAffordanceTogglesBetweenSettingAndClearing() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.go(Route.RECEIVE)

        m.toggleReceiveAmount()
        assertEquals(Route.AMOUNT, m.model.value.route, "with no amount set, it opens the keypad")
        m.type("520")
        m.keypadConfirm()
        runCurrent()
        assertEquals("₿520", m.model.value.receive.requestedAmount)

        m.toggleReceiveAmount()
        runCurrent()
        assertNull(m.model.value.receive.requestedAmount, "with one set, it clears it")
        assertEquals(Route.RECEIVE, m.model.value.route, "and stays on Get paid")
    }

    // --- Asynchrony discipline ---

    /** Until the code lands, the amountless code stands: never blank, never a stale attribution. */
    @Test
    fun theAmountlessCodeStandsUntilTheRequestedCodeLands() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        // Deliberately before runCurrent(): the mint has not answered yet.
        assertEquals(core.receiveCode, m.model.value.receive.code)
        assertEquals("₿520", m.model.value.receive.requestedAmount, "the ask is visible immediately")

        runCurrent()
        assertEquals("${core.receiveCode}&lightning=lntbs520invoice", m.model.value.receive.code)
    }

    @Test
    fun aSecondAmountSupersedesTheFirstCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)

        m.requestAmount("520")
        m.requestAmount("1000")
        runCurrent()

        assertEquals("₿1,000", m.model.value.receive.requestedAmount)
        assertEquals("${core.receiveCode}&lightning=lntbs1000invoice", m.model.value.receive.code)
    }

    /** A core that declines to mint still yields a usable code, with no error state. */
    @Test
    fun aCoreThatOffersOnlyTheArkCodeStillRendersAScannableCode() = runTest {
        val fake = FakeLarkCore(startWithWallet = true)
        val m = machineWith(fake) // inherits the seam default: the amountless code
        m.requestAmount("520")
        runCurrent()

        assertEquals(fake.receiveCode, m.model.value.receive.code)
        assertEquals("₿520", m.model.value.receive.requestedAmount, "the ask is still stated")
    }

    /** Copy must copy what is on screen — the composed code, not the bare address. */
    @Test
    fun copyingCopiesTheRenderedCode() = runTest {
        val core = RecordingReceiveCore()
        val m = machineWith(core)
        m.requestAmount("520")
        runCurrent()

        m.copyCode()
        assertEquals("Copied", m.model.value.receive.copyLabel)
        assertEquals("${core.receiveCode}&lightning=lntbs520invoice", m.model.value.receive.code)
    }
}

/** Types each digit of [digits] on the keypad. */
private fun AppStateMachine.type(digits: String) = digits.forEach { keyPress(it) }
