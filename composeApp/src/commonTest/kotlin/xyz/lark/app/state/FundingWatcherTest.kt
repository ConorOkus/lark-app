package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.OnchainFunding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val MIN_BOARD = 10_000L
private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1_000
private const val WINDOW_MILLIS = 7 * ONE_DAY_MILLIS

/**
 * The watcher's two cadences, mirrored from the machine.
 *
 * Duplicated rather than exposed: a test that reads the production constant cannot notice it
 * changing, and the point of these is that the timing is deliberate.
 */
private const val ACTIVE_TICK_MILLIS = 20_000L
private const val IDLE_TICK_MILLIS = 120_000L

/** Virtual millis to advance for exactly [ticks] watcher passes, the first firing at t=0. */
private fun ticks(ticks: Int, every: Long) = (ticks - 1) * every + 1

/**
 * An [OnchainFunding] whose persistence outlives the machine, like the real store.
 *
 * The armed timestamp lives here rather than in the machine on purpose: the behaviour under test is
 * that a request for money survives the app being closed, and a fake that forgot on construction
 * could not fail that test.
 */
private class FakeOnchainFunding(
    var confirmed: Long = 0L,
    var pending: Long = 0L,
    private var armedAt: Long? = null,
    /** Each element is one board attempt's outcome, consumed in order; the last repeats. */
    private val boardOutcomes: List<Boolean> = listOf(true),
) : OnchainFunding {

    var boardCalls = 0
        private set
    var syncCalls = 0
        private set

    override val confirmedSats: Long get() = confirmed
    override val pendingSats: Long get() = pending
    override val minBoardSats: Long = MIN_BOARD

    override val fundingArmedAtMillis: Long? get() = armedAt

    override fun armFunding(atMillis: Long) {
        armedAt = atMillis
    }

    override fun disarmFunding() {
        armedAt = null
    }

    override suspend fun syncOnchain() {
        syncCalls++
    }

    override suspend fun boardAll(): Boolean {
        val outcome = boardOutcomes.getOrElse(boardCalls) { boardOutcomes.last() }
        boardCalls++
        if (outcome) {
            confirmed = 0
            pending = 0
        }
        return outcome
    }
}

/**
 * A machine wired to [funding] with a wall clock the test controls.
 *
 * `workDelay = ZERO` so a watcher cycle is exactly its poll interval: these tests care about how
 * many passes happened, and the core's 1.5s spinner would make that arithmetic a guess.
 */
private fun TestScope.fundedMachine(
    funding: FakeOnchainFunding,
    wallClock: () -> Long,
): AppStateMachine = AppStateMachine(
    core = FakeLarkCore(startWithWallet = true, workDelay = Duration.ZERO),
    demo = null,
    scope = backgroundScope,
    nowMillis = { testScheduler.currentTime },
    funding = funding,
    wallClockMillis = wallClock,
)

