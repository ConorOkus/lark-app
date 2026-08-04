package xyz.lark.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.core.format.MoneyFormat
import xyz.lark.app.core.model.FundsStats
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.NetworkStats
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.ChannelsModel
import xyz.lark.app.state.DemoHealthOption
import xyz.lark.app.state.Route
import xyz.lark.app.ui.components.HealthDot
import xyz.lark.app.ui.components.RowGroup
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.SectionEyebrow
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val TitleTopGap = 18.dp
private val TitleBottomGap = 6.dp
private val SubtitleBottomGap = 22.dp
private val EyebrowBottomGap = 10.dp
private val SectionGap = 24.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 14.dp
private val DotGap = 8.dp
private val HealthDotSize = 7.dp
private val AddressLabelGap = 5.dp
private val AddressNoteGap = 6.dp
private val ActionGap = 10.dp
private val ActionHeight = 54.dp
private val ActionCornerRadius = 14.dp
private val DemoRowCornerRadius = 12.dp
private val DemoRowHorizontalPadding = 14.dp
private val DemoRowVerticalPadding = 12.dp
private val DemoRowGap = 8.dp
private val DemoDotGap = 10.dp

private const val SUBTITLE_ALPHA = 0.45f
private const val ADDRESS_LABEL_ALPHA = 0.4f
private const val NOTE_ALPHA = 0.35f
private const val ACTION_BORDER_ALPHA = 0.1f
private const val EXIT_BORDER_ALPHA = 0.35f
private const val DEMO_BORDER_ALPHA = 0.4f
private const val DEMO_NOTE_ALPHA = 0.4f

@Suppress("MagicNumber") // the spec's unselected demo-row surface, #111317
private val DemoRowBackground = Color(0xFF111317)

/**
 * Advanced (spec block `data-screen-label="Advanced"`): the FUNDS and NETWORK stat groups,
 * the refresh / unilateral-exit actions, and — on demo builds only — the DEMO health rail.
 */
@Composable
fun AdvancedScreen(
    model: AppModel,
    machine: AppStateMachine,
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
            )
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenBackButton(onBack = machine::back)
        Text(
            text = "Advanced",
            style = LarkTheme.typography.screenTitle.copy(fontSize = 30.sp, lineHeight = 32.sp),
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopGap, bottom = TitleBottomGap),
        )
        Text(
            text = "Everything LARK handles for you, in the protocol's own words.",
            style = LarkTheme.typography.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = LarkColors.TextPrimary.copy(alpha = SUBTITLE_ALPHA),
            modifier = Modifier.padding(bottom = SubtitleBottomGap),
        )
        SectionEyebrow(text = "FUNDS", modifier = Modifier.padding(bottom = EyebrowBottomGap))
        FundsGroup(funds = model.advanced.funds)
        Spacer(modifier = Modifier.height(SectionGap))
        SectionEyebrow(text = "NETWORK", modifier = Modifier.padding(bottom = EyebrowBottomGap))
        NetworkGroup(
            network = model.advanced.network,
            channels = model.channels,
            dotColorHex = model.health.dotColorHex,
        )
        Spacer(modifier = Modifier.height(SectionGap))
        ActionButtons(onRefresh = machine::runRefresh, onExit = { machine.push(Route.EXIT) })
        val demoHealth = model.demoHealth
        if (demoHealth != null) {
            DemoSection(options = demoHealth, onForce = machine::forceHealth)
        }
    }
}

/** Em-dash for a stat the active core cannot supply; the screen never invents a number (R9). */
private const val UNKNOWN = "—"

/** FUNDS: VTXO count/total, expiry, refresh, exit reserve, and the deposit-address cell. */
@Composable
private fun FundsGroup(funds: FundsStats) {
    RowGroup {
        StatValueRow(
            label = "VTXOs",
            // An unknown count must not render as "0": "0 · ₿29,879" asserts the wallet holds
            // nothing while showing what it holds, which is worse than saying nothing.
            value = "${funds.vtxoCount?.toString() ?: UNKNOWN} · ${MoneyFormat.btc(funds.vtxoTotalSats)}",
        )
        RowGroupDivider()
        StatValueRow(label = "Soonest expiry", value = funds.soonestExpiry)
        RowGroupDivider()
        StatValueRow(label = "Last refresh", value = funds.lastRefresh)
        RowGroupDivider()
        StatValueRow(
            label = "On-chain (exit reserve)",
            value = funds.onChainReserveSats?.let(MoneyFormat::btc) ?: UNKNOWN,
        )
        RowGroupDivider()
        AddressCell(address = funds.depositAddress)
    }
}

/** The two-line deposit-address cell: label, breakable mono address, dissuading note. */
@Composable
private fun AddressCell(address: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
    ) {
        Text(
            text = "On-chain deposit address",
            style = LarkTheme.typography.caption.copy(lineHeight = 12.sp),
            color = LarkColors.TextPrimary.copy(alpha = ADDRESS_LABEL_ALPHA),
            modifier = Modifier.padding(bottom = AddressLabelGap),
        )
        Text(
            text = address,
            style = LarkTheme.typography.mono,
            color = LarkColors.TextCode,
        )
        Text(
            text = "Slow and costs a miner fee. Use the Get paid code instead.",
            style = LarkTheme.typography.caption,
            color = LarkColors.TextPrimary.copy(alpha = NOTE_ALPHA),
            modifier = Modifier.padding(top = AddressNoteGap),
        )
    }
}

