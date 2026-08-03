package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Entering a real destination (issue #14). The Pay flow used to set one hardcoded demo handle, so
 * the send path — including the channel route — was unreachable through the UI.
 *
 * Two rules carry the weight. Resolution has to mean *recognized as payable*, because Continue and
 * the gold border key off it. And an amount-bearing invoice fixes the amount, because `ldk-pay`
 * pays the invoice's figure whatever the keypad says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendInputTest {

    /** 500u on signet = 50,000 sats. */
    private val amountInvoice = "lntbs500u1qqqqsyqcyq5rqwzqfqypq"
    private val amountlessInvoice = "lntbs1qqqqsyqcyq5rqwzqfqypq"
    // Bech32-valid: the charset excludes b, i, o and 1, so a casual "demo" string is not an address.
    private val arkAddress = "ark1qf2knext"

    private fun TestScope.machine(): AppStateMachine = AppStateMachine(
        core = FakeLarkCore(startWithWallet = true),
        demo = null,
        scope = backgroundScope,
        nowMillis = { testScheduler.currentTime },
    )

    private fun AppStateMachine.atSendInput(): AppStateMachine = also { it.push(Route.SEND_INPUT) }

    // --- What resolves ---

    @Test
    fun anAmountBearingInvoiceResolvesAndStatesItsAmount() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(amountInvoice)

        with(m.model.value.send) {
            assertTrue(inputResolved)
            assertTrue(fixedAmount, "the invoice carries its own amount")
            assertEquals("Invoice for ₿50,000.", inputSummary)
        }
    }

    @Test
    fun anArkAddressResolvesWithoutFixingAnAmount() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(arkAddress)

        with(m.model.value.send) {
            assertTrue(inputResolved)
            assertFalse(fixedAmount, "an ark address names no amount")
        }
    }

    @Test
    fun anAmountlessInvoiceResolvesButFixesNoAmount() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(amountlessInvoice)

        with(m.model.value.send) {
            assertTrue(inputResolved)
            assertFalse(fixedAmount)
        }
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTolerated() = runTest {
        val m = machine().atSendInput()
        m.setSendInput("  $amountInvoice\n")

        assertTrue(m.model.value.send.inputResolved, "a pasted invoice often arrives with whitespace")
        assertTrue(m.model.value.send.fixedAmount)
    }

    // --- What must not look payable ---

    /** The whole point of the change: unparseable text must not offer Continue. */
    @Test
    fun unrecognizableTextDoesNotResolve() = runTest {
        val m = machine().atSendInput()
        m.setSendInput("hello there")

        with(m.model.value.send) {
            assertFalse(inputResolved, "non-empty is not the same as payable")
            assertEquals("That doesn’t look like an invoice or address LARK can pay.", inputSummary)
        }
    }

    @Test
    fun anOnchainOnlyBip321UriDoesNotResolve() = runTest {
        val m = machine().atSendInput()
        // No ark or lightning param: nothing this milestone's send path can pay.
        m.setSendInput("bitcoin:bc1qexampleaddress?amount=0.001")

        assertFalse(m.model.value.send.inputResolved)
    }

    @Test
    fun clearingReturnsToThePlaceholderAndUnresolvedState() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(amountInvoice)
        assertTrue(m.model.value.send.inputResolved)

        m.setSendInput("")
        with(m.model.value.send) {
            assertFalse(inputResolved)
            assertEquals("Name, invoice or address", inputDisplay)
        }
    }

    // --- A paste that yields nothing (iOS gates clipboard reads) ---

    /**
     * iOS prompts before any programmatic clipboard read, so the read can come back empty when the
     * user dismisses it. Silently doing nothing made PASTE look like a dead button — observed on
     * device. The screen must be told, and it must point at the ungated alternative.
     */
    @Test
    fun aPasteThatYieldsNothingSaysSoInsteadOfDoingNothing() = runTest {
        val m = machine().atSendInput()
        assertEquals(
            "A name, an invoice, or a bitcoin address — LARK works out the rest.",
            m.model.value.send.inputSummary,
        )

        m.sendInputPasteFailed()

        assertEquals(
            "Nothing came through from the clipboard. Long-press the field to paste, or type it in.",
            m.model.value.send.inputSummary,
        )
        assertFalse(m.model.value.send.inputResolved, "a failed paste resolves nothing")
    }

    /** Once something does arrive, the failure notice must not linger. */
    @Test
    fun aLaterSuccessfulEntryClearsThePasteNotice() = runTest {
        val m = machine().atSendInput()
        m.sendInputPasteFailed()

        m.setSendInput(amountInvoice)

        assertEquals("Invoice for ₿50,000.", m.model.value.send.inputSummary)
    }

    /** Clearing the field returns to the ordinary hint, not the failure notice. */
    @Test
    fun clearingAfterAFailedPasteReturnsToTheOrdinaryHint() = runTest {
        val m = machine().atSendInput()
        m.sendInputPasteFailed()
        m.setSendInput(amountInvoice)
        m.setSendInput("")

        assertEquals(
            "A name, an invoice, or a bitcoin address — LARK works out the rest.",
            m.model.value.send.inputSummary,
        )
    }

    // --- Where Continue goes ---

    /**
     * An amount-bearing invoice skips the keypad. Offering to type an amount would imply the user
     * could change it, while the channel path pays the invoice's own figure regardless — and a
     * mismatch between the two is exactly what makes routing fall back to Ark.
     */
    @Test
    fun anAmountBearingInvoiceGoesStraightToReviewWithItsOwnAmount() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(amountInvoice)
        m.continueFromSendInput()

        assertEquals(Route.REVIEW, m.model.value.route)
        assertEquals("₿50,000", m.model.value.keypad.amountDisplay)
    }

    @Test
    fun aDestinationWithoutAnAmountGoesToTheKeypad() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(arkAddress)
        m.continueFromSendInput()

        assertEquals(Route.AMOUNT, m.model.value.route)
    }

    /** The confirmed send must carry the full destination, not its abbreviation. */
    @Test
    fun theSendUsesTheWholeDestinationNotTheDisplayForm() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(amountInvoice)
        m.continueFromSendInput()
        m.confirmSend()

        assertEquals(amountInvoice, m.model.value.send.recipientHandle)
    }

    /** Retyping after an amount-bearing invoice must not leave the old amount behind. */
    @Test
    fun replacingTheDestinationDropsThePreviousInvoiceAmount() = runTest {
        val m = machine().atSendInput()
        m.setSendInput(amountInvoice)
        assertTrue(m.model.value.send.fixedAmount)

        m.setSendInput(arkAddress)
        assertFalse(m.model.value.send.fixedAmount, "the old invoice's amount must not persist")
        m.continueFromSendInput()
        assertEquals(Route.AMOUNT, m.model.value.route)
    }
}
