package xyz.lark.app.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Ark round countdown the Advanced screen's "Next round" row reads.
 *
 * Two things make this worth its own tests. The input is an absolute server timestamp rather than a
 * remaining duration, so the subtraction against the local clock lives here and can be wrong on its
 * own; and rounds land on a seconds scale, which is the one scale every other countdown helper in
 * this package deliberately rounds away.
 */
class RoundTextTest {

    private val now = 1_760_000_000_000L // any fixed wall clock; only the difference matters

    private fun at(secondsFromNow: Long) = now / 1_000L + secondsFromNow

    @Test
    fun an_unknown_round_time_renders_as_unknown_not_as_imminent() {
        assertEquals(EXPIRY_PLACEHOLDER, roundCountdownLabel(null, now))
    }

    /**
     * The whole point of the seconds resolution: a round is a sub-minute wait most of the time, and
     * "about 1 minute" would be the wrong answer to a question the server answers exactly.
     */
    @Test
    fun a_sub_minute_wait_reads_in_seconds() {
        assertEquals("in 41 seconds", roundCountdownLabel(at(41), now))
    }

    @Test
    fun one_second_is_singular() {
        assertEquals("in 1 second", roundCountdownLabel(at(1), now))
    }

    /**
     * A round time that has already passed is the ordinary case between the round starting and the
     * next poll refreshing the timestamp. It must not render as a negative countdown.
     */
    @Test
    fun a_passed_round_time_reads_as_imminent_rather_than_negative() {
        assertEquals("any moment now", roundCountdownLabel(at(-7), now))
        assertEquals("any moment now", roundCountdownLabel(at(0), now))
    }

    @Test
    fun a_wait_of_a_minute_or_more_switches_to_the_coarser_voice() {
        assertEquals("in 1 minute", roundCountdownLabel(at(60), now))
        assertEquals("in 2 minutes", roundCountdownLabel(at(150), now))
    }

    /** A server configured with a long round interval still reads sensibly. */
    @Test
    fun a_long_round_interval_reads_in_hours() {
        assertEquals("in 2 hours", roundCountdownLabel(at(7_200), now))
    }
}
