package xyz.lark.app.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.ActivityRowModel
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import xyz.lark.app.ui.components.LarkTabBar
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val ScreenTopPadding = 56.dp
private val TitleHorizontalPadding = 20.dp
private val TitleTopPadding = 20.dp
private val TitleBottomPadding = 12.dp
private val ListBottomPadding = 8.dp
private val RowHorizontalPadding = 20.dp
private val RowVerticalPadding = 14.dp
private val RowGap = 14.dp
private val InitialCircleSize = 38.dp

private const val GOLD_CIRCLE_ALPHA = 0.18f
private const val PLAIN_CIRCLE_ALPHA = 0.08f

/**
 * Activity (spec block `data-screen-label="Activity"`): the screen title, the scrollable
 * transaction list (first row's initial circle gold-tinted, like the prototype), and the
 * tab bar with ACTIVITY selected.
 */
@Composable
fun ActivityScreen(
    model: AppModel,
    machine: AppStateMachine,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(top = ScreenTopPadding)) {
        Text(
            text = "Activity",
            style = LarkTheme.typography.screenTitle.copy(fontSize = 32.sp, lineHeight = 32.sp),
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(
                start = TitleHorizontalPadding,
                top = TitleTopPadding,
                end = TitleHorizontalPadding,
                bottom = TitleBottomPadding,
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = ListBottomPadding),
        ) {
            model.activity.forEachIndexed { index, row ->
                ActivityRow(
                    row = row,
                    first = index == 0,
                    onClick = { machine.openTx(index) },
                )
            }
        }
        LarkTabBar(current = Route.ACTIVITY, machine = machine)
    }
}

/** One activity row: initial circle, who/when, signed amount (incoming in green). */
@Composable
private fun ActivityRow(row: ActivityRowModel, first: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialCircle(initial = row.initial, gold = first)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.who,
                style = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 21.sp),
                color = LarkColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.whenLabel,
                style = LarkTheme.typography.bodySmall,
                color = LarkColors.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = row.amount,
            style = LarkTheme.typography.itemTitle.copy(
                fontSize = 16.sp,
                lineHeight = 16.sp,
                fontFeatureSettings = TABULAR_NUMERALS,
            ),
            color = if (row.incoming) LarkColors.Success else LarkColors.TextPrimary,
        )
    }
}

/** The 38dp leading circle with the counterparty's initial; gold tint on the first row. */
@Composable
private fun InitialCircle(initial: String, gold: Boolean) {
    val background = if (gold) {
        LarkColors.Gold.copy(alpha = GOLD_CIRCLE_ALPHA)
    } else {
        Color.White.copy(alpha = PLAIN_CIRCLE_ALPHA)
    }
    Box(
        modifier = Modifier
            .size(InitialCircleSize)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = LarkTheme.typography.itemTitle.copy(fontSize = 15.sp, lineHeight = 15.sp),
            color = if (gold) LarkColors.Gold else LarkColors.TextPrimary,
        )
    }
}
