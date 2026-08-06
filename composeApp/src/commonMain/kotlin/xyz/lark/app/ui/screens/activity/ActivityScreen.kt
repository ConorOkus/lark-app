package xyz.lark.app.ui.screens.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.ActivityRowModel
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import xyz.lark.app.ui.components.InitialAvatar
import xyz.lark.app.ui.components.LarkTabBar
import xyz.lark.app.ui.components.clickableNoRipple
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
            .clickableNoRipple(onClick)
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialAvatar(initial = row.initial, gold = first)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.who,
                style = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 21.sp),
                color = LarkColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // A pending row has always been in this list; until now it claimed to have landed.
                // Saying so where the timestamp goes is the smallest honest correction.
                text = if (row.pending) "On its way" else row.whenLabel,
                style = LarkTheme.typography.bodySmall,
                color = if (row.pending) LarkColors.Gold else LarkColors.TextTertiary,
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
            // Pending amounts stay neutral: green reads as "in your balance", which it is not yet.
            color = when {
                row.pending -> LarkColors.TextTertiary
                row.incoming -> LarkColors.Success
                else -> LarkColors.TextPrimary
            },
        )
    }
}
