package xyz.lark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

/** Height of the standard pill CTA. */
val PillButtonHeight: Dp = 60.dp

private const val DISABLED_ALPHA = 0.35f
private val PillPadding = 28.dp

@Immutable
private data class PillColors(val background: Color, val pressedBackground: Color)

/** The gold primary CTA — filled #E8C15C pill with on-gold text. One gold moment per screen. */
@Composable
fun GoldPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = PillButtonHeight,
) {
    PillSurface(
        onClick = onClick,
        colors = PillColors(background = LarkColors.Gold, pressedBackground = LarkColors.GoldPressed),
        modifier = modifier.height(height),
        enabled = enabled,
    ) {
        Text(text = text, style = LarkTheme.typography.button, color = LarkColors.OnGold)
    }
}

/** Secondary CTA — transparent pill with a 1dp rgba(255,255,255,.16) border. */
@Composable
fun OutlinePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = PillButtonHeight,
) {
    PillSurface(
        onClick = onClick,
        colors = PillColors(background = Color.Transparent, pressedBackground = LarkColors.SurfacePressed),
        modifier = modifier
            .height(height)
            .border(width = 1.dp, color = LarkColors.BorderStrong, shape = CircleShape),
        enabled = enabled,
    ) {
        Text(text = text, style = LarkTheme.typography.button, color = LarkColors.TextPrimary)
    }
}

/** Tertiary text-only action — no container, secondary-tier label. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = LarkColors.TextSecondary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .alpha(if (enabled && !pressed) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = LarkTheme.typography.itemTitle, color = color)
    }
}

@Composable
private fun PillSurface(
    onClick: () -> Unit,
    colors: PillColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = if (pressed) colors.pressedBackground else colors.background
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = PillPadding),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
