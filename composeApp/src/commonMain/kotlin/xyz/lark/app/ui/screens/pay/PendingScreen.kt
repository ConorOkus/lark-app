package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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

private val PendingCenterGap = 22.dp
private val PendingCircleSize = 76.dp
private val PendingGlyphSize = 34.dp
private val PendingSubGap = 12.dp
private val PendingSubMaxWidth = 300.dp
private val DoneButtonHeight = 60.dp

private const val CIRCLE_BG_ALPHA = 0.16f

/**
 * Pay — on its way: the landing for a payment that was accepted but had not settled before the
 * core stopped waiting ([xyz.lark.app.core.model.SendResult.Pending]).
 *
 * There is no such screen in the canonical design, because until now no send path could tell
 * acceptance from settlement. It borrows the sent screen's shape deliberately — same circle,
 * same headline scale, same Done pill — so it reads as a sibling outcome rather than an error.
 *
 * The copy is the whole point. It does not say "Sent.", because that has not been observed; it
 * does not say it failed, because the money may still be moving; and it promises no arrival
 * time, because none is known. Pending design review (see the plan's Open Questions).
 */
@Composable
fun PendingScreen(
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
        PendingCenter(
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

/** The centered in-flight mark, headline, amount line, and the honest status sentence. */
@Composable
private fun PendingCenter(amount: String, recipientName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PendingCenterGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(PendingCircleSize)
                .clip(CircleShape)
                .background(LarkColors.Gold.copy(alpha = CIRCLE_BG_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            // The outgoing glyph on a translucent circle, against the sent screen's solid
            // circle and check: money leaving, not money landed.
            Icon(
                imageVector = LarkIcons.ArrowUpRight,
                contentDescription = null,
                modifier = Modifier.size(PendingGlyphSize),
                tint = LarkColors.Gold,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "On its way.",
                style = LarkTheme.typography.screenTitle.copy(
                    fontSize = 40.sp,
                    lineHeight = 42.sp,
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
                modifier = Modifier.padding(top = PendingSubGap),
            )
            Text(
                text = "This payment hasn’t settled yet. Check Activity for the outcome.",
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                ),
                color = LarkColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = PendingSubMaxWidth)
                    .padding(top = PendingSubGap),
            )
        }
    }
}
