package xyz.lark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val AvatarSize = 38.dp

private const val GOLD_CIRCLE_ALPHA = 0.18f
private const val PLAIN_CIRCLE_ALPHA = 0.08f

/** The 38dp leading circle with a counterparty's initial; gold-tinted when [gold]. */
@Composable
fun InitialAvatar(initial: String, gold: Boolean, modifier: Modifier = Modifier) {
    val background = if (gold) {
        LarkColors.Gold.copy(alpha = GOLD_CIRCLE_ALPHA)
    } else {
        Color.White.copy(alpha = PLAIN_CIRCLE_ALPHA)
    }
    Box(
        modifier = modifier
            .size(AvatarSize)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = LarkTheme.typography.itemTitle.copy(fontSize = 15.sp, lineHeight = 15.sp),
            color = if (gold) LarkColors.Gold else LarkColors.TextPrimary,
        )
    }
}
