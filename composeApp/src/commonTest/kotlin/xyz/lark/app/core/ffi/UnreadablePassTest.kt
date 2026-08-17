package xyz.lark.app.core.ffi

import xyz.lark.app.core.EXIT_STALL_THRESHOLD
import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.ExitStallReason
import xyz.lark.app.core.ExitStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val MID_EXIT = ExitStatus(
    stage = ExitStage.WAITING_OUT_DELAY,
    vtxoCount = 3,
    inFlightSats = 250_000L,
)

/**
 * What the app reports when it cannot read a progress pass at all.
 *
 * Found in the field, not in a test: an exit whose every pass failed counted failures forever and
 * never said so, because the status carrying `stalled` was discarded in favour of the last good
 * one. The wallet sat on a stage name with no explanation and no way for the holder to learn there
 * was one.
 */
class UnreadablePassTest {

    @Test
    fun an_unreadable_pass_below_the_threshold_changes_nothing() {
        val after = MID_EXIT.afterUnreadablePass(EXIT_STALL_THRESHOLD - 1)
        assertFalse(after.stalled)
        assertEquals(MID_EXIT.stage, after.stage)
    }

    @Test
    fun enough_unreadable_passes_raise_the_stall() {
        val after = MID_EXIT.afterUnreadablePass(EXIT_STALL_THRESHOLD)
        assertTrue(after.stalled, "a pass that cannot be read is still a pass that did not advance")
        assertEquals(ExitStallReason.UNKNOWN, after.reason)
    }

    /** Nothing about the exit changed — only our ability to ask about it. */
    @Test
    fun an_unreadable_pass_preserves_what_was_last_known() {
        val after = MID_EXIT.afterUnreadablePass(EXIT_STALL_THRESHOLD)
        assertEquals(MID_EXIT.stage, after.stage)
        assertEquals(MID_EXIT.inFlightSats, after.inFlightSats)
        assertEquals(MID_EXIT.vtxoCount, after.vtxoCount)
    }

    /**
     * A category the engine did report survives. Otherwise a single unreadable pass after three
     * readable ones would downgrade a known, actionable cause to "something went wrong" — and the
     * one actionable category is the one that comes with a button.
     */
    @Test
    fun a_known_category_is_not_overwritten_by_an_unreadable_pass() {
        val starved = MID_EXIT.copy(reason = ExitStallReason.NEEDS_ONCHAIN_FUNDS)
        val after = starved.afterUnreadablePass(EXIT_STALL_THRESHOLD)
        assertEquals(ExitStallReason.NEEDS_ONCHAIN_FUNDS, after.reason)
        assertTrue(after.stalled)
    }
}
