package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.FakeWalletExit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

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
 * Resuming an exit when the wallet cannot answer yet.
 *
 * This is the case that shipped broken, twice over, and both failures looked identical from the
 * outside: the holder reopened the app to an ordinary wallet, ₿0, Pay and Get paid offered, while
 * their VTXOs sat mid-exit in the database. First because the machine read a status property that
 * is cold at construction; then because it treated "cannot tell yet" as "nothing is exiting".
 *
 * The wallet opens asynchronously, so a resume *always* lands in that gap. Waiting for a real
 * answer is the only correct behaviour, and these tests exist because no amount of care in the
 * production code survives a fake that can always answer instantly.
 */
class ExitResumeTest {

    @Test
    fun an_exit_resumes_even_though_the_first_reads_cannot_answer() = runTest {
        val exit = FakeWalletExit(startedAlready = true).apply { unreadableReads = 4 }
        val m = machine(exit)
        runCurrent()

        // The wallet is still opening: nothing can be claimed about the exit yet.
        assertEquals(null, m.model.value.exiting, "no exit may be reported before one is read")

        advanceTimeBy(3_000)
        runCurrent()
        assertNotNull(m.model.value.exiting, "the exit must be picked up once the wallet can answer")
    }

    @Test
    fun a_resumed_exit_is_actually_driven_forward() = runTest {
        val exit = FakeWalletExit(startedAlready = true).apply { unreadableReads = 2 }
        val m = machine(exit)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        val passesAtResume = exit.passes

        advanceTimeBy(90_000)
        runCurrent()
        assertTrue(exit.passes > passesAtResume, "a resumed exit that is not driven is not resumed")
    }

    /** A wallet with no exit still settles, rather than retrying for a status forever. */
    @Test
    fun a_wallet_with_no_exit_settles_once_it_can_answer() = runTest {
        val exit = FakeWalletExit().apply { unreadableReads = 2 }
        val m = machine(exit)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(null, m.model.value.exiting)
    }
}
