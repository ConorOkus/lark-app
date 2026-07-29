package xyz.lark.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import xyz.lark.app.ui.screens.settings.AdvancedScreen
import xyz.lark.app.ui.screens.settings.BackupScreen
import xyz.lark.app.ui.screens.settings.ExitScreen
import xyz.lark.app.ui.screens.settings.HealthScreen
import xyz.lark.app.ui.screens.settings.SettingsScreen
import xyz.lark.app.ui.theme.LarkTheme

/**
 * The app-scoped object graph: the demo engine and the app-wide state machine (the single place
 * a real core slots in later). A top-level lazy singleton so it survives recomposition and
 * Android configuration changes (rotation must not reset the app to onboarding); first touch
 * happens on the main thread inside composition, so [Dispatchers.Main] is available. Cold launch
 * (fresh process) still starts at the resting route.
 */
private object AppGraph {
    val core: FakeLarkCore by lazy { FakeLarkCore() }
    val machine: AppStateMachine by lazy {
        AppStateMachine(core = core, demo = core, scope = CoroutineScope(SupervisorJob() + Dispatchers.Main))
    }
}

/**
 * Composition root: applies [LarkTheme] and renders the current route from the app-scoped
 * [AppGraph.machine].
 */
@Composable
fun App() {
    val machine = AppGraph.machine
    LarkTheme {
        val model by machine.model.collectAsState()
        PlatformBackHandler(enabled = model.canGoBack, onBack = machine::back)
        ScreenHost(model = model, machine = machine)
    }
}

/**
 * The one place a route becomes a screen: one branch per route, each binding a screen
 * composable to [model] slices and [machine] intents (via `*Route` binders where the
 * callback wiring would not fit on a branch line).
 */
@Suppress("CyclomaticComplexMethod") // one branch per route by design
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
                amount = model.sentAmount,
                recipientName = model.send.recipientName,
                onDone = { machine.go(Route.HOME) },
            )
            Route.FAILED -> FailedScreen(onTryAgain = machine::tryAgain, onCancel = { machine.go(Route.HOME) })
            Route.RECEIVE -> ReceiveRoute(model = model, machine = machine)
            Route.SETTINGS -> SettingsScreen(model = model, machine = machine)
            Route.BACKUP -> BackupRoute(model = model, machine = machine)
            Route.HEALTH -> HealthRoute(model = model, machine = machine)
            Route.ADVANCED -> AdvancedScreen(model = model, machine = machine)
            Route.EXIT -> ExitRoute(model = model, machine = machine)
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

/** The BACKUP branch: binds [BackupScreen]'s callbacks to the machine's intents. */
@Composable
private fun BackupRoute(model: AppModel, machine: AppStateMachine) = BackupScreen(
    backup = model.backup,
    onBack = machine::back,
    onReveal = machine::revealWords,
    onDone = machine::finishBackup,
)

/** The HEALTH branch: binds [HealthScreen]'s callbacks to the machine's intents. */
@Composable
private fun HealthRoute(model: AppModel, machine: AppStateMachine) = HealthScreen(
    health = model.health,
    onBack = machine::back,
    onRefresh = machine::runRefresh,
    onDetails = { machine.push(Route.ADVANCED) },
)

/** The EXIT branch: binds [ExitScreen]'s callbacks to the machine's intents. */
@Composable
private fun ExitRoute(model: AppModel, machine: AppStateMachine) = ExitScreen(
    amount = model.exitAmount, // always unmasked: the screen states what moves on-chain (issue #4)
    onBack = machine::back,
    onStart = { machine.go(Route.HOME) },
)
