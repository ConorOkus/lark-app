package xyz.lark.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.HealthModel
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.HealthDot
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val CenterGap = 22.dp
private val IconCircleSize = 56.dp
private val StatusDotSize = 14.dp
private val BodyTopGap = 14.dp
private val DetailsButtonHeight = 52.dp

private const val ICON_TINT_ALPHA = 0.18f
private const val BODY_ALPHA = 0.55f
private const val DETAILS_ALPHA = 0.45f

/**
 * Wallet status (spec block `data-screen-label="Wallet status"`): the tinted 56dp status
 * circle, the state title/body, the gold action pill when the state needs one, and the
 * ghost "See the details" foot link.
 */
@Composable
fun HealthScreen(
    health: HealthModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = SettingsHorizontalPadding,
                top = SettingsTopPadding,
                end = SettingsHorizontalPadding,
                bottom = SettingsBottomPadding,
            ),
    ) {
        ScreenBackButton(onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(CenterGap, Alignment.CenterVertically),
        ) {
            StatusCircle(health = health)
            Column {
                Text(
                    text = health.statusTitle,
                    style = LarkTheme.typography.screenTitle,
                    color = LarkColors.TextPrimary,
                )
                Text(
                    text = health.statusBody,
                    style = LarkTheme.typography.body.copy(fontSize = 17.sp, lineHeight = 26.sp),
                    color = LarkColors.TextPrimary.copy(alpha = BODY_ALPHA),
                    modifier = Modifier.padding(top = BodyTopGap),
                )
            }
            val actionLabel = health.actionLabel
            if (actionLabel != null) {
                GoldPillButton(
                    text = actionLabel,
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        DetailsButton(onDetails = onDetails)
    }
}

/** The 56dp circle — warning-tinted when offline, gold-tinted otherwise — around the 14dp dot. */
@Composable
private fun StatusCircle(health: HealthModel) {
    val tint = if (health.offline) LarkColors.Warning else LarkColors.Gold
    Box(
        modifier = Modifier
            .size(IconCircleSize)
            .clip(CircleShape)
            .background(tint.copy(alpha = ICON_TINT_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        HealthDot(colorHex = health.dotColorHex, size = StatusDotSize)
    }
}

/** The 52dp ghost foot link to the Advanced screen. */
@Composable
private fun DetailsButton(onDetails: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DetailsButtonHeight)
            .clip(CircleShape)
            .clickableNoRipple(onDetails),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "See the details",
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 15.sp,
            ),
            color = LarkColors.TextPrimary.copy(alpha = DETAILS_ALPHA),
            textAlign = TextAlign.Center,
        )
    }
}
