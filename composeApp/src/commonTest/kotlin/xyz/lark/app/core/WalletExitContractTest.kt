package xyz.lark.app.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The [WalletExit] contract, exercised against [FakeWalletExit].
 *
 * Every implementation has to satisfy these; the fake is the one that can be driven to the end in
 * a test. The properties here are the ones the app's exiting mode is built on, so a core that
 * broke any of them would strand a wallet mid-exit.
 */
class WalletExitContractTest {

    @Test
    fun a_fresh_wallet_is_not_exiting() = runTest {
        val exit = FakeWalletExit()
        assertEquals(ExitStage.NONE, exit.exitStatus.stage)
        assertFalse(exit.exitStatus.isExiting)
    }

    @Test
    fun progressing_without_an_exit_does_nothing() = runTest {
        val exit = FakeWalletExit()
        assertEquals(ExitStatus.NOT_EXITING, exit.progressExit())
    }

    @Test
    fun a_started_exit_walks_every_stage_in_order_to_claimed() = runTest {
        val exit = FakeWalletExit()
        exit.startExit()

        val seen = mutableListOf(exit.exitStatus.stage)
        repeat(FakeWalletExit.DEFAULT_SCRIPT.size) { seen += exit.progressExit().stage }

        assertEquals(FakeWalletExit.DEFAULT_SCRIPT, seen.distinct())
        assertEquals(ExitStage.CLAIMED, exit.exitStatus.stage)
    }

    @Test
    fun claimed_is_the_only_way_out_of_the_exiting_state() = runTest {
        val exit = FakeWalletExit()
        exit.startExit()
        while (exit.exitStatus.stage != ExitStage.CLAIMED) {
            assertTrue(exit.exitStatus.isExiting, "still exiting at ${exit.exitStatus.stage}")
            exit.progressExit()
        }
        assertFalse(exit.exitStatus.isExiting)
    }

    @Test
    fun advancing_past_the_end_is_a_no_op() = runTest {
        val exit = FakeWalletExit(script = listOf(ExitStage.CLAIMED), startedAlready = true)
        assertEquals(ExitStage.CLAIMED, exit.progressExit().stage)
        assertEquals(ExitStage.CLAIMED, exit.progressExit().stage)
    }

    @Test
    fun starting_twice_does_not_restart_the_exit() = runTest {
        val exit = FakeWalletExit()
        exit.startExit()
        exit.progressExit()
        val afterOnePass = exit.exitStatus.stage

        exit.startExit()

        assertEquals(afterOnePass, exit.exitStatus.stage)
    }

    @Test
    fun a_stall_raises_only_at_the_threshold() = runTest {
        val exit = FakeWalletExit(failure = FakeWalletExit.FakeExitFailure(passes = EXIT_STALL_THRESHOLD))
        exit.startExit()

        repeat(EXIT_STALL_THRESHOLD - 1) {
            assertFalse(exit.progressExit().stalled, "one blip is not a stall")
        }

        assertTrue(exit.progressExit().stalled)
    }

    @Test
    fun a_stall_clears_when_progress_resumes_without_leaving_the_mode() = runTest {
        val exit = FakeWalletExit(failure = FakeWalletExit.FakeExitFailure(passes = EXIT_STALL_THRESHOLD))
        exit.startExit()
        repeat(EXIT_STALL_THRESHOLD) { exit.progressExit() }
        assertTrue(exit.exitStatus.stalled)

        val recovered = exit.progressExit()

        assertFalse(recovered.stalled)
        assertTrue(recovered.isExiting, "recovering from a stall must not end the exit")
    }

    @Test
    fun a_stall_does_not_advance_the_stage() = runTest {
        val exit = FakeWalletExit(failure = FakeWalletExit.FakeExitFailure(passes = 2))
        exit.startExit()
        val before = exit.exitStatus.stage

        exit.progressExit()

        assertEquals(before, exit.exitStatus.stage)
    }

    @Test
    fun an_unsupported_stage_still_counts_as_exiting() = runTest {
        // A channel VTXO would park here. The wallet has not left the Ark, so it must not read as
        // free — the mode holds and the app says it cannot advance.
        val status = ExitStatus(stage = ExitStage.UNSUPPORTED)
        assertTrue(status.isExiting)
    }

    @Test
    fun claimed_reports_nothing_left_in_flight() = runTest {
        val exit = FakeWalletExit()
        exit.startExit()
        while (exit.exitStatus.stage != ExitStage.CLAIMED) exit.progressExit()

        assertEquals(0L, exit.exitStatus.inFlightSats)
        assertEquals(exit.exitStatus.vtxoCount, exit.exitStatus.claimedCount)
    }
}