/**
 * The rules governing when LARK moves a deposit into spendable money on its own.
 *
 * The load-bearing question throughout is "does the app ever move money the user did not ask it
 * to?", so most of these assert a board that must *not* happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FundingWatcherTest {

    // --- Arming ---

    @Test
    fun openingTheDepositScreenIsWhatAsksForMoney() = runTest {
        val funding = FakeOnchainFunding()
        val m = fundedMachine(funding) { 1_000L }
        assertNull(funding.fundingArmedAtMillis)
        m.goDeposit()
        assertEquals(1_000L, funding.fundingArmedAtMillis)
    }

    @Test
    fun neverBoardsWithoutBeingAsked() = runTest {
        // The whole point of intent-scoping: money in the on-chain wallet is not consent.
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD * 5)
        fundedMachine(funding) { 1_000L }
        advanceTimeBy(ticks(5, IDLE_TICK_MILLIS))
        assertEquals(0, funding.boardCalls)
    }

    @Test
    fun reopeningTheDepositScreenRenewsTheRequest() = runTest {
        val funding = FakeOnchainFunding()
        var clock = 1_000L
        val m = fundedMachine(funding) { clock }
        m.goDeposit()
        clock += 6 * ONE_DAY_MILLIS
        m.goDeposit()
        // Six days on from the first ask, the deadline is a fresh seven days from the second.
        clock += 6 * ONE_DAY_MILLIS
        funding.confirmed = MIN_BOARD * 2
        advanceTimeBy(ticks(2, ACTIVE_TICK_MILLIS))
        assertEquals(1, funding.boardCalls)
    }

    // --- Expiry ---

    @Test
    fun anExpiredRequestDoesNotAuthoriseLaterMoney() = runTest {
        val funding = FakeOnchainFunding()
        var clock = 1_000L
        val m = fundedMachine(funding) { clock }
        m.goDeposit()
        clock += WINDOW_MILLIS + ONE_DAY_MILLIS
        // Arrives after the request lapsed, into a wallet that was empty when it did. Reviving the
        // request on the strength of the balance alone would board money nobody asked about.
        funding.confirmed = MIN_BOARD * 2
        advanceTimeBy(ticks(5, IDLE_TICK_MILLIS))
        assertEquals(0, funding.boardCalls)
    }

    @Test
    fun alapsedRequestIsErasedRatherThanLeftLying() = runTest {
        val funding = FakeOnchainFunding()
        var clock = 1_000L
        val m = fundedMachine(funding) { clock }
        m.goDeposit()
        clock += WINDOW_MILLIS + ONE_DAY_MILLIS
        advanceTimeBy(ticks(2, IDLE_TICK_MILLIS))
        assertNull(funding.fundingArmedAtMillis)
    }

    @Test
    fun moneyWaitingUnderTheMinimumIsNeverStrandedByTheDeadline() = runTest {
        // The wall clock tracks virtual time here, so the watcher really does keep running across
        // the whole eight days rather than being teleported past them.
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD / 2)
        val m = fundedMachine(funding) { 1_000L + testScheduler.currentTime }
        m.goDeposit()
        advanceTimeBy(WINDOW_MILLIS + ONE_DAY_MILLIS)
        // Eight days of sitting below the minimum, then a top-up. The request is still live,
        // because money the user is waiting on keeps pushing the deadline forward.
        funding.confirmed = MIN_BOARD * 2
        advanceTimeBy(ticks(2, ACTIVE_TICK_MILLIS))
        assertEquals(1, funding.boardCalls)
    }

    @Test
    fun aRequestFromAPreviousLaunchStillCounts() = runTest {
        // The store remembers; the machine is brand new, as after a cold start.
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD * 2, armedAt = 500L)
        fundedMachine(funding) { 500L + ONE_DAY_MILLIS }
        advanceTimeBy(ticks(2, ACTIVE_TICK_MILLIS))
        assertEquals(1, funding.boardCalls)
    }

    // --- Exit ---

    @Test
    fun startingAnExitStopsMoneyBeingPulledBackIn() = runTest {
        val funding = FakeOnchainFunding()
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        m.startExit()
        assertNull(funding.fundingArmedAtMillis)
        // Exit funds land in the same on-chain wallet the watcher reads. Nothing may touch them.
        funding.confirmed = MIN_BOARD * 10
        advanceTimeBy(ticks(5, IDLE_TICK_MILLIS))
        assertEquals(0, funding.boardCalls)
    }

    @Test
    fun exitingDoesNotPermanentlyDisableFunding() = runTest {
        val funding = FakeOnchainFunding()
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        m.startExit()
        m.goDeposit()
        funding.confirmed = MIN_BOARD * 2
        advanceTimeBy(ticks(2, ACTIVE_TICK_MILLIS))
        assertEquals(1, funding.boardCalls)
    }

    // --- Boarding ---

    @Test
    fun anAskedForDepositBecomesSpendableWithoutATap() = runTest {
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD * 2)
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        advanceTimeBy(ticks(2, ACTIVE_TICK_MILLIS))
        assertEquals(1, funding.boardCalls)
        // And does not board again once the on-chain wallet is empty.
        advanceTimeBy(ticks(5, IDLE_TICK_MILLIS))
        assertEquals(1, funding.boardCalls)
    }

    @Test
    fun aDepositUnderTheMinimumIsLeftAloneRatherThanFailed() = runTest {
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD / 2)
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        advanceTimeBy(ticks(3, ACTIVE_TICK_MILLIS))
        assertEquals(0, funding.boardCalls)
        val arriving = m.model.value.balance.arriving
        assertNotNull(arriving)
        assertTrue(arriving.note.contains("at least"), "expected a shortfall note, got: ${arriving.note}")
    }

    @Test
    fun oneOrTwoFailuresStaySilent() = runTest {
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD * 2, boardOutcomes = listOf(false))
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        advanceTimeBy(ticks(2, ACTIVE_TICK_MILLIS))
        assertEquals(2, funding.boardCalls)
        val note = m.model.value.balance.arriving?.note
        assertNotNull(note)
        assertFalse(note.contains("longer than it should"), "surfaced too early: $note")
    }

    @Test
    fun aThirdConsecutiveFailureIsWorthSaying() = runTest {
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD * 2, boardOutcomes = listOf(false))
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        advanceTimeBy(ticks(3, ACTIVE_TICK_MILLIS))
        assertEquals(3, funding.boardCalls)
        val note = m.model.value.balance.arriving?.note
        assertNotNull(note)
        assertTrue(note.contains("longer than it should"), "expected the trouble note, got: $note")
    }

    @Test
    fun successAfterFailuresClearsTheWarning() = runTest {
        val funding = FakeOnchainFunding(
            confirmed = MIN_BOARD * 2,
            boardOutcomes = listOf(false, false, false, true),
        )
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        advanceTimeBy(ticks(4, ACTIVE_TICK_MILLIS))
        // The fourth attempt succeeded, so the wallet is empty and there is nothing left to report.
        assertEquals(4, funding.boardCalls)
        assertNull(m.model.value.balance.arriving)
    }

    @Test
    fun repeatedAskingDoesNotStackWatchers() = runTest {
        val funding = FakeOnchainFunding()
        val m = fundedMachine(funding) { 1_000L }
        repeat(4) { m.goDeposit() }
        runCurrent()
        advanceTimeBy(ticks(2, IDLE_TICK_MILLIS))
        // One watcher means one sync per cycle. Four would mean four pollers racing the chain.
        assertEquals(2, funding.syncCalls)
    }

    // --- What the user sees ---

    @Test
    fun nothingArrivingMeansNothingOnHome() = runTest {
        val m = fundedMachine(FakeOnchainFunding()) { 1_000L }
        assertNull(m.model.value.balance.arriving)
    }

    @Test
    fun unconfirmedMoneyStillReadsAsOnItsWay() = runTest {
        // Below the minimum in confirmed terms, but more is still confirming — calling that a
        // shortfall would send the user to top up for nothing.
        val funding = FakeOnchainFunding(confirmed = 0, pending = MIN_BOARD * 2)
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        val arriving = m.model.value.balance.arriving
        assertNotNull(arriving)
        assertTrue(arriving.note.contains("few minutes"), "expected the ordinary wait, got: ${arriving.note}")
    }

    @Test
    fun hidingTheBalanceHidesWhatIsArrivingToo() = runTest {
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD / 2)
        val m = fundedMachine(funding) { 1_000L }
        m.toggleBalance()
        val arriving = m.model.value.balance.arriving
        assertNotNull(arriving)
        assertEquals("••••", arriving.amount)
    }

    @Test
    fun theDepositScreenTellsTheSameStoryAsHome() = runTest {
        val funding = FakeOnchainFunding(confirmed = MIN_BOARD / 2)
        val m = fundedMachine(funding) { 1_000L }
        m.goDeposit()
        assertEquals(m.model.value.balance.arriving?.note, m.model.value.deposit?.arriving?.note)
    }

    @Test
    fun aCoreWithNoOnchainWalletShowsNoneOfThis() = runTest {
        val m = AppStateMachine(core = FakeLarkCore(startWithWallet = true), demo = null, scope = backgroundScope)
        assertNull(m.model.value.balance.arriving)
        assertNull(m.model.value.deposit)
    }

    // --- Vocabulary ---

    @Test
    fun noneOfTheseStringsTeachTheUserAboutBoarding() = runTest {
        // The machine composes these, so the source-scanning guard over ui/screens cannot see them.
        // Whole words only: the product is called LARK, which contains "ark".
        val forbidden = listOf("board", "boarding", "ark", "vtxo", "on-chain", "onchain")
        val notes = listOf(
            FakeOnchainFunding(confirmed = MIN_BOARD / 2),
            FakeOnchainFunding(pending = MIN_BOARD * 2),
            FakeOnchainFunding(confirmed = MIN_BOARD * 2, boardOutcomes = listOf(false)),
        ).mapNotNull { funding ->
            val m = fundedMachine(funding) { 1_000L }
            m.goDeposit()
            advanceTimeBy(ticks(3, ACTIVE_TICK_MILLIS))
            m.model.value.balance.arriving?.note
        }
        assertEquals(3, notes.size, "expected all three states to produce a note")
        notes.forEach { note ->
            val words = note.lowercase().split(Regex("[^a-z-]+"))
            forbidden.forEach { word ->
                assertFalse(word in words, "'$word' leaked into user copy: $note")
            }
        }
    }
}
