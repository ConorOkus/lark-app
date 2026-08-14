package xyz.lark.app.core.ffi

import xyz.lark.app.core.EXIT_STALL_THRESHOLD
import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.ExitStallReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun reported(
    stage: FfiExitStage = FfiExitStage.PROCESSING,
    vtxoCount: Int = 3,
    claimedCount: Int = 0,
    totalSat: Long = 250_000L,
    errors: List<String> = emptyList(),
    stallCategory: FfiExitStallCategory? = null,
) = FfiExitStatus(
    stage = stage,
    vtxoCount = vtxoCount,
    claimedCount = claimedCount,
    totalSat = totalSat,
    errors = errors,
    stallCategory = stallCategory,
)

/** Mapping the crate's exit report into the seam's, including the app's stall policy. */
class FfiExitMappersTest {

    @Test
    fun an_unreadable_pass_maps_to_nothing_rather_than_to_not_exiting() {
        // The transition that must never happen by accident: one dropped call would otherwise
        // drop the wallet out of the exiting state while its money is still in flight.
        assertNull(null.toExitStatus(consecutiveFailures = 1))
    }

    @Test
    fun every_crate_stage_has_a_seam_stage() {
        val mapped = FfiExitStage.entries.map { it.toExitStage() }
        assertEquals(FfiExitStage.entries.size, mapped.size)
        assertEquals(mapped.size, mapped.distinct().size, "two crate stages collapsed into one")
    }

    @Test
    fun the_stages_map_in_order() {
        assertEquals(ExitStage.NONE, FfiExitStage.NONE.toExitStage())
        assertEquals(ExitStage.STARTING, FfiExitStage.START.toExitStage())
        assertEquals(ExitStage.BROADCASTING, FfiExitStage.PROCESSING.toExitStage())
        assertEquals(ExitStage.WAITING_OUT_DELAY, FfiExitStage.AWAITING_DELTA.toExitStage())
        assertEquals(ExitStage.CLAIMABLE, FfiExitStage.CLAIMABLE.toExitStage())
        assertEquals(ExitStage.CLAIMING, FfiExitStage.CLAIM_IN_PROGRESS.toExitStage())
        assertEquals(ExitStage.CLAIMED, FfiExitStage.CLAIMED.toExitStage())
        assertEquals(ExitStage.UNSUPPORTED, FfiExitStage.UNSUPPORTED.toExitStage())
    }

    @Test
    fun a_claimed_exit_reports_nothing_left_in_flight() {
        val status = assertNotNull(
            reported(stage = FfiExitStage.CLAIMED, claimedCount = 3, totalSat = 250_000L)
                .toExitStatus(consecutiveFailures = 0),
        )
        assertEquals(0L, status.inFlightSats)
        assertFalse(status.isExiting)
    }

    @Test
    fun an_in_flight_exit_carries_its_amount() {
        val status = assertNotNull(reported().toExitStatus(consecutiveFailures = 0))
        assertEquals(250_000L, status.inFlightSats)
        assertTrue(status.isExiting)
    }

    @Test
    fun a_stall_raises_only_at_the_threshold() {
        val below = assertNotNull(
            reported(errors = listOf("boom")).toExitStatus(EXIT_STALL_THRESHOLD - 1),
        )
        assertFalse(below.stalled)

        val at = assertNotNull(reported(errors = listOf("boom")).toExitStatus(EXIT_STALL_THRESHOLD))
        assertTrue(at.stalled)
    }

    @Test
    fun the_category_becomes_the_reason() {
        val status = assertNotNull(
            reported(stallCategory = FfiExitStallCategory.CHAIN_UNREACHABLE)
                .toExitStatus(consecutiveFailures = 1),
        )
        assertEquals(ExitStallReason.CHAIN_UNREACHABLE, status.reason)
    }

    /**
     * The engine's own wording carries VTXO ids and internals, so it must not be reachable from a
     * status the UI renders. Errors present, category absent, reason still null is the proof.
     */
    @Test
    fun the_engines_message_never_becomes_the_reason() {
        val status = assertNotNull(
            reported(errors = listOf("f00dbabe…: Database Store Failure: …"))
                .toExitStatus(consecutiveFailures = 1),
        )
        assertNull(status.reason)
    }

    @Test
    fun a_clean_pass_carries_no_reason() {
        assertNull(assertNotNull(reported().toExitStatus(consecutiveFailures = 0)).reason)
    }

    @Test
    fun every_crate_category_has_a_seam_reason() {
        val mapped = FfiExitStallCategory.entries.map { it.toExitStallReason() }
        assertEquals(FfiExitStallCategory.entries.size, mapped.distinct().size)
    }

    /** Only the fee-starved category comes with an action; the rest are wait-and-retry. */
    @Test
    fun only_a_funding_shortfall_is_clearable_by_the_holder() {
        val clearable = ExitStallReason.entries.filter { it.isClearableByDeposit }
        assertEquals(listOf(ExitStallReason.NEEDS_ONCHAIN_FUNDS), clearable)
    }

    @Test
    fun an_unsupported_stage_still_counts_as_exiting() {
        val status = assertNotNull(
            reported(stage = FfiExitStage.UNSUPPORTED).toExitStatus(consecutiveFailures = 0),
        )
        assertTrue(status.isExiting, "a parked channel exit has not left the Ark")
    }
}
