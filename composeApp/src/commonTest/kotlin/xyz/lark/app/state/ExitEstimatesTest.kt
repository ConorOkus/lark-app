package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.FakeWalletExit
import xyz.lark.app.core.format.EXPIRY_PLACEHOLDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

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

/**
 * The exit screen's two worked-out figures.
 *
 * This screen used to state `~$1.80` and `about 24 hours` as string literals under a button that
 * cannot be taken back. These tests exist to keep any figure here derived or absent — never
 * invented — so the failure mode they replaced cannot come back quietly.
 */
class ExitEstimatesTest {

    @Test
    fun the_miner_fee_is_unknown_rather_than_guessed() = runTest {
        val m = machineWith(FakeWalletExit())
        m.goExit()
        runCurrent()
        // bark keeps its exit-cost estimate crate-private, so nothing above it can price an exit.
        // The row says "not known"; it must never say a number until that changes upstream.
        assertEquals(EXPIRY_PLACEHOLDER, m.model.value.exitEstimates.minerFee)
    }

    @Test
    fun the_wait_is_derived_from_the_delta_at_the_networks_real_spacing() = runTest {
        val m = machineWith(
            FakeWalletExit(heights = FakeWalletExit.FakeExitHeights(deltaBlocks = 144)),
        )
        m.goExit()
        runCurrent()
        // 144 blocks is a bit over an hour on 30-second blocks. Reading "about 1 day" here would
        // mean the spacing was ignored — the 20x error the shared helper exists to prevent.
        assertEquals("about 1 hour", m.model.value.exitEstimates.readyIn)
    }

    @Test
    fun an_unreachable_server_leaves_the_wait_unknown_rather_than_defaulted() = runTest {
        // The scenario unilateral exit exists for: no server, so no ArkInfo, so no delta. The
        // screen has to be able to say it does not know, because this is the common case here.
        val m = machineWith(FakeWalletExit())
        m.goExit()
        runCurrent()
        assertEquals(EXPIRY_PLACEHOLDER, m.model.value.exitEstimates.readyIn)
    }
}
