package xyz.lark.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/** CompositionLocal carrying the LARK text roles; provided by [LarkTheme]. */
val LocalLarkTypography = staticCompositionLocalOf<LarkTypography> {
    error("LarkTypography not provided — wrap the UI in LarkTheme {}")
}

/** Accessors for the LARK design language, valid inside [LarkTheme]. */
object LarkTheme {
    val typography: LarkTypography
        @Composable get() = LocalLarkTypography.current

    val colors: LarkColors get() = LarkColors
}

/**
 * Applies the LARK Wallet design language. Material3 stays as the substrate with a
 * dark color scheme and typography overridden from [LarkColors] and [LarkTypography].
 */
@Composable
fun LarkTheme(content: @Composable () -> Unit) {
    val typography = larkTypography()
    MaterialTheme(
        colorScheme = LarkColorScheme,
        typography = materialTypography(typography),
    ) {
        CompositionLocalProvider(
            LocalLarkTypography provides typography,
            LocalContentColor provides LarkColors.TextPrimary,
            content = content,
        )
    }
}

private val LarkColorScheme = darkColorScheme(
    primary = LarkColors.Gold,
    onPrimary = LarkColors.OnGold,
    secondary = LarkColors.SurfacePressed,
    onSecondary = LarkColors.TextPrimary,
    tertiary = LarkColors.Success,
    onTertiary = LarkColors.OnGold,
    background = LarkColors.Background,
    onBackground = LarkColors.TextPrimary,
    surface = LarkColors.Surface,
    onSurface = LarkColors.TextPrimary,
    surfaceVariant = LarkColors.SurfacePressed,
    onSurfaceVariant = LarkColors.TextSecondary,
    outline = LarkColors.BorderStrong,
    outlineVariant = LarkColors.Border,
    error = LarkColors.Warning,
    onError = LarkColors.OnWarning,
)

/** Maps the LARK text roles onto the Material3 typography substrate. */
private fun materialTypography(lark: LarkTypography): Typography {
    val base = Typography()
    val bricolage = lark.screenTitle.fontFamily ?: FontFamily.Default
    val manrope = lark.body.fontFamily ?: FontFamily.Default
    return Typography(
        displayLarge = lark.displayHero,
        displayMedium = lark.amountDisplay,
        displaySmall = lark.balance,
        headlineLarge = lark.screenTitle,
        headlineMedium = base.headlineMedium.copy(fontFamily = bricolage),
        headlineSmall = base.headlineSmall.copy(fontFamily = bricolage),
        titleLarge = base.titleLarge.copy(fontFamily = manrope),
        titleMedium = lark.sectionTitle,
        titleSmall = lark.itemTitle,
        bodyLarge = lark.body,
        bodyMedium = lark.label,
        bodySmall = lark.bodySmall,
        labelLarge = lark.button,
        labelMedium = lark.caption,
        labelSmall = lark.eyebrow,
    )
}
