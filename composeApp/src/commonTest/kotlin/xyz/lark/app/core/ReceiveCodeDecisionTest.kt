package xyz.lark.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The receive-code decision, which is the whole of what Get paid hands out.
 *
 * Pinned here rather than through the core that calls it because that core is `iosMain` and has
 * no test lane. These assertions are the only thing standing between a degraded mint and a QR
 * that resolves to nothing — the failure this whole path exists to avoid.
 */
class ReceiveCodeDecisionTest {

    @Test
    fun anAmountWithAMintedInvoiceCarriesBothDestinations() {
        // Ark first: the app's own send-side parser prefers it, so a Lark payer settles out of
        // round instead of being pushed through the server's HTLC machinery for the same money.
        assertEquals(
            "$ARK_URI&lightning=$INVOICE",
            receiveCodeFor(ARK_URI, SATS, INVOICE),
        )
    }

    @Test
    fun anAmountlessAskIgnoresAnyInvoiceItIsHanded() {
        // Not an error case: with no amount there is no invoice to want, and an invoice arriving
        // anyway is a caller bug that must not reach the code the holder shows someone.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, 0L, INVOICE))
    }

    @Test
    fun aNegativeAmountReadsAsAmountlessRatherThanAsAnError() {
        // The seam takes a Long, so a negative amount is representable; it means the same thing
        // as zero here and must never be forwarded to a mint that would trap converting it.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, -1L, INVOICE))
    }

    @Test
    fun aFailedMintFallsBackToTheCodeTheWalletAlreadyHad() {
        // The whole degradation contract: an unreachable server costs the Lightning destination,
        // never the code itself, and never surfaces as an error.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, null))
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, ""))
    }

    @Test
    fun anInvoiceThatWouldBreakTheUriIsDroppedRatherThanEmbedded() {
        // A code carrying a mangled destination is worse than one carrying fewer: the first is
        // unpayable in a way nobody discovers until they try.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "lnbc1&lightning=evil"))
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "lnbc1?x=1"))
    }

    @Test
    fun anInvoiceForADifferentAmountIsNeverCarried() {
        // Get paid states the requested amount right beside this code, so an invoice asking for
        // another figure would make the app promise a number it is not requesting. Same refusal
        // the send path makes when an invoice disagrees with the reviewed amount.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "lnbc60u1p3xyzabc"))
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "lnbc40u1p3xyzabc"))
    }

    @Test
    fun anAmountlessInvoiceIsNeverCarriedForAnAmountAsk() {
        // The payer would choose the figure, which is not what the holder asked for.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "lnbc1p3xyzabc"))
    }

    @Test
    fun anInvoiceWhoseAmountCannotBeReadIsNeverCarried() {
        // Unreadable is a mismatch, not a maybe: carrying it would stake the screen's promise on
        // a figure nothing verified.
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "notaninvoiceatall"))
        assertEquals(ARK_URI, receiveCodeFor(ARK_URI, SATS, "lnxyz50u1p3abc"))
    }

    @Test
    fun theMutinynetPrefixTheAppActuallyShipsIsCarried() {
        // The wallet runs on mutinynet, so production invoices carry signet's `tbs` prefix. The
        // amount check must not quietly reject every real invoice by only understanding mainnet.
        val signet = "lntbs50u1p3xyzabc"
        assertEquals("$ARK_URI&lightning=$signet", receiveCodeFor(ARK_URI, SATS, signet))
    }

    @Test
    fun theCodeNeverCarriesMoreThanOneLightningDestination() {
        val code = receiveCodeFor(ARK_URI, SATS, INVOICE)
        assertEquals(1, code.split("lightning=").size - 1, "exactly one lightning destination")
    }

    @Test
    fun aWalletWithNoUriYetYieldsNoCodeAtAll() {
        // The pre-existing no-code-yet state, unchanged: an empty code renders as a scannable QR
        // that resolves to nothing, so the screen must be told there is nothing rather than given
        // something empty to draw.
        assertEquals("", receiveCodeFor(null, SATS, INVOICE))
        assertEquals("", receiveCodeFor("", SATS, INVOICE))
    }

    @Test
    fun anInvoiceIsReusedForTheSameAmountInsideTheWindow() {
        // Reuse is what stops a holder adjusting an amount from leaving a trail of live invoices
        // the server holds and every maintenance pass tries to claim.
        assertEquals(INVOICE, reusableInvoice(cached(), SATS, BALANCE, MINTED_AT + 1, WINDOW))
    }

    @Test
    fun anInvoiceIsNeverReusedForADifferentAmount() {
        // An invoice names its own amount, so serving one for another figure would ask the payer
        // for money the holder never requested.
        assertNull(reusableInvoice(cached(), SATS + 1, BALANCE, MINTED_AT + 1, WINDOW))
    }

    @Test
    fun anInvoiceIsNeverReusedOnceTheBalanceHasMoved() {
        // The balance may have moved *because this invoice was paid*, and a settled invoice is a
        // destination the payer's wallet refuses. The wallet cannot ask whether one specific
        // receive settled, so any movement is enough to stop reusing it.
        assertNull(reusableInvoice(cached(), SATS, BALANCE + SATS, MINTED_AT + 1, WINDOW))
        // Including downward: a send is not this invoice settling, but re-minting costs one round
        // trip and guessing wrong costs an unpayable code.
        assertNull(reusableInvoice(cached(), SATS, BALANCE - 1, MINTED_AT + 1, WINDOW))
    }

    @Test
    fun anInvoiceIsNotReusedOnceTheWindowHasPassed() {
        assertNull(
            reusableInvoice(cached(), SATS, BALANCE, MINTED_AT + WINDOW, WINDOW),
            "the boundary is exclusive",
        )
        assertTrue(reusableInvoice(cached(), SATS, BALANCE, MINTED_AT + WINDOW - 1, WINDOW) != null)
    }

    @Test
    fun nothingCachedMeansNothingToReuse() {
        assertNull(reusableInvoice(null, SATS, BALANCE, MINTED_AT, WINDOW))
    }

    private fun cached() = CachedInvoice(
        sats = SATS,
        bolt11 = INVOICE,
        mintedAtEpochSeconds = MINTED_AT,
        balanceAtMintSats = BALANCE,
    )

    private companion object {
        const val ARK_URI = "bitcoin:?ark=tark1q2v9lfmk"
        const val INVOICE = "lnbc50u1p3xyzabc"
        const val SATS = 5_000L
        const val MINTED_AT = 1_700_000_000L
        const val BALANCE = 12_000L
        const val WINDOW = 600L
    }
}
