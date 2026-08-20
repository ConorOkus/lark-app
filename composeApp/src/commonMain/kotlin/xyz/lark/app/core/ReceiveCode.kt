package xyz.lark.app.core

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
        // Drops an invoice that would break the URI: ark-only, but still scannable.
        else -> withLightningInvoice(arkUri, mintedInvoice)
    }

/**
 * The cached invoice that can stand in for a fresh mint, or null when one must be minted.
 *
 * Reuse is bounded on both axes that can make a cached invoice the wrong answer. The amount must
 * match, because an invoice names its own amount and serving one for a different figure would ask
 * the payer for money the holder did not request. And the mint must be recent, because the window
 * exists to stop a holder who adjusts an amount and asks again from leaving a trail of live
 * invoices the server must hold and every maintenance pass must try to claim — not to track how
 * long the invoice stays payable, which is the server's own business.
 */
internal fun reusableInvoice(
    cached: CachedInvoice?,
    requestedSats: Long,
    nowEpochSeconds: Long,
    reuseWindowSeconds: Long,
): String? = cached
    ?.takeIf { it.sats == requestedSats }
    ?.takeIf { nowEpochSeconds - it.mintedAtEpochSeconds < reuseWindowSeconds }
    ?.bolt11
