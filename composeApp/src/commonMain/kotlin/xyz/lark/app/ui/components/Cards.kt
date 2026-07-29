package xyz.lark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

/** Corner radius of surface cards. */
val CardCornerRadius = 16.dp

/** Corner radius of the large home action tiles. */
val TileCornerRadius = 20.dp

private val IconCircleSize = 44.dp
private val IconSize = 20.dp

/**
 * The base card of the design: #14161A surface, 16dp radius, 1dp rgba(255,255,255,.07)
 * border. Pass [onClick] to make it pressable (pressed surface #1C1F24). Use a
 * [shape] of [TileCornerRadius] for home action tiles.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(CardCornerRadius),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = if (onClick != null && pressed) LarkColors.SurfacePressed else LarkColors.Surface
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(width = 1.dp, color = LarkColors.Border, shape = shape)
            .then(clickModifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A pressable option/action card: leading icon in a subtle circle, title with
 * supporting line, trailing chevron.
 */
@Suppress("LongParameterList")
@Composable
fun OptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = LarkColors.TextPrimary,
) {
    SurfaceCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconCircle(icon = icon, tint = iconTint)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = title, style = LarkTheme.typography.itemTitle, color = LarkColors.TextPrimary)
                Text(text = subtitle, style = LarkTheme.typography.bodySmall, color = LarkColors.TextSecondary)
            }
            Icon(
                imageVector = LarkIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = LarkColors.TextFaint,
            )
        }
    }
}

/** The leading icon circle used by option/action cards and list rows. */
@Composable
fun IconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LarkColors.TextPrimary,
    background: Color = LarkColors.IconCircle,
) {
    Box(
        modifier = modifier
            .size(IconCircleSize)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconSize),
            tint = tint,
        )
    }
}
