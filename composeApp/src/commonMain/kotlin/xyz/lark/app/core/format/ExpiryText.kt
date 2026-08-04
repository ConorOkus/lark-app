package xyz.lark.app.core.format

/**
 * How an expiry height reads to a user: `block 918,402 · in 27 days`.
 *
 * Shared, and **parameterised by block spacing**, because the same block count means wildly
 * different things per network: 4,320 blocks is 30 days at Bitcoin's 10-minute target and 36 hours
 * on mutinynet's 30-second one. A countdown that assumes 10-minute blocks on a fast chain overstates
 * the remaining time by 20x — which, for a VTXO expiry the user is being warned about, is the
 * difference between a useful warning and a dangerous one.
 *
 * Heights are the input rather than dates because that is what the protocol works in; the conversion
 * to human time happens here, once, with the spacing stated explicitly.
 */

/** Em-dash for an expiry that is not yet knowable; never a fabricated countdown. */
internal const val EXPIRY_PLACEHOLDER = "—"

private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

/**
 * One expiry height in the block-countdown voice, or [EXPIRY_PLACEHOLDER] while either the height
 * or the tip is still unknown.
 *
 * A null [expiryHeight] means "nothing to expire" and a non-positive [tipHeight] means "no tip
 * read yet" — both are unknowns rather than zeros, and neither may render as a number.
 */
internal fun blockExpiryLabel(
    expiryHeight: Long?,
    tipHeight: Long,
    secondsPerBlock: Int,
): String = if (expiryHeight == null || tipHeight <= 0) {
    EXPIRY_PLACEHOLDER
} else {
    "block ${MoneyFormat.grouped(expiryHeight)} · " +
        expiryCountdown(blocks = expiryHeight - tipHeight, secondsPerBlock = secondsPerBlock)
}

/**
 * The countdown half: `expired`, `in 5 hours`, `in 27 days`.
 *
 * Rounds down and floors at one unit, so a countdown never reads `in 0 hours` — the honest reading
 * of "less than an hour left" is "in 1 hour", not "expired".
 */
private fun expiryCountdown(blocks: Long, secondsPerBlock: Int): String {
    if (blocks <= 0) return "expired"
    val seconds = blocks * secondsPerBlock
    return if (seconds < SECONDS_PER_DAY) {
        "in " + counted(maxOf(1L, seconds / SECONDS_PER_HOUR), "hour")
    } else {
        "in " + counted(seconds / SECONDS_PER_DAY, "day")
    }
}
