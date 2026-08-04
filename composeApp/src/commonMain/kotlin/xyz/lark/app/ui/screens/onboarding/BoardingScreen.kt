package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.GoldSpinner
import xyz.lark.app.ui.components.OutlinePillButton
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val SpinnerSize = 64.dp
private val TitleTopGap = 32.dp
private val SubCopyTopGap = 10.dp
private val SubCopyMaxWidth = 270.dp
private val SkipButtonHeight = 52.dp

/**
 * Was "You can close LARK — it finishes without you", which is not true with keys on device: nothing
 * runs while the app is closed, so a board finishes when LARK is next open. Saying otherwise would
 * teach a tester to close the app at exactly the wrong moment.
 */
private const val SUB_COPY = "A few minutes, and a few confirmations. Keep LARK open and it will finish here."

/**
 * First deposit settling (spec block `data-screen-label="Onboarding — first deposit settling"`):
 * a centered 64dp gold spinner with the settling copy, and the demo skip pill at the bottom.
 */
@Composable
fun BoardingScreen(
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = OnboardingHorizontalPadding,
                top = OnboardingTopPadding,
                end = OnboardingHorizontalPadding,
                bottom = OnboardingBottomPadding,
            ),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GoldSpinner(size = SpinnerSize)
            Spacer(modifier = Modifier.height(TitleTopGap))
            Text(
                text = "Settling your\nfirst deposit.",
                style = LarkTheme.typography.screenTitle.copy(fontSize = 28.sp, lineHeight = 32.sp),
                color = LarkColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(SubCopyTopGap))
            Text(
                text = SUB_COPY,
                style = LarkTheme.typography.body,
                color = LarkColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = SubCopyMaxWidth),
            )
        }
        OutlinePillButton(
            text = "Skip ahead (demo)",
            onClick = onSkip,
            height = SkipButtonHeight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}
