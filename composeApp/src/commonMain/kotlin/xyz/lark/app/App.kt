package xyz.lark.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import xyz.lark.app.ui.screens.activity.ActivityScreen
import xyz.lark.app.ui.screens.activity.TxDetailScreen
import xyz.lark.app.ui.screens.activity.TxTechScreen
import xyz.lark.app.ui.screens.home.HomeScreen
import xyz.lark.app.ui.screens.onboarding.BoardingScreen
import xyz.lark.app.ui.screens.onboarding.FundScreen
import xyz.lark.app.ui.screens.onboarding.HowItWorksScreen
import xyz.lark.app.ui.screens.onboarding.RestoreScreen
import xyz.lark.app.ui.screens.onboarding.WelcomeScreen
import xyz.lark.app.ui.screens.pay.AmountScreen
import xyz.lark.app.ui.screens.pay.FailedScreen
import xyz.lark.app.ui.screens.pay.ReviewScreen
import xyz.lark.app.ui.screens.pay.ScanScreen
import xyz.lark.app.ui.screens.pay.SendInputScreen
import xyz.lark.app.ui.screens.pay.SendingScreen
import xyz.lark.app.ui.screens.pay.SentScreen
import xyz.lark.app.ui.screens.receive.ReceiveScreen
import xyz.lark.app.ui.theme.LarkTheme

/**
 * Composition root: instantiates the demo engine and the app-wide state machine (the single
 * place a real core slots in later), applies [LarkTheme], and renders the current route.
 */
@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val machine = remember(scope) {
        val core = FakeLarkCore()
        AppStateMachine(core = core, demo = core, scope = scope)
    }
    LarkTheme {
        val model by machine.model.collectAsState()
        PlatformBackHandler(enabled = model.canGoBack, onBack = machine::back)
        ScreenHost(model = model, machine = machine)
    }
}

/**
 * The one place a route becomes a screen. U4–U8 swap each placeholder for the real
 * screen composable; [machine] is already available here for their intents.
 */
@Suppress("CyclomaticComplexMethod", "UnusedParameter") // one branch per route by design
@Composable
private fun ScreenHost(model: AppModel, machine: AppStateMachine) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LarkTheme.colors.Background),
        contentAlignment = Alignment.Center,
    ) {
        when (model.route) {
            Route.WELCOME -> WelcomeScreen(
                onSetUpWallet = machine::goHowItWorks,
                onRestore = machine::goRestore,
            )
            Route.HOW_IT_WORKS -> HowItWorksScreen(
                onBack = machine::back,
                onContinue = machine::goFund,
            )
            Route.FUND -> FundScreen(
                onBack = machine::back,
                onMoveBitcoinIn = machine::startBoarding,
                onBuyWithCard = machine::startBoarding,
                onLater = machine::finishOnboarding,
            )
            Route.BOARDING -> BoardingScreen(onSkip = machine::finishOnboarding)
            Route.RESTORE -> RestoreScreen(
                onBack = machine::back,
                onRestore = machine::finishRestore,
            )
            Route.HOME -> HomeScreen(model = model, machine = machine)
            Route.ACTIVITY -> ActivityScreen(model = model, machine = machine)
            Route.TX_DETAIL -> TxDetailScreen(
                model = model.txDetail,
                onBack = machine::back,
                onTechnicalDetails = { machine.push(Route.TX_TECH) },
            )
            Route.TX_TECH -> TxTechScreen(onBack = machine::back)
            Route.SEND_INPUT -> SendInputScreen(model = model, machine = machine)
            Route.SCAN -> ScanScreen(onClose = machine::back, onScanFound = machine::scanFound)
            Route.AMOUNT -> AmountScreen(keypad = model.keypad, machine = machine)
            Route.REVIEW -> ReviewScreen(
                keypad = model.keypad, send = model.send,
                onBack = machine::back, onConfirm = machine::confirmSend,
            )
            Route.SENDING -> SendingScreen()
            Route.SENT -> SentScreen(
                amount = model.keypad.amountDisplay,
                recipientName = model.send.recipientName,
                onDone = { machine.go(Route.HOME) },
            )
            Route.FAILED -> FailedScreen(onTryAgain = machine::tryAgain, onCancel = { machine.go(Route.HOME) })
            Route.RECEIVE -> ReceiveRoute(model = model, machine = machine)
            Route.SETTINGS -> PlaceholderScreen(model.screenLabel)
            Route.BACKUP -> PlaceholderScreen(model.screenLabel)
            Route.HEALTH -> PlaceholderScreen(model.screenLabel)
            Route.ADVANCED -> PlaceholderScreen(model.screenLabel)
            Route.EXIT -> PlaceholderScreen(model.screenLabel)
        }
    }
}

/** The RECEIVE branch: binds [ReceiveScreen]'s callbacks to the machine's intents. */
@Composable
private fun ReceiveRoute(model: AppModel, machine: AppStateMachine) = ReceiveScreen(
    receive = model.receive,
    onBack = machine::back,
    onCopy = machine::copyCode,
    onSetAmount = machine::goReceiveAmount,
)

/** Minimal stand-in until the route's real screen lands (U4–U8). */
@Composable
private fun PlaceholderScreen(label: String) {
    Text(
        text = label,
        style = LarkTheme.typography.screenTitle,
        color = LarkTheme.colors.TextPrimary,
        textAlign = TextAlign.Center,
    )
}
