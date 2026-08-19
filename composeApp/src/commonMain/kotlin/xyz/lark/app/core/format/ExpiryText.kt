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

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

/**
 * mutinynet's block target, in seconds.
 *
 * Stated as a named constant because every height-to-time conversion has to take spacing
 * explicitly: this chain is 20x faster than Bitcoin's target, so a helper that quietly assumed
 * 10-minute blocks would overstate every wait by that factor.
 */
internal const val MUTINYNET_BLOCK_SECONDS = 30

/**
 * A block count as an approximate wait: `about 4 minutes`, `about 3 hours`, `about 2 days`.
 *
 * Distinct from [blockExpiryLabel] in two ways that matter. It carries no height — a user waiting
 * on an exit is asking "how long", not "until which block" — and it resolves minutes, because an
 * exit delta on a 30-second chain is a bit over an hour, and rounding that to the nearest hour
 * throws away most of the answer.
 *
 * Null [blocks] returns [EXPIRY_PLACEHOLDER]. That is the honest rendering of a wait this build
 * cannot compute, and callers must not substitute a default in its place.
 */
internal fun approxDurationLabel(blocks: Long?, secondsPerBlock: Int): String = when {
    blocks == null -> EXPIRY_PLACEHOLDER
    blocks <= 0 -> "any moment now"
    else -> "about " + spelled(blocks * secondsPerBlock)
}

/**
 * How long something has been going on: `4 minutes`, `3 hours`, `2 days`.
 *
 * The counterpart to [approxDurationLabel] and deliberately unhedged — no "about". An elapsed time
 * is measured rather than predicted, so it can be stated flatly. Used where a prediction would be
 * a lie: a stalled exit has no knowable end, and the honest number is how long it has been stuck.
 */
internal fun elapsedLabel(millis: Long): String =
    if (millis < SECONDS_PER_MINUTE * MILLIS_PER_SECOND) "less than a minute"
    else spelled(millis / MILLIS_PER_SECOND)

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The unit choice, split out so the label above stays a single expression.
 *
 * Internal rather than private because [roundCountdownLabel] needs the same ladder for waits of a
 * minute or more; duplicating it there is how two countdowns in one screen end up phrased
 * differently.
 */
internal fun spelled(seconds: Long): String = when {
    seconds < SECONDS_PER_HOUR -> counted(maxOf(1L, seconds / SECONDS_PER_MINUTE), "minute")
    seconds < SECONDS_PER_DAY -> counted(maxOf(1L, seconds / SECONDS_PER_HOUR), "hour")
    else -> counted(seconds / SECONDS_PER_DAY, "day")
}

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
