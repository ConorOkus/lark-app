package xyz.lark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.colorFromHex

/** The 44x44 icon tap targets sit 10dp into the margin (the spec's `margin:0 -10px`). */
val IconButtonInset: Dp = 10.dp

private val IconButtonSize = 44.dp
private val BackIconSize = 22.dp

/** A 44x44 transparent circular icon tap target (back, scan, close). */
@Suppress("LongParameterList") // mirrors the spec's per-button icon/size/tint knobs
@Composable
fun LarkIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = IconButtonSize,
    iconSize: Dp = BackIconSize,
    tint: Color = LarkColors.TextPrimary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = tint,
        )
    }
}

/**
 * The screens' shared back affordance: a 44x44 tap target nudged 10dp into the leading
 * margin (the spec's `margin-left:-10px`) so the 22dp chevron aligns with the content edge.
 */
@Composable
fun ScreenBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    LarkIconButton(
        icon = LarkIcons.BackChevron,
        onClick = onBack,
        modifier = modifier.offset(x = -IconButtonInset),
        contentDescription = "Back",
    )
}

/** A round health dot in the state's spec color (`background:{{ healthDotColor }}`). */
@Composable
fun HealthDot(
    colorHex: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colorFromHex(colorHex)),
    )
}
