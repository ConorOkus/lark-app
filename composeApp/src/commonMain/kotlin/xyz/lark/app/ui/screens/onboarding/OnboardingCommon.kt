package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.theme.LarkColors

/**
 * Shared metrics of the onboarding `sc-if` blocks in
 * `docs/design/lark-wallet/LARK Wallet.dc.html` (`padding:74px 24px 46px`).
 */
internal val OnboardingTopPadding: Dp = 74.dp
internal val OnboardingHorizontalPadding: Dp = 24.dp
internal val OnboardingBottomPadding: Dp = 46.dp

private val BackButtonSize = 44.dp
private val BackButtonInset = 10.dp
private val BackIconSize = 22.dp

/**
 * The onboarding back affordance: a 44x44 tap target nudged 10dp into the leading
 * margin (the spec's `margin-left:-10px`) so the 22dp chevron aligns with the content edge.
 */
@Composable
internal fun OnboardingBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .offset(x = -BackButtonInset)
            .size(BackButtonSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onBack,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LarkIcons.BackChevron,
            contentDescription = "Back",
            modifier = Modifier.size(BackIconSize),
            tint = LarkColors.TextPrimary,
        )
    }
}
