package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.gateway.BarkdApiVariant
import xyz.lark.app.core.gateway.BarkdScript
import xyz.lark.app.core.gateway.barkdEngine
import xyz.lark.app.core.gateway.gatewayCore
import xyz.lark.app.core.model.AdvancedStats
import xyz.lark.app.core.model.ChannelDisplay
import xyz.lark.app.core.model.ChannelState
import xyz.lark.app.core.model.ChannelsSnapshot
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.FiatRate
import xyz.lark.app.core.model.FundsStats
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.NetworkStats
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Types each digit of [digits] on the keypad. */
private fun AppStateMachine.type(digits: String) = digits.forEach { keyPress(it) }

/**
 * A machine driven by the test's background scope, so virtual time controls all timing.
 * The wall clock defaults to the test scheduler's virtual time; pass [nowMillis] to move
 * the clock independently of the tick coroutines (simulating app suspension).
 */
private fun TestScope.machine(
    core: FakeLarkCore = FakeLarkCore(startWithWallet = true),
    withDemo: Boolean = true,
    nowMillis: (() -> Long)? = null,
): AppStateMachine = AppStateMachine(
    core = core,
    demo = if (withDemo) core else null,
    scope = backgroundScope,
    nowMillis = nowMillis ?: { testScheduler.currentTime },
)

