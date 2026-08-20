package xyz.lark.app.ui.screens.receive

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import xyz.lark.app.state.ReceiveModel
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.GoldSpinner
import xyz.lark.app.ui.components.OutlinePillButton
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.belowStatusBar

/** Shared metrics of the get-paid `sc-if` block (`padding:56px 20px 40px`). */
private val ReceiveTopPadding: Dp = 56.dp.belowStatusBar()
private val ReceiveHorizontalPadding: Dp = 20.dp
private val ReceiveBottomPadding: Dp = 40.dp

private val TopRowHeight = 44.dp
private val BackButtonSize = 44.dp

private val CenterTopGap = 14.dp
private val CenterGap = 20.dp
private val TextBlockMaxWidth = 300.dp
private val TextBlockGap = 6.dp

private val CodeBoxRadius = 14.dp
private val CodeBoxVerticalPadding = 14.dp
private val CodeBoxHorizontalPadding = 16.dp

private val CtaTopGap = 20.dp
private val CtaGap = 10.dp
private val CtaHeight = 56.dp

/** The white QR card (the spec's `padding:18px;border-radius:28px;background:#fff` wrapper). */
private val QrCardRadius = 28.dp
private val QrCardPadding = 18.dp
private val QrSize = 246.dp

/** The waiting spinner, sized between the 44dp default and the QR card it stands in for. */
private val WaitingSpinnerSize = 52.dp

private const val CODE_TEXT_ALPHA = 0.6f

/** What Get paid can do, bundled so the screen keeps a short parameter list. */
data class ReceiveActions(
    val onBack: () -> Unit,
    val onCopy: () -> Unit,
    val onToggleAmount: () -> Unit,
    val onRetry: () -> Unit,
)

/**
 * Get paid (spec block `data-screen-label="Get paid"`): back chevron + centered title,
 * the white QR card, the "One code, any wallet" text block, the mono code box, and
 * the Copy / Set amount pill pair.
 */
@Composable
fun ReceiveScreen(
    receive: ReceiveModel,
    actions: ReceiveActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = ReceiveHorizontalPadding,
                top = ReceiveTopPadding,
                end = ReceiveHorizontalPadding,
                bottom = ReceiveBottomPadding,
            ),
    ) {
        TopRow(onBack = actions.onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = CenterTopGap)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CenterGap, Alignment.CenterVertically),
        ) {
            if (receive.code == null) {
                WaitingForCode()
            } else {
                ReceiveQr(code = receive.code)
                RequestedAmount(amount = receive.requestedAmount)
                TextBlock()
                CodeBox(code = receive.code)
            }
        }
        // Without a code both ordinary actions are dead — there is nothing to copy and no code for
        // an amount to annotate — so the row gives way to the one action that can change anything.
        if (receive.code == null) {
            GoldPillButton(
                text = "Try again now",
                onClick = actions.onRetry,
                modifier = Modifier.fillMaxWidth().padding(top = CtaTopGap),
                enabled = !receive.retrying,
                height = CtaHeight,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = CtaTopGap),
                horizontalArrangement = Arrangement.spacedBy(CtaGap),
            ) {
                OutlinePillButton(
                    text = receive.copyLabel,
                    onClick = actions.onCopy,
                    modifier = Modifier.weight(1f),
                    height = CtaHeight,
                )
                GoldPillButton(
                    text = if (receive.requestedAmount == null) "Set amount" else "Any amount",
                    onClick = actions.onToggleAmount,
                    modifier = Modifier.weight(1f),
                    height = CtaHeight,
                )
            }
        }
    }
}

/** Back chevron on the left, the centered 600 16sp title, and a balancing 44dp spacer. */
@Composable
private fun TopRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(TopRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenBackButton(onBack = onBack)
        Text(
            text = "Get paid",
            modifier = Modifier.weight(1f),
            style = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 16.sp),
            color = LarkColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.width(BackButtonSize))
    }
}

/**
 * The 246dp receive-code QR inside its 18dp-padded white rounded card. Encodes EXACTLY
 * [code] — the same string the code box below displays (AE3's one-source rule).
 */
@Composable
private fun ReceiveQr(code: String) {
    Image(
        painter = rememberQrCodePainter(code),
        contentDescription = "Receive code QR",
        modifier = Modifier
            .clip(RoundedCornerShape(QrCardRadius))
            .background(Color.White)
            .padding(QrCardPadding)
            .size(QrSize),
    )
}

/**
 * The amount this code requests, when it requests one. Absent for the amountless code rather
 * than rendered as a zero — the code genuinely asks for any amount, and "₿0" would be a lie.
 */
@Composable
private fun RequestedAmount(amount: String?) {
    if (amount == null) return
    Text(
        text = "Requesting $amount",
        style = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
        color = LarkColors.TextPrimary,
        textAlign = TextAlign.Center,
    )
}

/**
 * What Get paid shows before the wallet has a code: the spinner, and what it is waiting on.
 *
 * It replaces a QR of the empty string. An empty code still encodes to a perfectly scannable QR,
 * so the screen used to hand out a code that resolved to nothing and looked exactly like a working
 * one — a payment aimed at it simply never arrived.
 *
 * Deliberately says what it is doing and not why it is failing. The one fact available here is
 * that there is no code and the wallet keeps asking; whether the network is down, slow, or three
 * seconds into a cold start is not something this screen knows, and a "can't reach the network"
 * headline would be a guess dressed as a diagnosis on a fresh launch.
 */
@Composable
private fun WaitingForCode() {
    GoldSpinner(size = WaitingSpinnerSize)
    Column(
        modifier = Modifier.widthIn(max = TextBlockMaxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TextBlockGap),
    ) {
        Text(
            text = "Getting your code",
            style = LarkTheme.typography.itemTitle.copy(fontSize = 18.sp, lineHeight = 23.sp),
            color = LarkColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "This needs the network. LARK keeps trying, and your code appears here the " +
                "moment it lands.",
            style = LarkTheme.typography.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = LarkColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** "One code, any wallet" headline and its 14sp explainer, centered, max 300dp wide. */
@Composable
private fun TextBlock() {
    Column(
        modifier = Modifier.widthIn(max = TextBlockMaxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TextBlockGap),
    ) {
        Text(
            text = "One code, any wallet",
            style = LarkTheme.typography.itemTitle.copy(fontSize = 18.sp, lineHeight = 23.sp),
            color = LarkColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Works from any bitcoin or Lightning wallet. " +
                "Money lands the moment they send it.",
            style = LarkTheme.typography.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = LarkColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** The full-width surface box carrying the one receive code in centered JetBrains Mono. */
@Composable
private fun CodeBox(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CodeBoxRadius))
            .background(LarkColors.Surface)
            .border(width = 1.dp, color = LarkColors.Border, shape = RoundedCornerShape(CodeBoxRadius))
            .padding(vertical = CodeBoxVerticalPadding, horizontal = CodeBoxHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = LarkTheme.typography.mono,
            color = LarkColors.TextPrimary.copy(alpha = CODE_TEXT_ALPHA),
            textAlign = TextAlign.Center,
        )
    }
}
