package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.OutlinePillButton
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val WelcomeTopPadding = 88.dp
private val HeroGap = 16.dp
private val ButtonGap = 12.dp
private val SubCopyMaxWidth = 290.dp
private const val SUB_COPY_ALPHA = 0.55f

private const val HERO = "Money that\nmoves now."
private const val SUB_COPY =
    "Bitcoin you hold yourself, spendable in about a second. No account, no bank, no waiting."

/**
 * Onboarding 1 — welcome (spec block `data-screen-label="Onboarding — welcome"`):
 * gold LARK eyebrow up top, hero + sub copy, and the two entry CTAs pinned at the bottom.
 */
@Composable
fun WelcomeScreen(
    onSetUpWallet: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = OnboardingHorizontalPadding,
                top = WelcomeTopPadding,
                end = OnboardingHorizontalPadding,
                bottom = OnboardingBottomPadding,
            ),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // The spec's brand eyebrow: 700 14/1, .22em tracking, gold.
        Text(
            text = "LARK",
            style = LarkTheme.typography.eyebrow.copy(fontSize = 14.sp, letterSpacing = 0.22.em),
            color = LarkColors.Gold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(HeroGap)) {
            Text(
                text = HERO,
                style = LarkTheme.typography.balance,
                color = LarkColors.TextPrimary,
            )
            Text(
                text = SUB_COPY,
                style = LarkTheme.typography.body.copy(fontSize = 17.sp, lineHeight = 25.sp),
                color = LarkColors.TextPrimary.copy(alpha = SUB_COPY_ALPHA),
                modifier = Modifier.widthIn(max = SubCopyMaxWidth),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ButtonGap)) {
            GoldPillButton(
                text = "Set up a wallet",
                onClick = onSetUpWallet,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinePillButton(
                text = "I have 12 words",
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
