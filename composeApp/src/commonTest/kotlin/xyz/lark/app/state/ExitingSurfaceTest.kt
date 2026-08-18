package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.FakeWalletExit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration

/** The tip `FakeLarkCore` reports, mirrored so the expected countdowns are readable here. */
private const val FAKE_TIP = 916_214L

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.machineWith(exit: FakeWalletExit): AppStateMachine = AppStateMachine(
    core = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO),
    demo = null,
    scope = backgroundScope,
    nowMillis = { testScheduler.currentTime },
    funding = null,
    walletExit = exit,
    wallClockMillis = { testScheduler.currentTime },
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.exitingAt(
    stage: ExitStage,
    claimableAtHeight: Long? = null,
): ExitingModel {
    val m = machineWith(
        FakeWalletExit(
            script = listOf(stage),
            heights = FakeWalletExit.FakeExitHeights(claimableAtHeight = claimableAtHeight),
            startedAlready = true,
        ),
    )
    // One pass, because the capability reports NOT_EXITING until something asks — the same
    // coldness the real core has, and the reason the resume path had to stop reading it directly.
    runCurrent()
    advanceTimeBy(1)
    runCurrent()
    return assertNotNull(m.model.value.exiting, "the wallet should be exiting at $stage")
}

/**
 * The headline on the surface a holder stares at for hours.
 *
 * The rule under test is one thing: a number appears only where one can be computed. Everywhere
 * else the state says its own name. Getting this wrong does not look like a bug — it looks like a
 * countdown, and it is wrong for hours before anyone notices.
 */
class ExitingSurfaceTest {

    @Test
    fun waiting_out_the_delay_headlines_a_countdown() = runTest {
        // 144 blocks short of claimable, on 30-second blocks: a bit over an hour.
        val exiting = exitingAt(ExitStage.WAITING_OUT_DELAY, claimableAtHeight = FAKE_TIP + 144)
        assertEquals("about 1 hour", exiting.headline)
    }

    /**
     * Before the exit confirms, what remains depends on confirmation time, which nothing here
     * knows. The state names itself rather than borrowing a number from the stage after it.
     */
    @Test
    fun broadcasting_headlines_the_state_and_no_figure() = runTest {
        val exiting = exitingAt(ExitStage.BROADCASTING, claimableAtHeight = FAKE_TIP + 144)
        assertEquals("Confirming your exit", exiting.headline)
    }

    /** After the delay, what remains depends on the claim confirming — also unknowable. */
    @Test
    fun claiming_headlines_the_state_and_no_figure() = runTest {
        val exiting = exitingAt(ExitStage.CLAIMABLE, claimableAtHeight = FAKE_TIP)
        assertEquals("Claiming your funds", exiting.headline)
    }

    /**
     * No claimable height means no countdown — not a countdown from zero, and not one invented
     * from the delta, which is unreadable in the scenario this feature exists for.
     */
    @Test
    fun waiting_without_a_known_height_falls_back_to_the_state_name() = runTest {
        val exiting = exitingAt(ExitStage.WAITING_OUT_DELAY, claimableAtHeight = null)
        assertEquals("Waiting out the delay", exiting.headline)
    }

    @Test
    fun a_passed_claimable_height_reads_as_imminent_rather_than_negative() = runTest {
        val exiting = exitingAt(ExitStage.WAITING_OUT_DELAY, claimableAtHeight = FAKE_TIP - 10)
        assertEquals("any moment now", exiting.headline)
    }

    /**
     * The surface takes over home rather than annotating it: a wallet that is leaving has no
     * spendable balance to show and no actions to offer.
     */
    @Test
    fun the_exiting_model_is_present_only_while_the_wallet_is_leaving() = runTest {
        val m = machineWith(FakeWalletExit(script = listOf(ExitStage.CLAIMED), startedAlready = true))
        runCurrent()
        assertEquals(null, m.model.value.exiting)
    }
}