/** Navigation, onboarding, balance, and keypad behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStateMachineTest {

    // --- Start route ---

    @Test
    fun startsAtWelcomeWithoutAWallet() = runTest {
        val m = machine(core = FakeLarkCore(startWithWallet = false))
        assertEquals(Route.WELCOME, m.model.value.route)
    }

    @Test
    fun startsAtHomeWithAWallet() = runTest {
        val m = machine()
        assertEquals(Route.HOME, m.model.value.route)
    }

    @Test
    fun emptyActivityCoreConstructsAndRendersWithoutThrowing() = runTest {
        // The LarkCore contract permits an empty payment history; rendering must be total.
        val m = AppStateMachine(core = EmptyHistoryCore(), demo = null, scope = backgroundScope)
        assertEquals(Route.WELCOME, m.model.value.route)
        m.finishOnboarding()
        assertEquals(Route.HOME, m.model.value.route)
        assertEquals("Sent", m.model.value.txDetail.verb) // benign placeholder detail
    }

    // --- Navigation stack ---

    @Test
    fun pushThenBackReturnsToOrigin() = runTest {
        val m = machine()
        m.push(Route.SETTINGS)
        assertEquals(Route.SETTINGS, m.model.value.route)
        m.back()
        assertEquals(Route.HOME, m.model.value.route)
    }

    @Test
    fun backOnEmptyStackLandsHomeWithAWallet() = runTest {
        val m = machine()
        m.go(Route.ACTIVITY)
        m.back()
        assertEquals(Route.HOME, m.model.value.route)
    }

    @Test
    fun backOnEmptyStackLandsWelcomeWithoutAWallet() = runTest {
        val m = machine(core = FakeLarkCore(startWithWallet = false))
        m.go(Route.FUND)
        m.back()
        assertEquals(Route.WELCOME, m.model.value.route)
    }

    @Test
    fun goResetsTheStack() = runTest {
        val m = machine()
        m.push(Route.SETTINGS)
        m.push(Route.ADVANCED)
        m.go(Route.ACTIVITY)
        assertEquals(Route.ACTIVITY, m.model.value.route)
        m.back()
        assertEquals(Route.HOME, m.model.value.route)
    }

    @Test
    fun canGoBackOnlyAwayFromTheRestingRoute() = runTest {
        val m = machine()
        assertFalse(m.model.value.canGoBack)
        m.push(Route.SETTINGS)
        assertTrue(m.model.value.canGoBack)
        m.back()
        assertFalse(m.model.value.canGoBack)
        m.go(Route.ACTIVITY)
        assertTrue(m.model.value.canGoBack)
    }

    @Test
    fun screenLabelTracksTheRoute() = runTest {
        val m = machine()
        assertEquals("Home", m.model.value.screenLabel)
        m.push(Route.SETTINGS)
        assertEquals("Settings", m.model.value.screenLabel)
    }

    // --- Onboarding ---

    @Test
    fun onboardingFlowWalksWelcomeToHomeAndCreatesTheWallet() = runTest {
        val core = FakeLarkCore(startWithWallet = false)
        val m = machine(core = core)
        m.goHowItWorks()
        assertEquals(Route.HOW_IT_WORKS, m.model.value.route)
        m.goFund()
        assertEquals(Route.FUND, m.model.value.route)
        m.startBoarding()
        assertEquals(Route.BOARDING, m.model.value.route)
        m.finishOnboarding()
        assertEquals(Route.HOME, m.model.value.route)
        assertTrue(core.walletExists.value)
        assertFalse(m.model.value.canGoBack)
    }

    @Test
    fun restoreCreatesTheWalletAndLandsHome() = runTest {
        val core = FakeLarkCore(startWithWallet = false)
        val m = machine(core = core)
        m.goRestore()
        assertEquals(Route.RESTORE, m.model.value.route)
        m.finishRestore()
        assertEquals(Route.HOME, m.model.value.route)
        assertTrue(core.walletExists.value)
    }

    @Test
    fun backDuringOnboardingNeverSkipsToHome() = runTest {
        val m = machine(core = FakeLarkCore(startWithWallet = false))
        m.goHowItWorks()
        m.goFund()
        m.back()
        assertEquals(Route.HOW_IT_WORKS, m.model.value.route)
        m.back()
        assertEquals(Route.WELCOME, m.model.value.route)
        m.back()
        assertEquals(Route.WELCOME, m.model.value.route)
    }

    // --- Denomination & balance visibility ---

    @Test
    fun denominationToggleFlipsEveryMoneyField() = runTest {
        val m = machine()
        m.goSendAmount()
        with(m.model.value) {
            assertEquals(Denomination.BTC, denomination)
            assertEquals("₿412,350", balance.primary)
            assertEquals("$412.35", balance.secondary)
            assertEquals("Bitcoin (₿)", balance.unitLabel)
            assertEquals("−₿14,200", activity[0].amount)
            assertEquals("+₿250,000", activity[2].amount)
            assertEquals("₿412,350 available", keypad.availability)
        }
        m.toggleUnit()
        with(m.model.value) {
            assertEquals(Denomination.FIAT, denomination)
            assertEquals("$412.35", balance.primary)
            assertEquals("₿412,350", balance.secondary)
            assertEquals("Dollars", balance.unitLabel)
            assertEquals("−$14.20", activity[0].amount)
            assertEquals("+$250.00", activity[2].amount)
            assertEquals("$412.35 available", keypad.availability)
        }
    }

    @Test
    fun toggleBalanceHidesAndShows() = runTest {
        val m = machine()
        assertTrue(m.model.value.balance.visible)
        assertEquals("Hide", m.model.value.balance.hideLabel)
        m.toggleBalance()
        with(m.model.value.balance) {
            assertFalse(visible)
            assertEquals("Show", hideLabel)
            assertEquals("••••", primary)
        }
        m.toggleBalance()
        assertTrue(m.model.value.balance.visible)
        assertEquals("₿412,350", m.model.value.balance.primary)
    }

    @Test
    fun exitAmountStaysUnmaskedWhenTheBalanceIsHidden() = runTest {
        // Issue #4: the exit screen's Amount row always shows the real amount (design parity).
        val m = machine()
        m.toggleBalance()
        assertEquals("••••", m.model.value.balance.primary)
        assertEquals("₿412,350", m.model.value.exitAmount)
        m.toggleUnit()
        assertEquals("$412.35", m.model.value.exitAmount)
        assertEquals("••••", m.model.value.balance.primary)
    }

    // --- Keypad ---

    @Test
    fun leadingZeroIsIgnoredOnEmptyDigits() = runTest {
        val m = machine()
        m.goSendAmount()
        m.keyPress('0')
        assertEquals("", m.model.value.keypad.digits)
        m.type("10")
        assertEquals("10", m.model.value.keypad.digits)
    }

    @Test
    fun ninthDigitIsIgnored() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("123456789")
        assertEquals("12345678", m.model.value.keypad.digits)
    }

    @Test
    fun backspaceRemovesLastDigitAndIsANoOpOnEmpty() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("52")
        m.backspace()
        assertEquals("5", m.model.value.keypad.digits)
        m.backspace()
        m.backspace()
        assertEquals("", m.model.value.keypad.digits)
    }

    @Test
    fun goSendAmountResetsDigitsAndSetsSendMode() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("42")
        m.back()
        m.goSendAmount()
        with(m.model.value.keypad) {
            assertEquals("", digits)
            assertEquals(KeypadMode.SEND, mode)
            assertEquals("Paying Jack", header)
            assertEquals("Review", primaryLabel)
            assertFalse(primaryEnabled)
            assertEquals("₿0", amountDisplay)
            assertEquals("$0.00", amountSecondary)
        }
    }

    @Test
    fun overBalanceFlagsAndDisablesInSendMode() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("500000")
        with(m.model.value.keypad) {
            assertTrue(overBalance)
            assertEquals("₿500,000", amountDisplay)
            assertEquals("More than you have", availability)
            assertFalse(primaryEnabled)
        }
        m.keypadConfirm()
        assertEquals(Route.AMOUNT, m.model.value.route)
    }

    @Test
    fun digitsExactlyEqualToBalanceStayEnabled() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("412350")
        with(m.model.value.keypad) {
            assertFalse(overBalance)
            assertTrue(primaryEnabled)
            assertEquals("₿412,350 available", availability)
        }
    }

    @Test
    fun fiatModeDigitsAreCents() = runTest {
        // AE3: fiat denomination, digits 520 → $5.20 primary, ₿5,200 secondary.
        val m = machine()
        m.toggleUnit()
        m.goSendAmount()
        m.type("520")
        with(m.model.value.keypad) {
            assertEquals("$5.20", amountDisplay)
            assertEquals("₿5,200", amountSecondary)
        }
    }

    @Test
    fun fiatModeEmptyDigitsShowBareZero() = runTest {
        val m = machine()
        m.toggleUnit()
        m.goSendAmount()
        with(m.model.value.keypad) {
            assertEquals("$0", amountDisplay)
            assertEquals("₿0", amountSecondary)
        }
    }

    @Test
    fun receiveModeHasRequestHeaderAnyAmountAndNoOverBalanceRule() = runTest {
        val m = machine()
        m.go(Route.RECEIVE)
        m.goReceiveAmount()
        m.type("99999999") // far more than the balance
        with(m.model.value.keypad) {
            assertEquals(KeypadMode.RECEIVE, mode)
            assertEquals("Request", header)
            assertEquals("Any amount", availability)
            assertEquals("Make the code", primaryLabel)
            assertFalse(overBalance)
            assertTrue(primaryEnabled)
        }
    }

    @Test
    fun receiveModeConfirmReturnsToReceive() = runTest {
        val m = machine()
        m.go(Route.RECEIVE)
        m.goReceiveAmount()
        m.type("100")
        m.keypadConfirm()
        assertEquals(Route.RECEIVE, m.model.value.route)
    }

    @Test
    fun sendModeConfirmPushesReview() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        assertEquals(Route.REVIEW, m.model.value.route)
    }

    @Test
    fun confirmIsANoOpWhenDigitsAreEmpty() = runTest {
        val m = machine()
        m.goSendAmount()
        m.keypadConfirm()
        assertEquals(Route.AMOUNT, m.model.value.route)
    }
}

/** Send, refresh, backup, receive, transaction detail, and demo-control behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStateMachineFlowsTest {

    // --- Send flow ---

    @Test
    fun confirmSendEmitsSendingRouteBeforeTheWorkRuns() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        assertEquals(Route.SENDING, m.model.value.route)
    }

    @Test
    fun readySendLandsSentAfter1500VirtualMillis() = runTest {
        // AE1 timing: the 1.5s working delay lives inside the core.
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(Route.SENDING, m.model.value.route)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(Route.SENT, m.model.value.route)
        assertEquals(412_350L - 520L, core.balanceSats.value)
        assertEquals("₿411,830", m.model.value.balance.primary)
        // Off the resting route with an empty stack: back is still meaningful (lands home).
        assertTrue(m.model.value.canGoBack)
    }

    @Test
    fun offlineSendLandsFailedWithBalanceUnchanged() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        core.forceHealth(HealthState.OFFLINE)
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.FAILED, m.model.value.route)
        assertEquals(412_350L, core.balanceSats.value)
        assertEquals("₿412,350", m.model.value.balance.primary)
    }

    @Test
    fun tryAgainRerunsTheSend() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        core.forceHealth(HealthState.OFFLINE)
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.FAILED, m.model.value.route)

        core.forceHealth(HealthState.READY)
        m.tryAgain()
        assertEquals(Route.SENDING, m.model.value.route)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.SENT, m.model.value.route)
        assertEquals(412_350L - 520L, core.balanceSats.value)
    }

    @Test
    fun backDuringSendingIsANoOpAndTheSendStillLands() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        assertEquals(Route.SENDING, m.model.value.route)
        assertFalse(m.model.value.canGoBack)
        m.back() // must not race the in-flight send
        assertEquals(Route.SENDING, m.model.value.route)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.SENT, m.model.value.route)
    }

    @Test
    fun sentAmountUsesTheConfirmedSnapshotNotLiveDigits() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        m.keyPress('9') // digit changes mid-flight must not alter the sent message
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.SENT, m.model.value.route)
        assertEquals("₿520", m.model.value.sentAmount)
        m.keyPress('9') // nor changes after landing
        assertEquals("₿520", m.model.value.sentAmount)
    }

    @Test
    fun sentDoneReturnsHome() = runTest {
        val m = machine()
        m.goSendAmount()
        m.type("520")
        m.keypadConfirm()
        m.confirmSend()
        advanceTimeBy(1_500)
        runCurrent()
        m.go(Route.HOME)
        assertEquals(Route.HOME, m.model.value.route)
        assertFalse(m.model.value.canGoBack)
    }

    @Test
    fun aLightningAddressResolvesAndReadsAsItsOwnName() = runTest {
        val m = machine()
        m.push(Route.SEND_INPUT)
        with(m.model.value.send) {
            assertEquals("Name, invoice or address", inputDisplay)
            assertFalse(inputResolved)
        }
        m.setSendInput("jack@lark.money")
        with(m.model.value.send) {
            assertEquals("jack@lark.money", inputDisplay)
            assertTrue(inputResolved)
            assertEquals("jack@lark.money", recipientName)
        }
    }

    @Test
    fun pickRecentPrefillsRecipientAndJumpsToKeypad() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.push(Route.SEND_INPUT)
        m.goSendAmount()
        m.type("42")
        m.back()
        m.pickRecent(core.recents[1]) // Maya
        assertEquals(Route.AMOUNT, m.model.value.route)
        with(m.model.value) {
            assertEquals("Maya", send.recipientName)
            assertEquals("maya@zaprite.com", send.recipientHandle)
            assertEquals("", keypad.digits)
            assertEquals(KeypadMode.SEND, keypad.mode)
            assertEquals("Paying Maya", keypad.header)
        }
    }

    @Test
    fun scanFoundJumpsToReviewWithFerryBuildingCoffee() = runTest {
        val m = machine()
        m.push(Route.SCAN)
        m.scanFound()
        assertEquals(Route.REVIEW, m.model.value.route)
        with(m.model.value) {
            assertEquals("Ferry Building Coffee", send.recipientName)
            assertEquals("₿520", keypad.amountDisplay)
            assertEquals("$0.52", keypad.amountSecondary)
            assertEquals(KeypadMode.SEND, keypad.mode)
        }
    }

    @Test
    fun fiatModeScanSendsExactlyTheScannedSats() = runTest {
        // Issue #5: the invoice amount is 520 sats, not 520 keypad digits (= cents in fiat mode).
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.toggleUnit() // FIAT
        m.push(Route.SCAN)
        m.scanFound()
        with(m.model.value.keypad) {
            assertEquals("$0.52", amountDisplay)
            assertEquals("₿520", amountSecondary)
        }
        m.confirmSend()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.SENT, m.model.value.route)
        assertEquals(412_350L - 520L, core.balanceSats.value) // exactly 520 sats, not 5,200
        assertEquals("$0.52", m.model.value.sentAmount)
    }

    @Test
    fun manualKeypadEntryAfterAScanClearsTheScannedAmount() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.push(Route.SCAN)
        m.scanFound()
        m.goSendAmount() // entering the keypad manually discards the scanned amount
        m.type("42")
        assertEquals("₿42", m.model.value.keypad.amountDisplay)
        m.keypadConfirm()
        m.confirmSend()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(412_350L - 42L, core.balanceSats.value)
    }

    // --- Health model (AE5) ---

    @Test
    fun readyShowsTheWordAndNoBanner() = runTest {
        val m = machine()
        with(m.model.value.health) {
            assertTrue(wordVisible)
            assertEquals("Ready", word)
            assertEquals("#6FE3A8", dotColorHex)
            assertNull(banner)
        }
    }

    @Test
    fun tidyingPresentsAsReady() = runTest {
        val m = machine()
        m.forceHealth(HealthState.TIDYING)
        with(m.model.value.health) {
            assertTrue(wordVisible)
            assertEquals("Ready", word)
            assertNull(banner)
        }
    }

    @Test
    fun staleShowsBannerAndSuppressesTheWord() = runTest {
        val m = machine()
        m.forceHealth(HealthState.STALE)
        with(m.model.value.health) {
            assertFalse(wordVisible)
            assertEquals("#FF7A4D", dotColorHex)
            assertEquals("Your wallet needs a moment", assertNotNull(banner).title)
            assertFalse(offline)
            assertEquals("Get it done", actionLabel)
        }
    }

    @Test
    fun offlineShowsBannerAndOfflineTint() = runTest {
        val m = machine()
        m.forceHealth(HealthState.OFFLINE)
        with(m.model.value.health) {
            assertFalse(wordVisible)
            assertEquals("Can’t reach the network", assertNotNull(banner).title)
            assertTrue(offline)
        }
    }

    // --- Refresh ---

    @Test
    fun runRefreshFromStaleLandsHomeReady() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.forceHealth(HealthState.STALE)
        m.push(Route.HEALTH)
        m.runRefresh()
        assertEquals(Route.SENDING, m.model.value.route)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Route.HOME, m.model.value.route)
        assertEquals(HealthState.READY, core.health.value)
        assertTrue(m.model.value.health.wordVisible)
        assertFalse(m.model.value.canGoBack)
    }

    // --- Backup (AE4) ---

    @Test
    fun revealedWordsRehideAfterSixtySeconds() = runTest {
        val m = machine()
        m.push(Route.BACKUP)
        assertFalse(m.model.value.backup.revealed)
        m.revealWords()
        assertTrue(m.model.value.backup.revealed)
        assertEquals(60, m.model.value.backup.countdown)
        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(m.model.value.backup.revealed)
        assertEquals(1, m.model.value.backup.countdown)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(m.model.value.backup.revealed)
        assertEquals(60, m.model.value.backup.countdown)
    }

    @Test
    fun revealAgainRestartsTheCountdown() = runTest {
        val m = machine()
        m.push(Route.BACKUP)
        m.revealWords()
        advanceTimeBy(30_000)
        runCurrent()
        m.revealWords()
        advanceTimeBy(59_000)
        runCurrent()
        assertTrue(m.model.value.backup.revealed)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(m.model.value.backup.revealed)
    }

    @Test
    fun finishBackupMarksBackedUpAndPops() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.go(Route.SETTINGS)
        m.push(Route.BACKUP)
        assertEquals("Not done yet", m.model.value.backup.statusLabel)
        assertFalse(m.model.value.backup.backedUp)
        m.finishBackup()
        assertEquals(Route.SETTINGS, m.model.value.route)
        assertTrue(m.model.value.backup.backedUp)
        assertEquals("Done", m.model.value.backup.statusLabel)
        assertTrue(core.backedUp.value)
    }

    @Test
    fun countdownDerivesFromTheInjectedClockNotTheTickCount() = runTest {
        // Issue #2: wall-clock suspension must not extend the reveal window. The clock jumps
        // 45s while only one tick runs — the countdown must reflect the clock, not the ticks.
        var now = 0L
        val m = machine(nowMillis = { now })
        m.push(Route.BACKUP)
        m.revealWords()
        now = 45_000L // the app was suspended: real time passed with no ticks running
        advanceTimeBy(1_000) // first tick after resume
        runCurrent()
        assertTrue(m.model.value.backup.revealed)
        assertEquals(15, m.model.value.backup.countdown)
    }

    @Test
    fun clockJumpPastTheDeadlineHidesOnTheNextTick() = runTest {
        var now = 0L
        val m = machine(nowMillis = { now })
        m.push(Route.BACKUP)
        m.revealWords()
        now = 61_000L // suspended past the deadline
        advanceTimeBy(1_000) // first tick after resume
        runCurrent()
        assertFalse(m.model.value.backup.revealed)
        assertEquals(60, m.model.value.backup.countdown)
    }

    @Test
    fun finishBackupCancelsTheRevealCountdown() = runTest {
        // Issue #3: leaving via "I've written them down" must hide the words and stop the timer.
        val m = machine()
        m.go(Route.SETTINGS)
        m.push(Route.BACKUP)
        m.revealWords()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(40, m.model.value.backup.countdown)
        m.finishBackup()
        m.push(Route.BACKUP) // re-enter: words must be hidden with a fresh countdown
        assertFalse(m.model.value.backup.revealed)
        assertEquals(60, m.model.value.backup.countdown)
        val settled = m.model.value
        advanceTimeBy(120_000) // a stale countdown job must not mutate anything further
        runCurrent()
        assertEquals(settled, m.model.value)
    }

    @Test
    fun backupModelCarriesTheTwelveWords() = runTest {
        val m = machine()
        assertEquals(12, m.model.value.backup.words.size)
        assertEquals("tide", m.model.value.backup.words.first())
    }

    // --- Receive ---

    @Test
    fun copyCodeFlipsCopiedAndClearsAfter1600Millis() = runTest {
        val m = machine()
        m.go(Route.RECEIVE)
        assertEquals("Copy", m.model.value.receive.copyLabel)
        m.copyCode()
        assertTrue(m.model.value.receive.copied)
        assertEquals("Copied", m.model.value.receive.copyLabel)
        advanceTimeBy(1_600)
        runCurrent()
        assertFalse(m.model.value.receive.copied)
        assertEquals("Copy", m.model.value.receive.copyLabel)
    }

    @Test
    fun receiveModelCarriesTheCode() = runTest {
        val m = machine()
        assertEquals("ark1qf7…lark.money", m.model.value.receive.code)
    }

    // --- Transaction detail ---

    @Test
    fun openSentTxShowsSentTo() = runTest {
        val m = machine()
        m.go(Route.ACTIVITY)
        m.openTx(0) // Maya, -14,200
        assertEquals(Route.TX_DETAIL, m.model.value.route)
        with(m.model.value.txDetail) {
            assertEquals("Sent", verb)
            assertEquals("To", partyLabel)
            assertEquals("Maya", party)
            assertEquals("₿14,200", amount)
            assertEquals("$14.20", secondaryAmount)
            assertEquals("2 hours ago", whenLabel)
            assertEquals("None", fee)
        }
    }

    @Test
    fun openReceivedTxShowsReceivedFrom() = runTest {
        val m = machine()
        m.go(Route.ACTIVITY)
        m.openTx(2) // Jack, +250,000
        with(m.model.value.txDetail) {
            assertEquals("Received", verb)
            assertEquals("From", partyLabel)
            assertEquals("Jack", party)
            assertEquals("+₿250,000", amount)
            assertEquals("$250.00", secondaryAmount)
        }
    }

    @Test
    fun txDetailFollowsTheDenomination() = runTest {
        val m = machine()
        m.openTx(0)
        m.toggleUnit()
        with(m.model.value.txDetail) {
            assertEquals("$14.20", amount)
            assertEquals("₿14,200", secondaryAmount)
        }
    }

    // --- Demo controls ---

    @Test
    fun forceHealthAppliesAndGoesHome() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        m.push(Route.SETTINGS)
        m.push(Route.ADVANCED)
        m.forceHealth(HealthState.STALE)
        assertEquals(Route.HOME, m.model.value.route)
        assertEquals(HealthState.STALE, core.health.value)
        assertFalse(m.model.value.canGoBack)
    }

    @Test
    fun demoHealthOptionsListTheFourStates() = runTest {
        val m = machine()
        val options = assertNotNull(m.model.value.demoHealth)
        assertEquals(
            listOf("Ready", "Refreshing", "Needs a moment", "Offline"),
            options.map { it.label },
        )
        assertEquals(
            listOf("Steady state", "Silent refresh (invisible)", "Away too long", "Server unreachable"),
            options.map { it.note },
        )
        assertTrue(options.single { it.state == HealthState.READY }.selected)
        m.forceHealth(HealthState.OFFLINE)
        val updated = assertNotNull(m.model.value.demoHealth)
        assertTrue(updated.single { it.state == HealthState.OFFLINE }.selected)
        assertFalse(updated.single { it.state == HealthState.READY }.selected)
    }

    @Test
    fun withoutDemoControlsForceHealthIsANoOpAndOptionsAreAbsent() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core, withDemo = false)
        assertNull(m.model.value.demoHealth)
        m.push(Route.SETTINGS)
        m.forceHealth(HealthState.OFFLINE)
        assertEquals(Route.SETTINGS, m.model.value.route)
        assertEquals(HealthState.READY, core.health.value)
    }

    // --- Advanced stats passthrough ---

    @Test
    fun advancedStatsFollowTheHealthState() = runTest {
        val m = machine()
        assertEquals("4 hours ago", m.model.value.advanced.funds.lastRefresh)
        m.forceHealth(HealthState.STALE)
        assertEquals("38 days ago", m.model.value.advanced.funds.lastRefresh)
    }

    // --- Core push seam: emissions outside any machine intent must reach the model ---
    // Note: TestScope.advanceUntilIdle() only drives until no *foreground* work remains, and
    // machine coroutines live on backgroundScope — runCurrent()/advanceTimeBy() drive them.

    @Test
    fun coreHealthChangeOutsideAnIntentReachesTheModel() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        core.forceHealth(HealthState.OFFLINE) // directly on the core; no machine intent
        runCurrent()
        assertTrue(m.model.value.health.offline)
        assertEquals("Can’t reach the network", assertNotNull(m.model.value.health.banner).title)
    }

    @Test
    fun coreBalanceChangeOutsideAnIntentReachesTheModel() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        backgroundScope.launch { core.send("jack@lark.money", 520) } // no machine intent
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("₿411,830", m.model.value.balance.primary)
    }

    @Test
    fun coreBackedUpChangeOutsideAnIntentReachesTheModel() = runTest {
        val core = FakeLarkCore(startWithWallet = true)
        val m = machine(core = core)
        core.markBackedUp() // directly on the core; no machine intent
        runCurrent()
        assertTrue(m.model.value.backup.backedUp)
        assertEquals("Done", m.model.value.backup.statusLabel)
    }
}

/** Advanced channel rows (plan U5): the bridge summary, per-channel rows, and the footer label. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStateMachineChannelsTest {

    private fun TestScope.channelsMachine(core: LarkCore): AppStateMachine =
        AppStateMachine(core = core, demo = null, scope = backgroundScope)

    @Test
    fun nullSnapshotPassesTheCoreBridgeStringThroughUnchanged() = runTest {
        // The demo core never fetches channels; its bridge row stays the fake's own string.
        val m = machine()
        assertEquals("Open · 2 peers", m.model.value.channels.bridgeValue)
        assertTrue(m.model.value.channels.rows.isEmpty())
    }

    @Test
    fun nullSnapshotRendersTheEmDashPlaceholderExactlyAsToday() = runTest {
        // The stock gateway's not-exposed placeholder — the same "—" GatewayLarkCoreDataTest pins.
        val m = channelsMachine(ChannelsCore(bridgePlaceholder = "—"))
        assertEquals("—", m.model.value.channels.bridgeValue)
        assertTrue(m.model.value.channels.rows.isEmpty())
    }

    @Test
    fun polledAndEmptySnapshotRendersZeroChannels() = runTest {
        val core = ChannelsCore()
        val m = channelsMachine(core)
        core.channelsFlow.value = ChannelsSnapshot(channels = emptyList())
        runCurrent() // a core emission outside any intent must reach the model
        assertEquals("0 channels", m.model.value.channels.bridgeValue)
        assertTrue(m.model.value.channels.rows.isEmpty())
    }

    @Test
    fun twoChannelsRenderTheBridgeSummaryAndOneRowEach() = runTest {
        val core = ChannelsCore()
        val m = channelsMachine(core)
        core.channelsFlow.value = TWO_CHANNELS
        runCurrent()
        with(m.model.value.channels) {
            // R7: the bridge total is the sum of the visible rows.
            assertEquals("2 channels · ₿170,000", bridgeValue)
            assertEquals(2, rows.size)
            assertEquals("1230abc…f00d456", rows[0].shortId)
            assertEquals("₿120,000 of ₿200,000 · usable", rows[0].value)
            assertEquals("block 918,402 · in 27 days", rows[0].expiryLabel)
            assertEquals("9a8b7c6…d5e4f31", rows[1].shortId)
            assertEquals("₿50,000 of ₿1,000,000 · opening", rows[1].value)
            assertEquals("—", rows[1].expiryLabel) // absent expiry_height stays the em-dash
        }
    }

    @Test
    fun singleUnusableChannelRendersSingularCountAndUnusableLabel() = runTest {
        val core = ChannelsCore()
        val m = channelsMachine(core)
        core.channelsFlow.value = ChannelsSnapshot(
            channels = listOf(
                ChannelDisplay(
                    shortId = "aaa…bbb",
                    localSat = 1_000L,
                    capacitySat = 2_000L,
                    state = ChannelState.UNUSABLE,
                    expiryLabel = "—",
                ),
            ),
        )
        runCurrent()
        with(m.model.value.channels) {
            assertEquals("1 channel · ₿1,000", bridgeValue)
            assertEquals("₿1,000 of ₿2,000 · unusable", rows.single().value)
        }
    }

    @Test
    fun hiddenBalanceMasksChannelAmountsButNotCountsOrExpiry() = runTest {
        val core = ChannelsCore()
        val m = channelsMachine(core)
        core.channelsFlow.value = TWO_CHANNELS
        runCurrent()
        m.toggleBalance()
        with(m.model.value.channels) {
            assertEquals("2 channels · ••••", bridgeValue)
            assertEquals("•••• of •••• · usable", rows[0].value)
            assertEquals("block 918,402 · in 27 days", rows[0].expiryLabel)
        }
        m.toggleBalance()
        assertEquals("2 channels · ₿170,000", m.model.value.channels.bridgeValue)
    }

    @Test
    fun forkConfiguredGatewayCoreRendersTheInjectedFooterLabel() = runTest {
        // R5 decoupling: the fork identifies as signet on the wire; the footer stays mutinynet.
        val core = gatewayCore(
            engine = barkdEngine(BarkdScript(BarkdScript.forkDefaults)),
            variant = BarkdApiVariant.FORK_BETA6,
            expectedNetwork = "signet",
            networkLabel = "mutinynet",
        )
        val m = channelsMachine(core)
        assertEquals("mutinynet", m.model.value.networkLabel)
    }
}

/** Two display channels whose locals (120k + 50k) deliberately do not sum to the snapshot total. */
private val TWO_CHANNELS = ChannelsSnapshot(
    channels = listOf(
        ChannelDisplay(
            shortId = "1230abc…f00d456",
            localSat = 120_000L,
            capacitySat = 200_000L,
            state = ChannelState.USABLE,
            expiryLabel = "block 918,402 · in 27 days",
        ),
        ChannelDisplay(
            shortId = "9a8b7c6…d5e4f31",
            localSat = 50_000L,
            capacitySat = 1_000_000L,
            state = ChannelState.OPENING,
            expiryLabel = "—",
        ),
    ),
)

