package xyz.lark.app.core

import xyz.lark.app.core.gateway.Bolt11
import xyz.lark.app.core.gateway.parseBolt11
import xyz.lark.app.core.gateway.withLightningInvoice

/**
 * Which code Get paid serves, given what the wallet actually has: the Ark receive URI it has
 * managed to mint, the amount the holder asked for, and the invoice minted for that amount.
 *
 * Pure, and deliberately so. [LarkCore.requestReceiveCode] promises never to return an invoice
 * that cannot be paid and never to fail, which makes every branch below a money-bearing
 * decision — and the core that calls it cannot be tested where it lives. Keeping the branches
 * here is what makes them assertable at all.
 *
 * Every degraded outcome lands on the same honest value: the Ark-only URI, which any Ark wallet
 * can still pay. Only a wallet with no URI at all yields nothing, and that is the pre-existing
 * no-code-yet state rather than something this decision introduces.
 */
internal fun receiveCodeFor(arkUri: String?, requestedSats: Long, mintedInvoice: String?): String =
    when {
        arkUri.isNullOrEmpty() -> ""
        // A non-positive amount is the amountless ask, not an error: no invoice is wanted.
        requestedSats <= 0L || mintedInvoice.isNullOrEmpty() -> arkUri
        // An invoice for the wrong figure is worse than none: the screen states the amount the
        // holder asked for right beside this code, so embedding one that asks for something else
        // would make the app promise a number it is not requesting.
        !asksExactly(mintedInvoice, requestedSats) -> arkUri
        // Drops an invoice that would break the URI: ark-only, but still scannable.
        else -> withLightningInvoice(arkUri, mintedInvoice)
    }

/**
 * Whether [invoice] asks for exactly [sats].
 *
 * The mirror of the send path's amount gate: `ldk-pay` takes an invoice and no amount, so
 * routing refuses one whose figure disagrees with what the review screen showed
 * ([xyz.lark.app.core.gateway.ArkRouteReason.AMOUNT_MISMATCH]). Receiving has the same hazard in
 * the other direction — Get paid renders the requested amount beside the code — and the same
 * answer: read the invoice's own figure and refuse to carry it when they disagree.
 *
 * Anything the reader cannot state an amount for is a mismatch: an amountless invoice (the payer
 * would choose, which is not what was asked), and anything it cannot parse. Network is not
 * checked, because this wallet minted the invoice through its own server and the code is served
 * for whatever chain that server is on.
 */
private fun asksExactly(invoice: String, sats: Long): Boolean =
    (parseBolt11(invoice) as? Bolt11.WithAmount)?.amountSat == sats

/**
 * The cached invoice that can stand in for a fresh mint, or null when one must be minted.
 *
 * Reuse is bounded on the three axes that can make a cached invoice the wrong answer. The amount
 * must match, because an invoice names its own amount and serving one for a different figure would
 * ask the payer for money the holder did not request. The balance must be unchanged, because a
 * balance that moved may have moved *because this invoice was paid*, and a settled invoice is a
 * destination the payer's wallet refuses. And the mint must be recent, because the window exists
 * to stop a holder who adjusts an amount and asks again from leaving a trail of live invoices the
 * server must hold and every maintenance pass must try to claim.
 *
 * The window is not an attempt to track how long the invoice stays payable, which is the server's
 * own business. Being wrong here costs one round trip; being wrong the other way hands out a
 * destination that cannot be paid.
 */
internal fun reusableInvoice(
    cached: CachedInvoice?,
    requestedSats: Long,
    currentBalanceSats: Long,
    nowEpochSeconds: Long,
    reuseWindowSeconds: Long,
): String? = cached
    ?.takeIf { it.sats == requestedSats && it.balanceAtMintSats == currentBalanceSats }
    ?.takeIf { nowEpochSeconds - it.mintedAtEpochSeconds < reuseWindowSeconds }
    ?.bolt11
