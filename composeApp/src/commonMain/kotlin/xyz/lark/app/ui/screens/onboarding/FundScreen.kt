package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.GhostButton
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.OptionCard
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 24.dp
private val TitleBottomPadding = 8.dp
private val SubCopyMaxWidth = 300.dp
private val CardsTopGap = 32.dp

private const val SUB_COPY =
    "First deposit takes a few minutes to settle. Everything after that is instant."

/**
 * Onboarding 3 — add money (spec block `data-screen-label="Onboarding — add money"`):
 * back chevron, "Add money." title + settling note, the funding option card, and a ghost
 * "Later" escape at the bottom.
 *
 * The spec's second card, "Buy with a card", is deliberately absent: there is no card provider
 * integrated, so tapping it went straight to the first-deposit settling spinner for a purchase that
 * had not happened and never would. An affordance that fabricates progress is worse than a missing
 * one — restore it alongside a real provider, not before.
 */
@Composable
fun FundScreen(
    onBack: () -> Unit,
    /** Null for a core with no on-chain wallet, which hides the card rather than faking a route. */
    onMoveBitcoinIn: (() -> Unit)?,
    onLater: () -> Unit,
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
        ScreenBackButton(onBack = onBack)
        Text(
            text = "Add money.",
            style = LarkTheme.typography.screenTitle,
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopPadding, bottom = TitleBottomPadding),
        )
        Text(
            text = SUB_COPY,
            style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = LarkColors.TextSecondary,
            modifier = Modifier.widthIn(max = SubCopyMaxWidth),
        )
        Spacer(modifier = Modifier.height(CardsTopGap))
        if (onMoveBitcoinIn != null) {
            OptionCard(
                title = "Move bitcoin in",
                subtitle = "From another wallet or exchange",
                icon = LarkIcons.ArrowUp,
                onClick = onMoveBitcoinIn,
                iconTint = LarkColors.Gold,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        GhostButton(
            text = "Later",
            onClick = onLater,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