/**
 * A [FakeLarkCore]-backed core with a test-controlled channels flow (the fake itself stays null
 * forever) and an optionally overridden Lightning-bridge placeholder string, so machine tests can
 * pin the gateway's em-dash without wiring a full harness.
 */
private class ChannelsCore(
    private val bridgePlaceholder: String? = null,
    private val fake: FakeLarkCore = FakeLarkCore(startWithWallet = true),
) : LarkCore by fake {
    val channelsFlow = MutableStateFlow<ChannelsSnapshot?>(null)
    override val channels: StateFlow<ChannelsSnapshot?> = channelsFlow

    override fun advancedStats(): AdvancedStats {
        val stats = fake.advancedStats()
        val placeholder = bridgePlaceholder ?: return stats
        return stats.copy(network = stats.network.copy(lightningBridge = placeholder))
    }
}

/** A minimal [LarkCore] with an empty payment history, which the contract permits. */
private class EmptyHistoryCore : LarkCore {
    private val walletExistsFlow = MutableStateFlow(false)
    override val walletExists: StateFlow<Boolean> = walletExistsFlow
    override val balanceSats: StateFlow<Long> = MutableStateFlow(0L)
    override val fiatRate: FiatRate = FiatRate(satsPerCent = 10L)
    override val health: StateFlow<HealthState> = MutableStateFlow(HealthState.READY)
    override val backedUp: StateFlow<Boolean> = MutableStateFlow(false)
    override val activity: List<Transaction> = emptyList()
    override val recents: List<Contact> = emptyList()
    override val backupWords: List<String> = emptyList()
    override val receiveCode: String = ""
    override val depositAddress: String = ""
    override val networkLabel: String = ""

    override fun createWallet() {
        walletExistsFlow.value = true
    }

    override fun restoreWallet() {
        walletExistsFlow.value = true
    }

    override fun markBackedUp() = Unit

    override fun advancedStats(): AdvancedStats = AdvancedStats(
        funds = FundsStats(
            vtxoCount = 0,
            vtxoTotalSats = 0L,
            soonestExpiry = "",
            lastRefresh = "",
            onChainReserveSats = 0L,
            depositAddress = "",
        ),
        network = NetworkStats(arkServerStatus = "", nextRound = "", lightningBridge = "", chainTip = 0L),
    )

    override suspend fun refresh() = Unit

    override suspend fun send(recipient: String, sats: Long): SendResult = SendResult.Success
}
