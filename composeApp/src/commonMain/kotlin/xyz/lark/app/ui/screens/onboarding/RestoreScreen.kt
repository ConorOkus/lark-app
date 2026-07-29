package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 24.dp
private val TitleBottomPadding = 8.dp
private val InputTopGap = 28.dp
private val InputPadding = 18.dp
private val InputMinHeight = 120.dp

/** Static placeholder — real seed entry is out of scope for this milestone. */
private const val WORDS_PLACEHOLDER = "tide margin ocean …"

/**
 * Restore from 12 words (spec block `data-screen-label="Restore from 12 words"`):
 * back chevron, "Type your 12 words." title + hint, the words input surface (placeholder
 * only this milestone), and the gold restore CTA at the bottom.
 */
@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    onRestore: () -> Unit,
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
            text = "Type your\n12 words.",
            style = LarkTheme.typography.screenTitle,
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopPadding, bottom = TitleBottomPadding),
        )
        Text(
            text = "In order, separated by spaces. LARK rebuilds the rest.",
            style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = LarkColors.TextSecondary,
        )
        SurfaceCard(
            modifier = Modifier
                .padding(top = InputTopGap)
                .fillMaxWidth()
                .heightIn(min = InputMinHeight),
            contentPadding = PaddingValues(InputPadding),
        ) {
            Text(
                text = WORDS_PLACEHOLDER,
                style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 27.sp),
                color = LarkColors.TextQuaternary,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        GoldPillButton(
            text = "Restore wallet",
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
