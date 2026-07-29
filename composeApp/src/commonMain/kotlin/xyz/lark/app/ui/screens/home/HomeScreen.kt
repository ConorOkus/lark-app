package xyz.lark.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.BalanceModel
import xyz.lark.app.state.HealthModel
import xyz.lark.app.state.Route
import xyz.lark.app.ui.components.HealthDot
import xyz.lark.app.ui.components.LarkTabBar
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val HomeTopPadding = 56.dp
private val ScreenHorizontalPadding = 20.dp
private val TopRowHeight = 44.dp
private val ChipHorizontalPadding = 10.dp
private val ChipVerticalPadding = 7.dp
private val ChipGap = 7.dp
private val HealthDotSize = 7.dp
private val SecondaryGap = 12.dp
private val HideRowGap = 18.dp
private val HideRowHeight = 44.dp

private const val HIDE_LABEL_ALPHA = 0.4f

// Optical compensation for the fallback-font ₿ glyph's left side bearing (fraction of font size).
private const val BTC_GLYPH_LEFT_BEARING_EM = 0.10f

/**
 * Home — balance (spec block `data-screen-label="Home — balance"`): wordmark + health
 * chip, the centered balance block, the stale/offline attention banner, the Pay /
 * Get paid tiles, and the tab bar.
 */
@Composable
fun HomeScreen(
    model: AppModel,
    machine: AppStateMachine,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(top = HomeTopPadding)) {
        HomeTopRow(health = model.health, onOpenHealth = { machine.push(Route.HEALTH) })
        BalanceBlock(
            balance = model.balance,
            onToggleUnit = machine::toggleUnit,
            onToggleBalance = machine::toggleBalance,
            modifier = Modifier.weight(1f),
        )
        val banner = model.health.banner
        if (banner != null) {
            AttentionBanner(
                banner = banner,
                offline = model.health.offline,
                onClick = { machine.push(Route.HEALTH) },
            )
        }
        ActionTiles(
            onPay = { machine.push(Route.SEND_INPUT) },
            onGetPaid = { machine.go(Route.RECEIVE) },
        )
        LarkTabBar(current = Route.HOME, machine = machine)
    }
}

/** Top row: LARK wordmark left, health chip (dot + optional word, AE5) right. */
@Composable
private fun HomeTopRow(health: HealthModel, onOpenHealth: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopRowHeight)
            .padding(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LARK",
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 13.sp,
                letterSpacing = 0.2.em,
            ),
            color = LarkColors.TextPrimary,
        )
        HealthChip(health = health, onClick = onOpenHealth)
    }
}

/** The 7dp health dot plus the state word (hidden while a banner carries the message). */
@Composable
private fun HealthChip(health: HealthModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickableNoRipple(onClick)
            .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HealthDot(colorHex = health.dotColorHex, size = HealthDotSize)
        if (health.wordVisible) {
            Text(
                text = health.word,
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                ),
                color = LarkColors.TextTertiary,
            )
        }
    }
}

/** Centered balance block: label, primary (or the hidden dots), secondary, Hide/Show. */
@Composable
private fun BalanceBlock(
    balance: BalanceModel,
    onToggleUnit: () -> Unit,
    onToggleBalance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        BalanceAmounts(balance = balance, onToggleUnit = onToggleUnit)
        Spacer(modifier = Modifier.height(HideRowGap))
        HideShowRow(label = balance.hideLabel, onClick = onToggleBalance)
    }
}

/** The visible primary/secondary pair, or the dimmed hidden dots. */
@Composable
private fun BalanceAmounts(balance: BalanceModel, onToggleUnit: () -> Unit) {
    if (balance.visible) {
        val heroStyle = LarkTheme.typography.displayHero
        Text(
            text = balance.primary,
            style = heroStyle,
            color = LarkColors.TextPrimary,
            modifier = Modifier.offset {
                // The ₿ glyph (U+20BF) renders from a fallback font whose wide left
                // side bearing makes the hero amount look indented against the fiat
                // line below; nudge it left so both start optically flush.
                val bearing = if (balance.primary.startsWith('₿')) {
                    (heroStyle.fontSize.toPx() * BTC_GLYPH_LEFT_BEARING_EM).roundToInt()
                } else {
                    0
                }
                IntOffset(-bearing, 0)
            },
        )
        Spacer(modifier = Modifier.height(SecondaryGap))
        Text(
            text = balance.secondary,
            style = secondaryMoneyStyle(),
            color = LarkColors.TextTertiary,
            modifier = Modifier
                .clip(CircleShape)
                .clickableNoRipple(onToggleUnit),
        )
    } else {
        Text(
            text = balance.primary,
            style = LarkTheme.typography.displayHero.copy(
                fontSize = 60.sp,
                lineHeight = 56.sp,
                letterSpacing = 0.1.em,
            ),
            color = LarkColors.TextQuaternary,
        )
    }
}

/** The ghost Hide/Show toggle row under the balance. */
@Composable
private fun HideShowRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(HideRowHeight)
            .clip(CircleShape)
            .clickableNoRipple(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 14.sp,
            ),
            color = LarkColors.TextPrimary.copy(alpha = HIDE_LABEL_ALPHA),
        )
    }
}

/** Manrope 500 16/1 tabular — the secondary money line under a big amount. */
@Composable
private fun secondaryMoneyStyle() = LarkTheme.typography.itemTitle.copy(
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = TABULAR_NUMERALS,
)
