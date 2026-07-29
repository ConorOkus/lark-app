package xyz.lark.app.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val TitleTopGap = 20.dp
private val TitleBottomGap = 6.dp
private val SubBottomGap = 24.dp
private val StatListRadius = 14.dp
private val StatCellHorizontalPadding = 16.dp
private val StatCellVerticalPadding = 14.dp
private val StatLabelGap = 5.dp
private val StatSeparatorWidth = 1.dp

private const val STAT_LABEL_ALPHA = 0.4f

/** One label/value cell of the technical stat list; [mono] switches to JetBrains Mono. */
@Immutable
private data class TechStat(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    val tabular: Boolean = false,
)

/**
 * Static demo stats, verbatim from the spec block except the server host: the network is
 * mutinynet (KTD-11), so `ark.signet.lark` becomes `ark.mutinynet.lark`.
 */
private val TECH_STATS = listOf(
    TechStat(label = "Route", value = "Ark out-of-round → Lightning bridge"),
    TechStat(label = "VTXOs spent", value = "2 · 51,000 sats", tabular = true),
    TechStat(label = "Change VTXO expiry", value = "block 918,402 · in 27 days", tabular = true),
    TechStat(label = "Preimage", value = "8f2c1ad4e77b0159c3ab...44e1", mono = true),
    TechStat(label = "Server", value = "02f9a1...ark.mutinynet.lark", mono = true),
)

/**
 * Transaction — technical details (spec block `data-screen-label="Transaction — technical
 * details"`): back chevron, title, the "nothing here is needed" note, and the stat list.
 */
@Composable
fun TxTechScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = DetailHorizontalPadding,
                top = DetailTopPadding,
                end = DetailHorizontalPadding,
                bottom = DetailBottomPadding,
            ),
    ) {
        ScreenBackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(TitleTopGap))
        Text(
            text = "Technical details",
            style = LarkTheme.typography.screenTitle.copy(fontSize = 28.sp, lineHeight = 31.sp),
            color = LarkColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(TitleBottomGap))
        Text(
            text = "Nothing here is needed to use LARK.",
            style = LarkTheme.typography.label.copy(lineHeight = 21.sp),
            color = LarkColors.TextTertiary,
        )
        Spacer(modifier = Modifier.height(SubBottomGap))
        TechStatList()
    }
}

/** The 14dp-radius stat group: #14161A cells separated by 1dp rgba(255,255,255,.07). */
@Composable
private fun TechStatList() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(StatListRadius))
            .background(LarkColors.Border),
        verticalArrangement = Arrangement.spacedBy(StatSeparatorWidth),
    ) {
        TECH_STATS.forEach { stat -> TechStatCell(stat = stat) }
    }
}

/** One cell: 12sp faint label above the 500-weight value (mono for codes). */
@Composable
private fun TechStatCell(stat: TechStat) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LarkColors.Surface)
            .padding(
                horizontal = StatCellHorizontalPadding,
                vertical = StatCellVerticalPadding,
            ),
    ) {
        Text(
            text = stat.label,
            style = LarkTheme.typography.caption,
            color = LarkColors.TextPrimary.copy(alpha = STAT_LABEL_ALPHA),
        )
        Spacer(modifier = Modifier.height(StatLabelGap))
        if (stat.mono) {
            Text(
                text = stat.value,
                style = LarkTheme.typography.mono,
                color = LarkColors.TextCode,
            )
        } else {
            Text(
                text = stat.value,
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFeatureSettings = if (stat.tabular) TABULAR_NUMERALS else null,
                ),
                color = LarkColors.TextPrimary,
            )
        }
    }
}
