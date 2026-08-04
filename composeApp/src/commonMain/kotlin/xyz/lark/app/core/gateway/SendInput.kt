package xyz.lark.app.core.gateway

import xyz.lark.app.core.format.displayName

/**
 * What the app can tell about a destination the user pasted or typed, before any send happens.
 *
 * Lives beside the two functions it composes ([resolveSendDestination] and [parseBolt11]) so the
 * knowledge of "what counts as a payable destination" stays in one place: the send path and the
 * input screen must agree, or the screen would offer Continue on something the core then refuses.
 */
internal data class SendInput(
    /** What would actually be paid, or null when nothing payable was recognized. */
    val destination: String?,
    /**
     * The invoice's own amount, when it carries one. Authoritative: an amount-bearing invoice
     * pays that figure regardless of what the keypad says, so the flow must not offer to change it.
     */
    val amountSat: Long?,
    /** Short human-facing rendering — a lightning address as-is, a long invoice as head…tail. */
    val display: String,
) {
    val isResolved: Boolean get() = destination != null
}

/**
 * Classifies [raw] as a send destination. Pure and cheap, so the render path can call it rather
 * than duplicating resolution state.
 *
 * [resolveSendDestination] alone is not enough here. It is deliberately a pass-through — barkd
 * routes raw destination strings itself, so it hands back any non-BIP-321 text unchanged, which
 * would make "hello there" look payable. Its job is unwrapping a BIP-321 URI; recognizing a
 * destination is this function's.
 */
internal fun classifySendInput(raw: String): SendInput {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return SendInput(destination = null, amountSat = null, display = "")

    val candidate = resolveSendDestination(trimmed)?.takeIf(::isPayableDestination)
    val amountSat = candidate?.let { (parseBolt11(it) as? Bolt11.WithAmount)?.amountSat }
    return SendInput(
        destination = candidate,
        amountSat = amountSat,
        display = displayName(candidate ?: trimmed),
    )
}

/**
 * Whether this looks like something the wallet could pay.
 *
 * Intentionally shape-level, and intentionally narrower than what barkd would accept: the screen
 * only needs to avoid *offering* to pay text nobody could route. A form we do not recognize is
 * refused here rather than sent and rejected downstream — the bech32 branch keeps that generous
 * enough to cover Ark addresses and BOLT12 offers as well as invoices.
 */
private fun isPayableDestination(destination: String): Boolean =
    parseBolt11(destination) != Bolt11.Unrecognized ||
        LIGHTNING_ADDRESS.matches(destination) ||
        LNURL.matches(destination) ||
        isBech32Shaped(destination)

private val LIGHTNING_ADDRESS = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
private val LNURL = Regex("""(?i)lnurl[0-9a-z]+""")
