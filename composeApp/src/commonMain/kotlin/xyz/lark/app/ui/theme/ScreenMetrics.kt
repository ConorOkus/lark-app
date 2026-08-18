package xyz.lark.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of a mockup's top padding the status bar band eats.
 *
 * The design frames in `docs/design/lark-wallet` measure their top padding from the top of the
 * device screen and draw no status bar, so a `56px` top value is meant to include the band the
 * clock lives in. The app draws below that band on both platforms (the iOS safe area, the
 * root's system-bar insets on Android), so taking those values literally stacked the two and
 * left every screen's first row sitting a status bar too low.
 */
private val StatusBarBand: Dp = 40.dp

/**
 * A design top padding, re-measured from below the status bar — what every screen should use in
 * place of the raw mockup value.
 */
internal fun Dp.belowStatusBar(): Dp = (this - StatusBarBand).coerceAtLeast(0.dp)
