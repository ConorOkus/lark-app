package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
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
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 24.dp
private val TitleBottomPadding = 8.dp
private val SubCopyMaxWidth = 300.dp
private val CardsTopGap = 32.dp
private val CardGap = 10.dp

private const val SUB_COPY =
    "First deposit takes a few minutes to settle. Everything after that is instant."

/**
 * Onboarding 3 — add money (spec block `data-screen-label="Onboarding — add money"`):
 * back chevron, "Add money." title + settling note, the two funding option cards
 * (both lead into boarding), and a ghost "Later" escape at the bottom.
 */
@Composable
fun FundScreen(
    onBack: () -> Unit,
    onMoveBitcoinIn: () -> Unit,
    onBuyWithCard: () -> Unit,
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
        OnboardingBackButton(onBack = onBack)
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
        Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
            OptionCard(
                title = "Move bitcoin in",
                subtitle = "From another wallet or exchange",
                icon = LarkIcons.ArrowUp,
                onClick = onMoveBitcoinIn,
                iconTint = LarkColors.Gold,
            )
            OptionCard(
                title = "Buy with a card",
                subtitle = "Debit or Apple Pay",
                icon = LarkIcons.Card,
                onClick = onBuyWithCard,
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
