package xyz.lark.app.state

import xyz.lark.app.core.format.MoneyFormat
import xyz.lark.app.core.gateway.SendInput

/**
 * The line under the send input, as a pure function of what was typed.
 *
 * Split out for the same reason the exit copy is: it is wording rather than behaviour, and it is
 * where the app either tells the truth about a destination or quietly misleads. Pure so the
 * awkward cases — an unrecognised paste, an empty clipboard, an on-chain address that used to be
 * offered as an ordinary payment — are testable without a wallet.
 */
internal fun sendSummary(
    typed: String,
    pasteFailed: Boolean,
    input: SendInput,
    invoiceAmount: String?,
): String = when {
    typed.isBlank() && pasteFailed ->
        "Nothing came through from the clipboard. Long-press the field to paste, or type it in."
    typed.isBlank() -> "A name, an invoice, or a bitcoin address — LARK works out the rest."
    !input.isResolved -> "That doesn\u2019t look like an invoice or address LARK can pay."
    // Said before the amount screen, because it changes what the holder is agreeing to: a
    // different balance, a miner fee, and no way to take it back once it is out.
    input.isOnchain -> "A bitcoin address. This goes on-chain \u2014 slower, and it pays a miner fee."
    invoiceAmount != null -> "Invoice for $invoiceAmount."
    else -> "Ready to pay ${input.display}."
}

/**
 * What the deposit screen says this money is for.
 *
 * Two genuinely different answers behind one address. Ordinarily a deposit is on its way to being
 * spendable, and the board minimum is the number that matters — under it, the wait will not end.
 *
 * During an exit it is neither. The funds pay the exit's miner fees, and the funding intent is
 * deliberately held down so they are not boarded: sweeping them into the Ark is the undo the exit
 * exists to prevent, and it would take the fees with it. The board minimum is not the threshold
 * either — what matters is the exit's own cost, which the engine will not tell us. So this says no
 * number at all rather than the wrong one, which is the same rule the exit screen's fee row follows.
 */
internal fun depositExplainer(exiting: Boolean, minBoardSats: Long): String = if (exiting) {
    "This pays the miner fees your exit needs to finish. It stays on-chain — LARK will not move " +
        "it into your spendable balance while you are leaving."
} else {
    "Send at least ${MoneyFormat.btc(minBoardSats)}. It takes a few minutes before you can spend it."
}
