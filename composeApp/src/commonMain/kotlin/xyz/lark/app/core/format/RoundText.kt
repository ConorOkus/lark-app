package xyz.lark.app.core.format

/**
 * How the Ark server's next round reads to a user: `in 41 seconds`.
 *
 * Separate from [blockExpiryLabel] and [approxDurationLabel] because a round is not a chain event.
 * Those two convert block counts at a stated block spacing; the server answers this one with an
 * absolute wall-clock timestamp, so the arithmetic here is against the device clock and the honest
 * resolution is seconds — the round interval on a live stack is around a minute, and rounding that
 * to the nearest minute would throw away the whole answer.
 */

private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * The countdown to the next round, or [EXPIRY_PLACEHOLDER] while the schedule is unknown.
 *
 * A null [nextRoundEpochSeconds] is the offline and never-fetched case: the schedule comes from the
 * Ark server, so a wallet that cannot reach one has no answer, and an em-dash is that answer rather
 * than a fabricated countdown.
 *
 * A timestamp already in the past is ordinary rather than exceptional — the round starts, and the
 * cached timestamp stays stale until the next poll replaces it — so it reads as imminent instead of
 * as a negative countdown.
 */
internal fun roundCountdownLabel(nextRoundEpochSeconds: Long?, nowMillis: Long): String {
    if (nextRoundEpochSeconds == null) return EXPIRY_PLACEHOLDER
    val seconds = nextRoundEpochSeconds - nowMillis / MILLIS_PER_SECOND
    return when {
        seconds <= 0L -> "any moment now"
        seconds < SECONDS_PER_MINUTE -> "in " + counted(seconds, "second")
        // Reuses the shared unit ladder above a minute, so a server configured with a long round
        // interval reads in the same voice as every other wait in the app.
        else -> "in " + spelled(seconds)
    }
}
