package xyz.lark.app.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The block-count-to-wait helper the exit surfaces read.
 *
 * The spacing argument is the whole reason this is tested: mutinynet's 30-second blocks make every
 * one of these answers 20x shorter than the same count on Bitcoin, and a helper that quietly
 * assumed the slower target would put a day on screen where an hour is true.
 */
class ApproxDurationTest {

    @Test
    fun an_unknown_block_count_renders_as_unknown_not_as_zero() {
        assertEquals(EXPIRY_PLACEHOLDER, approxDurationLabel(null, MUTINYNET_BLOCK_SECONDS))
    }

    /** 144 blocks — the exit delta — is a bit over an hour here, not the better part of a day. */
    @Test
    fun the_exit_delta_reads_in_minutes_at_mutinynet_spacing() {
        assertEquals("about 1 hour", approxDurationLabel(144, MUTINYNET_BLOCK_SECONDS))
    }

    @Test
    fun the_same_count_at_bitcoin_spacing_is_a_different_answer() {
        // Guards the parameterisation itself: if spacing were ignored, these two would agree.
        assertEquals("about 1 day", approxDurationLabel(144, 600))
    }

    @Test
    fun a_short_wait_resolves_to_minutes_rather_than_rounding_to_an_hour() {
        assertEquals("about 4 minutes", approxDurationLabel(8, MUTINYNET_BLOCK_SECONDS))
    }

    /** Never "about 0 minutes": under one unit of anything still reads as a real wait. */
    @Test
    fun a_sub_minute_wait_floors_at_one_minute() {
        assertEquals("about 1 minute", approxDurationLabel(1, MUTINYNET_BLOCK_SECONDS))
    }

    @Test
    fun an_elapsed_wait_says_so_rather_than_counting_backwards() {
        assertEquals("any moment now", approxDurationLabel(0, MUTINYNET_BLOCK_SECONDS))
        assertEquals("any moment now", approxDurationLabel(-5, MUTINYNET_BLOCK_SECONDS))
    }

    @Test
    fun a_long_wait_reads_in_days() {
        assertEquals("about 2 days", approxDurationLabel(5_760, MUTINYNET_BLOCK_SECONDS))
    }
}
