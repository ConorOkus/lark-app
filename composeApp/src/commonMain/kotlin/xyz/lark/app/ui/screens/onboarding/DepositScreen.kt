package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/** Gaps inside the pinned bottom block: content -> status -> button. */
private val StatusTopGap = 20.dp
private val StatusBottomGap = 14.dp

/** 56dp to match the Get paid screen's pills, which this screen is styled from. */
private val CtaHeight = 56.dp

/**
 * The deposit step: an address to send to, and what is happening to what has arrived.
 *
 * Net-new, with no block in the design spec — with keys on device the money has to land somewhere
 * the user can see. Styled from the "Get paid" block so it reads as part of the same app.
 *
 * Opening this screen *is* the user asking for money, so there is nothing further to press: no
 * check control (the app watches the chain itself) and no confirm (there was never a second
 * decision to make). Copy stays, because copying an address is a real choice with a real
 * alternative. What arrived is described in terms of when it can be spent, never in terms of where
 * it is going — the user is not asked to learn that there are two places their money can live.
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
        // Scrolls rather than squeezes. This screen carries more than the Get paid screen it is
        // styled from — a three-line explainer, a QR, and a two-line address — so on a shorter or
        // denser phone the content exceeds the viewport. A weighted Spacer collapses to zero there,
        // which is what pushed the status line up against the buttons; a scroll region cannot.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Send bitcoin here.",
                style = LarkTheme.typography.screenTitle,
                color = LarkColors.TextPrimary,
                modifier = Modifier.padding(top = TitleTopPadding, bottom = TitleBottomPadding),
            )
            Text(
                text = "Send at least ${deposit.minLabel}. " +
                    "It takes a few minutes before you can spend it.",
                style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
                color = LarkColors.TextSecondary,
            )
            DepositAddress(address = deposit.address)
        }
        // Pinned rather than scrolling: once money has arrived this is the only thing on the screen
        // that has changed, and it must not be somewhere the user has to go looking for it.
        val arriving = deposit.arriving
        if (arriving != null) {
            Text(
                text = "${arriving.amount} on its way. ${arriving.note}",
                style = LarkTheme.typography.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = LarkColors.TextSecondary,
                modifier = Modifier.padding(top = StatusTopGap, bottom = StatusBottomGap),
            )
        }
        OutlinePillButton(
            text = deposit.copyLabel,
            onClick = actions.onCopy,
            height = CtaHeight,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** What the deposit screen can do, bundled so the screen keeps a short parameter list. */
data class DepositActions(
    val onBack: () -> Unit,
    val onCopy: () -> Unit,
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
