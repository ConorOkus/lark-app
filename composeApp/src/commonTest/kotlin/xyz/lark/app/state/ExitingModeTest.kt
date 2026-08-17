package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.EXIT_STALL_THRESHOLD
import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.ExitStallReason
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.FakeWalletExit
import xyz.lark.app.core.OnchainFunding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The machine's exit cadence, mirrored rather than imported — the timing is the deliberate part. */
private const val EXIT_TICK_MILLIS = 30_000L

/** Virtual millis covering [passes] progress passes, the first firing at t=0. */
private fun passes(passes: Int) = (passes - 1) * EXIT_TICK_MILLIS + 1

/** Minimal funding capability: enough to prove the exit stands it down and never re-arms it. */
private class SpyFunding(
    private var armedAt: Long? = null,
) : OnchainFunding {
    var boardCalls = 0
        private set
    var armCalls = 0
        private set
    var disarmCalls = 0
        private set

    override val confirmedSats: Long = 500_000L
    override val pendingSats: Long = 0L
    override val minBoardSats: Long = 10_000L
    override val fundingArmedAtMillis: Long? get() = armedAt

    override fun armFunding(atMillis: Long) {
        armCalls++
        armedAt = atMillis
    }

    override fun disarmFunding() {
        disarmCalls++
        armedAt = null
    }

    override suspend fun syncOnchain() = Unit

    override suspend fun boardAll(): Boolean {
        boardCalls++
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.machine(
    exit: FakeWalletExit,
    funding: OnchainFunding? = null,
): AppStateMachine = AppStateMachine(
    core = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO),
    demo = null,
    scope = backgroundScope,
    nowMillis = { testScheduler.currentTime },
    funding = funding,
    walletExit = exit,
    wallClockMillis = { testScheduler.currentTime },
)

/**
 * The wallet-level exiting state: what holds it, what it forbids, and the one way out.
 *
 * These are the properties an exit's safety rests on. A wallet that boarded during an exit would
 * undo it; one that forgot an exit on relaunch would strand a broadcast that still needs claiming.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExitingModeTest {

    // --- Entering and leaving ---

    @Test
    fun a_wallet_that_is_not_exiting_shows_no_exiting_surface() = runTest {
        val m = machine(FakeWalletExit())
        runCurrent()
        assertNull(m.model.value.exiting)
    }

    @Test
    fun starting_an_exit_puts_the_wallet_into_the_exiting_state() = runTest {
        val m = machine(FakeWalletExit())
        m.startExit()
        runCurrent()

        assertNotNull(m.model.value.exiting)
    }

    @Test
    fun the_exit_advances_on_its_own_without_the_user_doing_anything() = runTest {
        val exit = FakeWalletExit()
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(3))
        runCurrent()

        assertTrue(exit.passes >= 3, "watcher made ${exit.passes} passes")
        assertNotNull(m.model.value.exiting)
    }

    @Test
    fun claiming_every_vtxo_is_what_ends_the_exiting_state() = runTest {
        val exit = FakeWalletExit()
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(FakeWalletExit.DEFAULT_SCRIPT.size + 1))
        runCurrent()

        assertEquals(ExitStage.CLAIMED, exit.exitStatus.stage)
        assertNull(m.model.value.exiting, "a claimed exit leaves the mode")
    }

    /**
     * Found in the field: this passed against a core that could not resume at all.
     *
     * The machine decided from the capability's status property at construction, and that property
     * is cold until a suspending read fills it — so a reopened exit reported "not exiting", no
     * watcher started, the exiting surface never appeared, and send and receive were offered on a
     * wallet mid-exit. The fake was eager where the real core is lazy, so the test agreed with the
     * bug. [FakeWalletExit] is cold now, which is what makes this test mean something.
     */
    @Test
    fun an_exit_already_in_flight_resumes_at_launch_with_no_user_action() = runTest {
        // The app does not run while it is closed, so a wallet reopened mid-exit is carrying a
        // broadcast that still needs claiming. Nothing but this would notice.
        val exit = FakeWalletExit(startedAlready = true)
        val m = machine(exit)
        runCurrent()

        assertNotNull(m.model.value.exiting)

        advanceTimeBy(passes(2))
        runCurrent()
        assertTrue(exit.passes >= 2, "resumed watcher made ${exit.passes} passes")
    }

    /**
     * The guards have to come back with the exit. Covers R7 on the resume path specifically: a
     * reopened exit that never re-entered the mode would offer sends out of a wallet with no
     * spendable VTXOs left.
     */
    @Test
    fun a_resumed_exit_restores_the_guards_it_was_holding() = runTest {
        val funding = SpyFunding(armedAt = 1L)
        val m = machine(FakeWalletExit(startedAlready = true), funding)
        runCurrent()
        advanceTimeBy(1)
        runCurrent()

        assertNotNull(m.model.value.exiting, "a reopened exit is still an exit")
        assertEquals(1, funding.disarmCalls, "the funding intent stands down again on resume")
        assertNull(funding.fundingArmedAtMillis)
    }

    // --- Guards ---

    @Test
    fun starting_an_exit_stands_the_funding_intent_down() = runTest {
        val funding = SpyFunding(armedAt = 1L)
        val m = machine(FakeWalletExit(), funding)
        m.startExit()
        runCurrent()

        assertEquals(1, funding.disarmCalls)
        assertNull(funding.fundingArmedAtMillis)
    }

    @Test
    fun opening_the_deposit_screen_mid_exit_does_not_arm_funding() = runTest {
        // The undo this exists to prevent: exit proceeds land in the same on-chain wallet the
        // funding watcher reads, so arming here would board them straight back in.
        val funding = SpyFunding()
        val m = machine(FakeWalletExit(), funding)
        m.startExit()
        runCurrent()

        m.goDeposit()
        advanceTimeBy(passes(3))
        runCurrent()

        assertEquals(0, funding.armCalls)
        assertEquals(0, funding.boardCalls, "nothing may be boarded during an exit")
    }

    /**
     * The one stall that comes with something to do. Covers AE9a.
     *
     * The exit cannot even leave its first state without on-chain funds to pay its fees, so this
     * is the stall a fully-boarded wallet meets first — and the only one where telling the holder
     * to wait would be false.
     */
    @Test
    fun a_funding_shortfall_offers_the_deposit_route() = runTest {
        val exit = FakeWalletExit(
            failure = FakeWalletExit.FakeExitFailure(
                passes = EXIT_STALL_THRESHOLD,
                reason = ExitStallReason.NEEDS_ONCHAIN_FUNDS,
            ),
        )
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(EXIT_STALL_THRESHOLD))
        runCurrent()

        val exiting = assertNotNull(m.model.value.exiting)
        assertEquals("Needs on-chain funds", exiting.headline)
        assertEquals("Add on-chain funds", exiting.stallAction)
    }

    /**
     * Taking the deposit route does not end the exit, and does not arm funding. Covers AE9a.
     *
     * Both halves matter. Leaving exiting mode would be the cancel this plan refuses to build, and
     * arming funding would board the deposit — spending on the Ark the very sats that were meant
     * to pay the exit's fees, which would deepen the stall rather than clear it.
     */
    @Test
    fun clearing_a_stall_by_depositing_neither_ends_the_exit_nor_boards() = runTest {
        val exit = FakeWalletExit(
            failure = FakeWalletExit.FakeExitFailure(
                passes = EXIT_STALL_THRESHOLD,
                reason = ExitStallReason.NEEDS_ONCHAIN_FUNDS,
            ),
        )
        val funding = SpyFunding()
        val m = machine(exit, funding)
        m.startExit()
        advanceTimeBy(passes(EXIT_STALL_THRESHOLD))
        runCurrent()

        m.goDeposit()
        runCurrent()

        assertNotNull(m.model.value.exiting, "depositing to unstick an exit is not a cancel")
        assertEquals(0, funding.armCalls, "the deposit must pay fees, not be boarded back in")
        assertEquals(0, funding.boardCalls)
    }

    /** Every other category is wait-and-retry, and says so by offering nothing. Covers AE9b. */
    @Test
    fun other_stalls_offer_no_action_at_all() = runTest {
        val everythingElse = ExitStallReason.entries.filterNot { it.isClearableByDeposit }
        everythingElse.forEach { reason ->
            val m = machine(
                FakeWalletExit(
                    failure = FakeWalletExit.FakeExitFailure(
                        passes = EXIT_STALL_THRESHOLD,
                        reason = reason,
                    ),
                ),
            )
            m.startExit()
            advanceTimeBy(passes(EXIT_STALL_THRESHOLD))
            runCurrent()
            assertNull(assertNotNull(m.model.value.exiting).stallAction, "$reason offered an action")
        }
    }

    @Test
    fun boarding_becomes_available_again_once_the_exit_is_done() = runTest {
        val exit = FakeWalletExit()
        val funding = SpyFunding()
        val m = machine(exit, funding)
        m.startExit()
        advanceTimeBy(passes(FakeWalletExit.DEFAULT_SCRIPT.size + 1))
        runCurrent()
        assertNull(m.model.value.exiting)

        m.goDeposit()
        runCurrent()

        assertEquals(1, funding.armCalls, "a finished exit leaves an ordinary, boardable wallet")
    }

    @Test
    fun sending_is_refused_while_the_wallet_is_exiting() = runTest {
        val m = machine(FakeWalletExit())
        m.startExit()
        runCurrent()
        val before = m.model.value.route

        m.confirmSend()
        runCurrent()

        assertEquals(before, m.model.value.route, "the send never left the current screen")
        assertNotNull(m.model.value.exiting, "and the wallet is still exiting")
    }

    // --- Stalls ---

    @Test
    fun a_stall_is_reported_and_the_wallet_keeps_trying() = runTest {
        val exit = FakeWalletExit(failure = FakeWalletExit.FakeExitFailure(passes = EXIT_STALL_THRESHOLD))
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(EXIT_STALL_THRESHOLD))
        runCurrent()

        val exiting = assertNotNull(m.model.value.exiting)
        assertTrue(exiting.stalled)
        // The stall names its cause rather than the stage it is stuck at, and says how long. The
        // wording is per-category now (R20a), so this asserts the reporting, not one phrasing.
        assertEquals("Can't reach the network", exiting.headline)
        assertTrue(exiting.detail.contains("Stuck for"), "got: ${exiting.detail}")
        assertTrue(exiting.detail.contains("funds are safe"), "got: ${exiting.detail}")
        // Nothing to press: this category is not one the holder can clear.
        assertNull(exiting.stallAction)

        val passesAtStall = exit.passes
        advanceTimeBy(passes(2))
        runCurrent()
        assertTrue(exit.passes > passesAtStall, "a stalled exit is still being driven")
    }

    @Test
    fun a_stall_never_offers_a_way_out_of_the_exiting_state() = runTest {
        val exit = FakeWalletExit(failure = FakeWalletExit.FakeExitFailure(passes = EXIT_STALL_THRESHOLD))
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(EXIT_STALL_THRESHOLD))
        runCurrent()

        assertNotNull(m.model.value.exiting, "stalled is still exiting")
    }

    @Test
    fun a_stall_that_clears_stops_being_reported() = runTest {
        val exit = FakeWalletExit(failure = FakeWalletExit.FakeExitFailure(passes = EXIT_STALL_THRESHOLD))
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(EXIT_STALL_THRESHOLD))
        runCurrent()
        assertTrue(assertNotNull(m.model.value.exiting).stalled)

        advanceTimeBy(passes(2))
        runCurrent()

        assertFalse(assertNotNull(m.model.value.exiting).stalled)
    }

    // --- What the surface says ---

    @Test
    fun the_exiting_surface_reports_progress_against_the_whole_wallet() = runTest {
        val exit = FakeWalletExit(vtxoCount = 3)
        val m = machine(exit)
        m.startExit()
        runCurrent()

        val exiting = assertNotNull(m.model.value.exiting)
        assertEquals("0 of 3 claimed", exiting.claimedOf)
    }

    @Test
    fun a_wallet_with_no_exit_capability_never_enters_the_state() = runTest {
        val m = AppStateMachine(
            core = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO),
            demo = null,
            scope = backgroundScope,
        )
        m.startExit()
        runCurrent()

        assertNull(m.model.value.exiting)
    }
}
