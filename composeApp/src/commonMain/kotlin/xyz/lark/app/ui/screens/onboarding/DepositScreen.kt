package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import xyz.lark.app.state.DepositModel
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.OutlinePillButton
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 24.dp
private val TitleBottomPadding = 8.dp
private val QrTopGap = 20.dp
private val QrCardRadius = 28.dp
private val QrCardPadding = 18.dp
private val QrSize = 200.dp
private val AddressTopGap = 18.dp
private val AddressPadding = 16.dp
private val StatusTopGap = 16.dp
private val CtaGap = 10.dp
private val CtaHeight = 52.dp

/**
 * The on-chain deposit step: an address to send to, what has arrived, and a board CTA.
 *
 * Net-new, with no block in the design spec — with keys on device the money has to land somewhere the
 * user can see. Styled from the "Get paid" block so it reads as part of the same app.
 *
 * The two balances are shown separately on purpose: "arrived" and "spendable in Ark" are different
 * facts here, and collapsing them would make the disabled board button look broken.
 */
@Composable
fun DepositScreen(
    deposit: DepositModel,
    actions: DepositActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = OnboardingHorizontalPadding,
                top = OnboardingTopPadding,
                end = OnboardingHorizontalPadding,
                bottom = OnboardingBottomPadding,
            ),
    ) {
        ScreenBackButton(onBack = actions.onBack)
        Text(
            text = "Send bitcoin here.",
            style = LarkTheme.typography.screenTitle,
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopPadding, bottom = TitleBottomPadding),
        )
        Text(
            text = "An on-chain deposit of at least ${deposit.minBoardLabel}. " +
                "It needs a few confirmations before LARK can move it in.",
            style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = LarkColors.TextSecondary,
        )
        DepositAddress(address = deposit.address)
        Text(
            text = statusLine(deposit),
            style = LarkTheme.typography.body.copy(fontSize = 14.sp),
            color = LarkColors.TextSecondary,
            modifier = Modifier.padding(top = StatusTopGap),
        )
        Spacer(modifier = Modifier.weight(1f))
        DepositCtas(deposit = deposit, actions = actions)
    }
}

/** What the deposit screen can do, bundled so the screen keeps a short parameter list. */
data class DepositActions(
    val onBack: () -> Unit,
    val onCopy: () -> Unit,
    val onCheckAgain: () -> Unit,
    val onBoard: () -> Unit,
)

/** The QR + address, or an honest waiting line while the wallet is still opening. */
@Composable
private fun DepositAddress(address: String) {
    if (address.isEmpty()) {
        Text(
            text = "Getting your address ready…",
            style = LarkTheme.typography.body.copy(fontSize = 15.sp),
            color = LarkColors.TextSecondary,
            modifier = Modifier.padding(top = StatusTopGap),
        )
        return
    }
    DepositQr(painter = rememberQrCodePainter(address))
    SurfaceCard(
        modifier = Modifier.padding(top = AddressTopGap).fillMaxWidth(),
        contentPadding = PaddingValues(AddressPadding),
    ) {
        Text(
            text = address,
            style = LarkTheme.typography.mono.copy(fontSize = 13.sp, lineHeight = 20.sp),
            color = LarkColors.TextPrimary,
        )
    }
}

@Composable
private fun DepositCtas(deposit: DepositModel, actions: DepositActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(CtaGap), modifier = Modifier.fillMaxWidth()) {
        OutlinePillButton(
            text = if (deposit.checking) "Checking…" else "Check again",
            onClick = actions.onCheckAgain,
            modifier = Modifier.weight(1f),
            height = CtaHeight,
        )
        OutlinePillButton(
            text = deposit.copyLabel,
            onClick = actions.onCopy,
            modifier = Modifier.weight(1f),
            height = CtaHeight,
        )
    }
    GoldPillButton(
        text = if (deposit.boarding) "Moving it in…" else "Move it in",
        onClick = actions.onBoard,
        modifier = Modifier.padding(top = CtaGap).fillMaxWidth(),
        enabled = deposit.canBoard && !deposit.boarding,
    )
}

@Composable
private fun DepositQr(painter: Painter) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = QrTopGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painter,
            contentDescription = "On-chain deposit address QR code",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .clip(RoundedCornerShape(QrCardRadius))
                .background(Color.White)
                .padding(QrCardPadding)
                .size(QrSize),
        )
    }
}

/** The one status line: what has arrived, and what it is waiting for. */
private fun statusLine(deposit: DepositModel): String = when {
    deposit.failed -> "That did not go through. Your money is still on-chain — try again."
    deposit.canBoard -> "${deposit.confirmedLabel} confirmed and ready to move in."
    deposit.hasPending -> "${deposit.pendingLabel} arrived, waiting for confirmations."
    else -> "Nothing has arrived yet."
}
