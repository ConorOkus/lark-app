package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val FailedCenterGap = 22.dp
private val FailedCircleSize = 76.dp
private val FailedCloseSize = 34.dp
private val FailedSubGap = 12.dp
private val FailedSubMaxWidth = 280.dp
private val ActionsGap = 10.dp
private val TryAgainHeight = 60.dp
private val CancelHeight = 52.dp

private const val CIRCLE_BG_ALPHA = 0.16f

/**
 * Pay — failed (spec block `data-screen-label="Pay — failed"`): the warning-tinted
 * close circle, the "Didn't go through." headline with its reassurance line, and the
 * Try again / Cancel action column (AE1).
 */
@Composable
fun FailedScreen(
    onTryAgain: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = PayHorizontalPadding,
                top = PayTopPadding,
                end = PayHorizontalPadding,
                bottom = PayBottomPadding,
            ),
    ) {
        FailedCenter(modifier = Modifier.fillMaxWidth().weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(ActionsGap)) {
            GoldPillButton(
                text = "Try again",
                onClick = onTryAgain,
                modifier = Modifier.fillMaxWidth(),
                height = TryAgainHeight,
            )
            CancelButton(onClick = onCancel)
        }
    }
}

/** The centered failure mark, headline, and reassurance copy. */
@Composable
private fun FailedCenter(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(FailedCenterGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(FailedCircleSize)
                .clip(CircleShape)
                .background(LarkColors.Warning.copy(alpha = CIRCLE_BG_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LarkIcons.Close,
                contentDescription = null,
                modifier = Modifier.size(FailedCloseSize),
                tint = LarkColors.Warning,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Didn’t go\nthrough.",
                style = LarkTheme.typography.screenTitle.copy(
                    fontSize = 40.sp,
                    lineHeight = 42.sp,
                    letterSpacing = (-0.04).em,
                ),
                color = LarkColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Nothing left your wallet. Your balance is unchanged.",
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                ),
                color = LarkColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = FailedSubMaxWidth)
                    .padding(top = FailedSubGap),
            )
        }
    }
}

/** The 52dp ghost Cancel action under Try again. */
@Composable
private fun CancelButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CancelHeight)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Cancel",
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 15.sp,
            ),
            color = LarkColors.TextSecondary,
        )
    }
}
