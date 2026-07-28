package xyz.lark.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.PillButtonHeight
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val CenterGap = 20.dp
private val CardRowPadding = 16.dp

private const val BODY_ALPHA = 0.55f
private const val LABEL_ALPHA = 0.5f

/**
 * Move on-chain (spec block `data-screen-label="Move on-chain (unilateral exit)"`): the
 * permissionless-exit explainer, the amount / miner fee / readiness card, and the
 * warning-colored "Start moving funds" pill.
 */
@Composable
fun ExitScreen(
    amount: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
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
        SettingsBackButton(onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(CenterGap, Alignment.CenterVertically),
        ) {
            Text(
                text = "Move everything\non-chain.",
                style = LarkTheme.typography.screenTitle.copy(fontSize = 32.sp, lineHeight = 35.sp),
                color = LarkColors.TextPrimary,
            )
            Text(
                text = "You don't need permission from anyone to do this — that's the point " +
                    "of LARK. It is slower and it costs a miner fee.",
                style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 25.sp),
                color = LarkColors.TextPrimary.copy(alpha = BODY_ALPHA),
            )
            ExitCard(amount = amount)
        }
        StartButton(onStart = onStart)
    }
}

/** The amount / miner fee / readiness surface card. */
@Composable
private fun ExitCard(amount: String) {
    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        ExitRow(label = "Amount", value = amount, tabular = true)
        RowGroupDivider()
        ExitRow(label = "Miner fee", value = "~$1.80")
        RowGroupDivider()
        ExitRow(label = "Ready to spend in", value = "about 24 hours")
    }
}

/** One card line: 400 15sp label left, 600 15sp value right. */
@Composable
private fun ExitRow(label: String, value: String, tabular: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(CardRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LarkTheme.typography.body.copy(fontSize = 15.sp, lineHeight = 15.sp),
            color = LarkColors.TextPrimary.copy(alpha = LABEL_ALPHA),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = LarkTheme.typography.itemTitle.copy(
                lineHeight = 15.sp,
                fontFeatureSettings = if (tabular) TABULAR_NUMERALS else null,
            ),
            color = LarkColors.TextPrimary,
            textAlign = TextAlign.End,
        )
    }
}

/** The warning-orange 60dp "Start moving funds" pill — the one non-gold CTA in the app. */
@Composable
private fun StartButton(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PillButtonHeight)
            .clip(CircleShape)
            .background(LarkColors.Warning)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onStart,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Start moving funds",
            style = LarkTheme.typography.button,
            color = LarkColors.OnWarning,
        )
    }
}
