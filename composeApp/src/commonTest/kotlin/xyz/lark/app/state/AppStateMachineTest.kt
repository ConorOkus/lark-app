package xyz.lark.app.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.model.HealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Types each digit of [digits] on the keypad. */
private fun AppStateMachine.type(digits: String) = digits.forEach { keyPress(it) }

/** A machine driven by the test's background scope, so virtual time controls all timing. */
private fun TestScope.machine(
    core: FakeLarkCore = FakeLarkCore(startWithWallet = true),
    withDemo: Boolean = true,
): AppStateMachine = AppStateMachine(
    core = core,
    demo = if (withDemo) core else null,
    scope = backgroundScope,
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
    fun pasteInvoiceResolvesJack() = runTest {
        val m = machine()
        m.push(Route.SEND_INPUT)
        with(m.model.value.send) {
            assertEquals("Name, invoice or address", inputDisplay)
            assertFalse(inputResolved)
        }
        m.pasteInvoice()
        with(m.model.value.send) {
            assertEquals("jack@lark.money", inputDisplay)
            assertTrue(inputResolved)
            assertEquals("Jack", recipientName)
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
            assertEquals("520", keypad.digits)
            assertEquals("₿520", keypad.amountDisplay)
            assertEquals(KeypadMode.SEND, keypad.mode)
        }
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
}