/** NETWORK: the Ark server dot/status row, next round, Lightning bridge + channel rows, chain tip. */
@Composable
private fun NetworkGroup(network: NetworkStats, channels: ChannelsModel, dotColorHex: String) {
    RowGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ark server",
                style = LarkTheme.typography.label,
                color = LarkColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            HealthDot(colorHex = dotColorHex, size = HealthDotSize)
            Spacer(modifier = Modifier.width(DotGap))
            Text(text = network.arkServerStatus, style = statValueStyle(), color = LarkColors.TextPrimary)
        }
        RowGroupDivider()
        StatValueRow(label = "Next round", value = network.nextRound)
        RowGroupDivider()
        StatValueRow(label = "Lightning bridge", value = channels.bridgeValue)
        channels.rows.forEach { row ->
            RowGroupDivider()
            // One read-only channel row (plan U5, R8: no actions), in the stat-row idiom.
            StatValueRow(label = row.shortId, value = row.value, subLine = row.expiryLabel)
        }
        RowGroupDivider()
        StatValueRow(
            label = "Chain tip",
            value = network.chainTip?.let { MoneyFormat.btc(it).removePrefix("₿") } ?: UNKNOWN,
        )
    }
}

/** One stat line: 400 14sp label left, 600 14sp tabular value right, optional caption sub-line. */
@Composable
private fun StatValueRow(label: String, value: String, subLine: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = LarkTheme.typography.label,
                color = LarkColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(text = value, style = statValueStyle(), color = LarkColors.TextPrimary, textAlign = TextAlign.End)
        }
        if (subLine != null) {
            Text(
                text = subLine,
                style = LarkTheme.typography.caption,
                color = LarkColors.TextPrimary.copy(alpha = NOTE_ALPHA),
                modifier = Modifier.padding(top = AddressNoteGap),
            )
        }
    }
}

/** The refresh and unilateral-exit actions, stacked with a 10dp gap. */
@Composable
private fun ActionButtons(onRefresh: () -> Unit, onExit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ActionGap)) {
        ActionButton(
            text = "Refresh funds now",
            onClick = onRefresh,
            background = LarkColors.Surface,
            borderColor = Color.White.copy(alpha = ACTION_BORDER_ALPHA),
            textColor = LarkColors.TextPrimary,
        )
        ActionButton(
            text = "Move everything on-chain",
            onClick = onExit,
            background = Color.Transparent,
            borderColor = LarkColors.Warning.copy(alpha = EXIT_BORDER_ALPHA),
            textColor = LarkColors.Warning,
        )
    }
}

/** A 54dp, 14dp-radius bordered action button (the Advanced screen's non-pill tier). */
@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    background: Color,
    borderColor: Color,
    textColor: Color,
) {
    val shape = RoundedCornerShape(ActionCornerRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ActionHeight)
            .clip(shape)
            .background(background)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = LarkTheme.typography.itemTitle.copy(lineHeight = 15.sp), color = textColor)
    }
}

/** DEMO (demo builds only): the force-a-health-state rail from the prototype. */
@Composable
private fun DemoSection(
    options: List<DemoHealthOption>,
    onForce: (HealthState) -> Unit,
) {
    Column(modifier = Modifier.padding(top = SectionGap)) {
        SectionEyebrow(text = "DEMO", modifier = Modifier.padding(bottom = EyebrowBottomGap))
        Text(
            text = "Force a network state (demo build only).",
            style = LarkTheme.typography.caption,
            color = LarkColors.TextPrimary.copy(alpha = NOTE_ALPHA),
            modifier = Modifier.padding(bottom = EyebrowBottomGap),
        )
        Column(verticalArrangement = Arrangement.spacedBy(DemoRowGap)) {
            options.forEach { option ->
                DemoOptionRow(option = option, onClick = { onForce(option.state) })
            }
        }
    }
}

/** One selectable demo row: health dot, label, and the trailing explanatory note. */
@Composable
private fun DemoOptionRow(option: DemoHealthOption, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DemoRowCornerRadius)
    val border = if (option.selected) LarkColors.Gold.copy(alpha = DEMO_BORDER_ALPHA) else LarkColors.Border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (option.selected) LarkColors.SurfacePressed else DemoRowBackground)
            .border(width = 1.dp, color = border, shape = shape)
            .clickableNoRipple(onClick)
            .padding(horizontal = DemoRowHorizontalPadding, vertical = DemoRowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(DemoDotGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthDot(colorHex = option.dotColorHex, size = HealthDotSize)
        Text(
            text = option.label,
            style = LarkTheme.typography.itemTitle.copy(fontSize = 13.sp, lineHeight = 13.sp),
            color = LarkColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = option.note,
            style = LarkTheme.typography.caption.copy(fontSize = 11.sp, lineHeight = 11.sp),
            color = LarkColors.TextPrimary.copy(alpha = DEMO_NOTE_ALPHA),
        )
    }
}

/** Manrope 600 14/1 tabular — the Advanced stat-value tier. */
@Composable
private fun statValueStyle() = LarkTheme.typography.itemTitle.copy(
    fontSize = 14.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = TABULAR_NUMERALS,
)
