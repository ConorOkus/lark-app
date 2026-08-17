package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.OnchainFunding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val MIN_BOARD = 10_000L

/** The demo wallet's off-chain balance, mirrored so the arithmetic below is legible. */
private const val OFFCHAIN = 412_350L

/** What the mutinynet exit actually landed. Enough to be worth naming; not a round number. */
private const val ONCHAIN = 119_350L

/**
 * On-chain funds that simply sit there.
 *
 * Never armed, so the watcher will not board them — which is the situation after an exit, and the
 * only one where a holder can be looking at money home used to refuse to mention.
 */
private class StaticFunding(
    override val confirmedSats: Long,
    override val pendingSats: Long = 0L,
) : OnchainFunding {
    override val minBoardSats: Long = MIN_BOARD
    override val fundingArmedAtMillis: Long? = null
    override fun armFunding(atMillis: Long) = Unit
    override fun disarmFunding() = Unit
    override suspend fun syncOnchain() = Unit
    override suspend fun boardAll(): Boolean = false
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.machine(
    funding: OnchainFunding?,
    core: FakeLarkCore = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO),
): AppStateMachine = AppStateMachine(
    core = core,
    demo = null,
    scope = backgroundScope,
    nowMillis = { testScheduler.currentTime },
    funding = funding,
    wallClockMillis = { testScheduler.currentTime },
)

private fun AppStateMachine.type(digits: String) = digits.forEach { keyPress(it) }

/**
 * What home says a wallet is worth.
 *
 * These exist because of a real screen. A wallet that had just finished a unilateral exit showed
 * ₿0 over 119,350 sats the holder plainly owned — the headline read the off-chain balance alone,
 * and the exited money was on-chain. Nothing was lost; home simply declined to mention it.
 *
 * The rule pinned here: the headline is everything owned, and whatever it covers that Pay cannot
 * spend right now is said out loud rather than left to be discovered at the keypad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeBalanceSplitTest {

    @Test
    fun the_headline_counts_money_held_onchain() = runTest {
        val m = machine(StaticFunding(confirmedSats = ONCHAIN))
        runCurrent()

        assertEquals("₿531,700", m.model.value.balance.primary)
    }

    /** The case that started this: off-chain genuinely zero, and the old headline said so. */
    @Test
    fun a_wallet_that_has_exited_does_not_read_as_empty() = runTest {
        val core = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO)
        core.send("drain", OFFCHAIN)
        val m = machine(StaticFunding(confirmedSats = ONCHAIN), core = core)
        runCurrent()

        assertEquals(0L, core.balanceSats.value, "the exit left nothing off-chain")
        assertEquals("₿119,350", m.model.value.balance.primary)
    }

    @Test
    fun the_split_names_what_pay_can_actually_send() = runTest {
        val m = machine(StaticFunding(confirmedSats = ONCHAIN))
        runCurrent()

        val split = assertNotNull(m.model.value.balance.split, "on-chain funds must be accounted for")
        assertEquals("₿412,350", split.instant)
        assertEquals("₿119,350", split.onchain)
    }

    /** The ordinary wallet, which is most of them: one balance, nothing to explain. */
    @Test
    fun an_ordinary_wallet_shows_no_split_at_all() = runTest {
        val m = machine(StaticFunding(confirmedSats = 0L))
        runCurrent()
        assertNull(m.model.value.balance.split)
    }

    @Test
    fun a_core_with_no_onchain_wallet_shows_no_split() = runTest {
        val m = machine(funding = null)
        runCurrent()
        assertNull(m.model.value.balance.split)
    }

    /** Hiding the balance hides all of it — a split line would leak the figure just masked. */
    @Test
    fun hiding_the_balance_hides_the_split() = runTest {
        val m = machine(StaticFunding(confirmedSats = ONCHAIN))
        runCurrent()
        m.toggleBalance()
        assertNull(m.model.value.balance.split)
    }

    /**
     * Pay still validates against the off-chain balance, so the headline can now exceed what a
     * send can spend. That is the trade the split line pays for: the total is true, and the line
     * says which part of it is not instantly spendable — rather than the keypad refusing money the
     * home screen just promised, with no explanation anywhere.
     */
    @Test
    fun the_send_limit_is_unchanged_by_what_home_shows() = runTest {
        val m = machine(StaticFunding(confirmedSats = ONCHAIN))
        runCurrent()

        m.goSendAmount()
        m.type((OFFCHAIN + 1).toString())

        assertTrue(m.model.value.keypad.overBalance, "a send may not draw on funds Ark cannot reach")
        assertEquals("₿531,700", m.model.value.balance.primary, "and the headline still tells the truth")
    }
}
