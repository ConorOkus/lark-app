package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.model.AdvancedStats
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A core whose "next round" answer moves with the clock, the way the in-process core's does: it
 * holds the server's absolute round time and renders the countdown at read time.
 *
 * This is what makes the tick observable. Without a re-render the row is frozen at whatever it said
 * when the screen was opened — the value is recomputed on read, but nothing reads it.
 */
private class TickingRoundCore(
    private val nowMillis: () -> Long,
    private val fake: FakeLarkCore = FakeLarkCore(startWithWallet = true),
) : LarkCore by fake {
    /** Reads count as evidence that the countdown was recomputed, not just re-emitted. */
    var reads = 0
        private set

    override fun advancedStats(): AdvancedStats {
        reads++
        val stats = fake.advancedStats()
        return stats.copy(network = stats.network.copy(nextRound = "in ${nowMillis() / 1_000L} seconds"))
    }
}

private fun TestScope.machineWith(core: LarkCore) = AppStateMachine(
    core = core,
    demo = null,
    scope = backgroundScope,
    nowMillis = { testScheduler.currentTime },
)

/**
 * The Advanced screen's round countdown is the one row in the app whose value goes stale on its own:
 * every other figure changes only when the wallet changes, and so re-renders when the core emits.
 * A countdown measured in seconds has to be driven by the clock instead, and only while it is being
 * looked at — a per-second re-render of a screen nobody is on is pure battery cost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedRoundTickerTest {

    @Test
    fun the_round_countdown_advances_while_advanced_is_on_screen() = runTest {
        val core = TickingRoundCore(nowMillis = { testScheduler.currentTime })
        val machine = machineWith(core)
        machine.push(Route.ADVANCED)
        runCurrent()
        val first = machine.model.value.advanced.network.nextRound

        advanceTimeBy(1_500L)
        runCurrent()

        val second = machine.model.value.advanced.network.nextRound
        assertEquals("in 0 seconds", first)
        assertEquals("in 1 seconds", second, "the countdown must move without the user touching anything")
    }

    /**
     * The cost side of the same behavior. A ticker that outlives the screen would re-render the
     * whole model every second for the rest of the session, on a device holding someone's money.
     */
    @Test
    fun leaving_advanced_stops_the_ticker() = runTest {
        val core = TickingRoundCore(nowMillis = { testScheduler.currentTime })
        val machine = machineWith(core)
        machine.push(Route.ADVANCED)
        runCurrent()
        advanceTimeBy(1_500L)
        runCurrent()

        machine.back()
        runCurrent()
        val readsOnLeaving = core.reads

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(readsOnLeaving, core.reads, "no re-render should happen once Advanced is closed")
    }

    /** Never started, not merely stopped: arriving is what turns it on. */
    @Test
    fun the_ticker_does_not_run_before_advanced_is_opened() = runTest {
        val core = TickingRoundCore(nowMillis = { testScheduler.currentTime })
        val machine = machineWith(core)
        runCurrent()
        val readsAtRest = core.reads

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(readsAtRest, core.reads, "a screen nobody opened must not cost a tick")
    }
}
