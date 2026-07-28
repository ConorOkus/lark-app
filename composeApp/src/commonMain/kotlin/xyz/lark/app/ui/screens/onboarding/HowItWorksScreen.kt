package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.IconCircle
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 24.dp
private val TitleBottomPadding = 36.dp
private val PromiseGap = 28.dp
private val PromiseRowGap = 16.dp
private val PromiseIconCircleSize = 38.dp
private val PromiseSubtitleGap = 4.dp

/** The spec's tinted promise-icon circles: gold at .14 for the hero row, white at .07 otherwise. */
private const val GOLD_CIRCLE_ALPHA = 0.14f
private const val WHITE_CIRCLE_ALPHA = 0.07f

/**
 * Onboarding 2 — three promises (spec block `data-screen-label="Onboarding — three promises"`):
 * back chevron, "Three things to know." title, three icon+copy promise rows, gold Continue.
 */
@Composable
fun HowItWorksScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
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
        OnboardingBackButton(onBack = onBack)
        Text(
            text = "Three things\nto know.",
            style = LarkTheme.typography.screenTitle,
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopPadding, bottom = TitleBottomPadding),
        )
        Column(verticalArrangement = Arrangement.spacedBy(PromiseGap)) {
            PromiseRow(
                icon = LarkIcons.Bolt,
                iconTint = LarkColors.Gold,
                iconBackground = LarkColors.Gold.copy(alpha = GOLD_CIRCLE_ALPHA),
                title = "Payments land in a second",
                subtitle = "Any amount, any time of day, to anyone.",
            )
            PromiseRow(
                icon = LarkIcons.Shield,
                title = "Only this phone can spend it",
                subtitle = "No company holds your money, so no company can freeze it.",
            )
            PromiseRow(
                icon = LarkIcons.Refresh,
                title = "Nothing to manage",
                subtitle = "LARK keeps your wallet ready in the background. " +
                    "If it ever needs you, it says so plainly.",
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        GoldPillButton(
            text = "Continue",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One promise row: 38dp tinted icon circle, 17/600 title, 15 sub in the .5 tier. */
@Composable
private fun PromiseRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = LarkColors.TextPrimary,
    iconBackground: Color = Color.White.copy(alpha = WHITE_CIRCLE_ALPHA),
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PromiseRowGap)) {
        IconCircle(
            icon = icon,
            modifier = Modifier.size(PromiseIconCircleSize),
            tint = iconTint,
            background = iconBackground,
        )
        Column {
            Text(
                text = title,
                style = LarkTheme.typography.sectionTitle,
                color = LarkColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = LarkTheme.typography.body,
                color = LarkColors.TextSecondary,
                modifier = Modifier.padding(top = PromiseSubtitleGap),
            )
        }
    }
}
