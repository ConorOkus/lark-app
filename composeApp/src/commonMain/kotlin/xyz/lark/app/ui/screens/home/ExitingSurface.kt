package xyz.lark.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.ExitingModel
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val SurfaceHorizontalPadding = 20.dp
private val HeadlineGap = 8.dp
private val LedgerTopGap = 28.dp
private val CardRowPadding = 16.dp
private val FootnoteTopGap = 20.dp

private const val DETAIL_ALPHA = 0.55f
private const val LABEL_ALPHA = 0.5f
private const val FOOTNOTE_ALPHA = 0.4f

/**
 * What home is while the wallet is leaving the Ark.
 *
 * This replaces the balance and the action tiles rather than sitting above them, because exiting
 * is a state the wallet is *in*, not a notice about one. The balance it would replace is not
 * spendable and the actions it removes are not available, so showing either would offer something
 * that is not there.
 *
 * The headline is whatever the model worked out — a wait when one is computable, the state's own
 * name when it is not. Nothing here invents a figure; every string arrives ready.
 */
@Composable
fun ExitingSurface(exiting: ExitingModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SurfaceHorizontalPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = exiting.headline,
            style = LarkTheme.typography.screenTitle.copy(fontSize = 30.sp, lineHeight = 34.sp),
            color = if (exiting.stalled) LarkColors.Warning else LarkColors.TextPrimary,
        )
        Text(
            text = exiting.detail,
            modifier = Modifier.padding(top = HeadlineGap),
            style = LarkTheme.typography.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
            color = LarkColors.TextPrimary.copy(alpha = DETAIL_ALPHA),
        )
        ExitingLedger(
            exiting = exiting,
            modifier = Modifier.padding(top = LedgerTopGap),
        )
        Text(
            // The one instruction worth giving: nothing here needs them, and an exit that takes
            // hours should not read as something they are supposed to sit and watch.
            text = "Nothing to do. You can close the app —\nLARK picks this up where it left off.",
            modifier = Modifier.padding(top = FootnoteTopGap).fillMaxWidth(),
            style = LarkTheme.typography.body.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = LarkColors.TextPrimary.copy(alpha = FOOTNOTE_ALPHA),
        )
    }
}

/**
 * Where the money is: still moving, and already arrived.
 *
 * Both rows show even when one is zero. A zero here is a real reading rather than an unknown —
 * nothing has landed yet is a fact worth stating on a screen whose whole subject is progress.
 */
@Composable
private fun ExitingLedger(exiting: ExitingModel, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        LedgerRow(label = "On its way", value = exiting.inFlight)
        RowGroupDivider()
        LedgerRow(label = "Landed on-chain", value = exiting.landed)
        RowGroupDivider()
        LedgerRow(label = "Outputs", value = exiting.claimedOf)
    }
}

/** One ledger line: label left, tabular figure right — the exit screen's row, same voice. */
@Composable
private fun LedgerRow(label: String, value: String) {
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
                fontFeatureSettings = TABULAR_NUMERALS,
            ),
            color = LarkColors.TextPrimary,
        )
    }
}
