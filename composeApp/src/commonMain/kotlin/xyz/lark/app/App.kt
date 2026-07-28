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
            Route.WELCOME -> PlaceholderScreen(model.screenLabel)
            Route.HOW_IT_WORKS -> PlaceholderScreen(model.screenLabel)
            Route.FUND -> PlaceholderScreen(model.screenLabel)
            Route.BOARDING -> PlaceholderScreen(model.screenLabel)
            Route.RESTORE -> PlaceholderScreen(model.screenLabel)
            Route.HOME -> PlaceholderScreen(model.screenLabel)
            Route.ACTIVITY -> PlaceholderScreen(model.screenLabel)
            Route.TX_DETAIL -> PlaceholderScreen(model.screenLabel)
            Route.TX_TECH -> PlaceholderScreen(model.screenLabel)
            Route.SEND_INPUT -> PlaceholderScreen(model.screenLabel)
            Route.SCAN -> PlaceholderScreen(model.screenLabel)
            Route.AMOUNT -> PlaceholderScreen(model.screenLabel)
            Route.REVIEW -> PlaceholderScreen(model.screenLabel)
            Route.SENDING -> PlaceholderScreen(model.screenLabel)
            Route.SENT -> PlaceholderScreen(model.screenLabel)
            Route.FAILED -> PlaceholderScreen(model.screenLabel)
            Route.RECEIVE -> PlaceholderScreen(model.screenLabel)
            Route.SETTINGS -> PlaceholderScreen(model.screenLabel)
            Route.BACKUP -> PlaceholderScreen(model.screenLabel)
            Route.HEALTH -> PlaceholderScreen(model.screenLabel)
            Route.ADVANCED -> PlaceholderScreen(model.screenLabel)
            Route.EXIT -> PlaceholderScreen(model.screenLabel)
        }
    }
}

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
