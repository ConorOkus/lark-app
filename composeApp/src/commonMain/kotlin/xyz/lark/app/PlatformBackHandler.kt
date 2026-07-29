package xyz.lark.app

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform back gesture while [enabled], forwarding it to [onBack]
 * (KTD-4: the system back gesture drives `AppStateMachine.back()`).
 *
 * Android implements this with activity-compose's `BackHandler`; iOS has no system-wide
 * back gesture to intercept, so its actual is a no-op.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
