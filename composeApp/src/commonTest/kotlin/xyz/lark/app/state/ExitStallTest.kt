package xyz.lark.app.state

import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.ExitStallReason
import xyz.lark.app.core.ExitStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 10_000_000L

private fun stalled(reason: ExitStallReason?) = ExitStatus(
    stage = ExitStage.WAITING_OUT_DELAY,
    vtxoCount = 3,
    inFlightSats = 250_000L,
    stalled = true,
    reason = reason,
)

/**
 * What a stalled exit says, per category.
 *
 * The rule these protect is narrow and easy to erode: exactly one stall is something the holder
 * can act on, and every other one must say so plainly rather than implying they did something
 * wrong. A stall that offered a remedy which cannot work would be worse than one offering none.
 */
class ExitStallTest {

    @Test
    fun every_category_has_its_own_headline() {
        val headlines = ExitStallReason.entries.map { exitHeadline(stalled(it), tipHeight = 900_000L) }
        assertEquals(ExitStallReason.entries.size, headlines.distinct().size)
        assertTrue(headlines.none { it.isBlank() })
    }

    /** A stall outranks the countdown: promising an end while nothing moves is the worst lie here. */
    @Test
    fun a_stall_replaces_the_countdown_even_when_a_height_is_known() {
        val status = stalled(ExitStallReason.CHAIN_UNREACHABLE).copy(claimableAtHeight = 900_144L)
        assertEquals("Can't reach the network", exitHeadline(status, tipHeight = 900_000L))
    }

    @Test
    fun a_funding_shortfall_explains_what_to_do() {
        val detail = exitStallDetail(ExitStallReason.NEEDS_ONCHAIN_FUNDS, stalledSince = null, nowMillis = NOW)
        assertTrue(detail.contains("on-chain balance"), "got: $detail")
        assertTrue(detail.contains("picks up on its own"), "got: $detail")
    }

    /**
     * Uneconomic is deliberately not the funding category: depositing cannot clear it, so its copy
     * must not suggest adding funds would help.
     */
    @Test
    fun an_uneconomic_exit_does_not_suggest_depositing() {
        val detail = exitStallDetail(ExitStallReason.NOT_ECONOMIC, stalledSince = null, nowMillis = NOW)
        assertFalse(detail.contains("Add some"), "got: $detail")
        assertTrue(detail.contains("fee"), "got: $detail")
    }

    @Test
    fun a_stall_says_how_long_it_has_lasted() {
        val detail = exitStallDetail(
            ExitStallReason.CHAIN_UNREACHABLE,
            stalledSince = NOW - 3 * 60 * 60 * 1_000L,
            nowMillis = NOW,
        )
        assertTrue(detail.startsWith("Stuck for 3 hours."), "got: $detail")
    }

    /** Elapsed time is omitted rather than guessed when the clock never started. */
    @Test
    fun a_stall_with_no_recorded_start_omits_the_elapsed_line() {
        val detail = exitStallDetail(ExitStallReason.CHAIN_UNREACHABLE, stalledSince = null, nowMillis = NOW)
        assertFalse(detail.contains("Stuck for"), "got: $detail")
    }

    /** An engine this build predates still gets a readable line, not a blank or an enum name. */
    @Test
    fun an_uncategorised_stall_still_reads_as_english() {
        assertEquals("Something isn't working", exitHeadline(stalled(null), tipHeight = 900_000L))
        val detail = exitStallDetail(null, stalledSince = null, nowMillis = NOW)
        assertTrue(detail.contains("funds are safe"), "got: $detail")
    }
}
