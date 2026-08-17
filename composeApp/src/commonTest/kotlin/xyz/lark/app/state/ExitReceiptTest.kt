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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val EXIT_TICK_MILLIS = 30_000L

private fun passes(passes: Int) = (passes - 1) * EXIT_TICK_MILLIS + 1

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.machine(exit: FakeWalletExit): AppStateMachine = AppStateMachine(
    core = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO),
    demo = null,
    scope = backgroundScope,
    nowMillis = { testScheduler.currentTime },
    funding = null,
    walletExit = exit,
    wallClockMillis = { testScheduler.currentTime },
)

/**
 * The receipt for a finished exit.
 *
 * Two properties carry the weight. It must appear — an exit runs for hours with nothing to watch,
 * so a holder who opens the app to an ordinary home would never learn it worked except by counting
 * sats. And it must appear exactly once, including across the relaunch that is the *likely* way
 * this is seen rather than the unlucky one.
 */
class ExitReceiptTest {

    @Test
    fun finishing_an_exit_puts_the_receipt_up() = runTest {
        val exit = FakeWalletExit()
        val m = machine(exit)
        m.startExit()
        advanceTimeBy(passes(FakeWalletExit.DEFAULT_SCRIPT.size + 1))
        runCurrent()

        assertEquals(Route.EXIT_DONE, m.model.value.route)
        val done = assertNotNull(m.model.value.exitDone)
        // No "about": how long it took is measured, so it is stated flatly. The hedge belongs on
        // the countdown, which is a prediction.
        assertEquals("3 hours", done.took)
        assertNull(m.model.value.exiting, "the wallet has left the exiting state by now")
    }

    /**
     * The relaunch case, and the reason the timestamps are persisted at all: the app was closed
     * when the last claim landed, which is what usually happens.
     */
    @Test
    fun a_receipt_owed_from_a_previous_session_appears_at_launch() = runTest {
        // Already finished, never acknowledged — exactly the state a killed app leaves behind.
        val exit = FakeWalletExit(script = listOf(ExitStage.CLAIMED), startedAlready = true)
        val m = machine(exit)
        runCurrent()

        assertEquals(Route.EXIT_DONE, m.model.value.route)
        assertNotNull(m.model.value.exitDone)
    }

    @Test
    fun dismissing_the_receipt_returns_to_an_ordinary_wallet() = runTest {
        val exit = FakeWalletExit(script = listOf(ExitStage.CLAIMED), startedAlready = true)
        val m = machine(exit)
        runCurrent()

        m.dismissExitReceipt()
        runCurrent()

        assertEquals(Route.HOME, m.model.value.route)
        assertNull(m.model.value.exitDone)
    }

    /** Once means once: a dismissed receipt does not come back on the next launch. */
    @Test
    fun a_dismissed_receipt_does_not_return() = runTest {
        val exit = FakeWalletExit(script = listOf(ExitStage.CLAIMED), startedAlready = true)
        val first = machine(exit)
        runCurrent()
        first.dismissExitReceipt()
        runCurrent()

        // Same capability, fresh machine: the app reopened.
        val second = machine(exit)
        runCurrent()
        assertEquals(Route.HOME, second.model.value.route)
        assertNull(second.model.value.exitDone)
    }

    /**
     * Covers R22. The funds are already under the holder's own keys, so the receipt must not read
     * as an outstanding task — that framing was in the plan until the code showed it was wrong.
     */
    @Test
    fun the_receipt_states_a_result_rather_than_an_unfinished_step() = runTest {
        val exit = FakeWalletExit(script = listOf(ExitStage.CLAIMED), startedAlready = true)
        val m = machine(exit)
        runCurrent()

        val done = assertNotNull(m.model.value.exitDone)
        assertTrue(done.landed.isNotBlank())
        // The cost is not recorded by the engine, so it reads as unknown rather than as a figure.
        assertEquals("—", done.minerFee)
    }
}
