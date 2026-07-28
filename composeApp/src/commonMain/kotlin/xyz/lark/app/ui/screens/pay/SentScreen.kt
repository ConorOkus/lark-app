package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.OutlinePillButton
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val SentCenterGap = 22.dp
private val SentCircleSize = 76.dp
private val SentCheckSize = 38.dp
private val SentSubGap = 12.dp
private val DoneButtonHeight = 60.dp

/**
 * Pay — sent (spec block `data-screen-label="Pay — sent"`): the 76dp gold check
 * circle, the "Sent." headline with the amount/recipient line, and the outline
 * Done pill returning home.
 */
@Composable
fun SentScreen(
    amount: String,
    recipientName: String,
    onDone: () -> Unit,
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
        SentCenter(
            amount = amount,
            recipientName = recipientName,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        OutlinePillButton(
            text = "Done",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            height = DoneButtonHeight,
        )
    }
}

/** The centered gold check circle, "Sent." headline, and amount/recipient line. */
@Composable
private fun SentCenter(amount: String, recipientName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SentCenterGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(SentCircleSize)
                .clip(CircleShape)
                .background(LarkColors.Gold),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LarkIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(SentCheckSize),
                tint = LarkColors.OnGold,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Sent.",
                style = LarkTheme.typography.screenTitle.copy(
                    fontSize = 44.sp,
                    lineHeight = 44.sp,
                    letterSpacing = (-0.04).em,
                ),
                color = LarkColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "$amount to $recipientName",
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                ),
                color = LarkColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = SentSubGap),
            )
        }
    }
}
