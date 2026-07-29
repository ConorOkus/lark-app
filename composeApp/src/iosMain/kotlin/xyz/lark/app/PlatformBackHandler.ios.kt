package xyz.lark.app

import androidx.compose.runtime.Composable

@Suppress("UnusedParameter") // iOS has no system back gesture to intercept; navigation is in-UI
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
