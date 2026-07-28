package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.KeypadModel
import xyz.lark.app.state.SendModel
import xyz.lark.app.ui.components.KeyValueRow
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val ReviewCenterGap = 26.dp
private val AmountBlockGap = 10.dp
private val PayCtaHeight = 64.dp

/**
 * Pay — review (spec block `data-screen-label="Pay — review"`): back chevron, the
 * centered "Paying {who}" amount block, the Arrives/Fee card, and the gold
 * "Pay {amount}" pill confirming the send.
 */
@Composable
fun ReviewScreen(
    keypad: KeypadModel,
    send: SendModel,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
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
        ScreenBackButton(onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(ReviewCenterGap, Alignment.CenterVertically),
        ) {
            AmountBlock(keypad = keypad, recipientName = send.recipientName)
            SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                KeyValueRow(label = "Arrives", value = "Instantly")
                RowGroupDivider()
                KeyValueRow(label = "Fee", value = "None")
            }
        }
        PayCta(text = "Pay ${keypad.amountDisplay}", onClick = onConfirm)
    }
}

/** "Paying {who}", the 62sp amount, and the secondary amount, 10dp apart. */
@Composable
private fun AmountBlock(keypad: KeypadModel, recipientName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AmountBlockGap)) {
        Text(
            text = "Paying $recipientName",
            style = reviewMetaStyle(),
            color = LarkColors.TextTertiary,
        )
        Text(
            text = keypad.amountDisplay,
            style = LarkTheme.typography.amountDisplay.copy(fontSize = 62.sp, lineHeight = 62.sp),
            color = LarkColors.TextPrimary,
        )
        Text(
            text = keypad.amountSecondary,
            style = reviewMetaStyle().copy(fontFeatureSettings = TABULAR_NUMERALS),
            color = LarkColors.TextTertiary,
        )
    }
}

/** Manrope 500 16/1 — the meta lines around the review amount. */
@Composable
private fun reviewMetaStyle() = LarkTheme.typography.itemTitle.copy(
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 16.sp,
)

/** The 64dp gold confirm pill with the spec's larger 18sp label. */
@Composable
private fun PayCta(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PayCtaHeight)
            .clip(CircleShape)
            .background(if (pressed) LarkColors.GoldPressed else LarkColors.Gold)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = LarkTheme.typography.button.copy(fontSize = 18.sp, lineHeight = 18.sp),
            color = LarkColors.OnGold,
        )
    }
}
