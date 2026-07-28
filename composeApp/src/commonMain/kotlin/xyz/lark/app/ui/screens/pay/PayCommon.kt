package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.theme.LarkColors

/** Shared metrics of the pay-flow `sc-if` blocks (`padding:56px 20px 40px`). */
internal val PayTopPadding: Dp = 56.dp
internal val PayHorizontalPadding: Dp = 20.dp
internal val PayBottomPadding: Dp = 40.dp

/** The 44x44 icon tap targets sit 10dp into the margin (the spec's `margin:0 -10px`). */
internal val PayIconButtonSize: Dp = 44.dp
internal val PayIconButtonInset: Dp = 10.dp

private val BackIconSize = 22.dp

/** A 44x44 transparent icon tap target of the pay screens (back, scan, close). */
@Suppress("LongParameterList") // mirrors the spec's per-button icon/size/tint knobs
@Composable
internal fun PayIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = BackIconSize,
    tint: Color = LarkColors.TextPrimary,
) {
    Box(
        modifier = modifier
            .size(PayIconButtonSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
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

/** The pay screens' back chevron, nudged 10dp into the leading margin. */
@Composable
internal fun PayBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    PayIconButton(
        icon = LarkIcons.BackChevron,
        contentDescription = "Back",
        onClick = onBack,
        modifier = modifier.offset(x = -PayIconButtonInset),
    )
}
