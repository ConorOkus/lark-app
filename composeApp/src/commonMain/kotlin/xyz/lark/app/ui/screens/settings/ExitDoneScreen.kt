package xyz.lark.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.ExitDoneModel
import xyz.lark.app.ui.components.PillButtonHeight
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val CenterGap = 20.dp

private const val BODY_ALPHA = 0.55f

/**
 * The receipt for a finished unilateral exit, shown once.
 *
 * The counterpart to [ExitScreen] and deliberately its opposite in tone. That screen is the one
 * non-gold, warning-coloured CTA in the app because it starts something irreversible; this one is
 * the plainest possible statement that it worked.
 *
 * There is no next step here on purpose. The funds are on-chain under the holder's own keys — the
 * same seed that backs the Ark wallet — so nothing is outstanding and nothing needs rescuing.
 * Offering a "move them somewhere safe" action would invent a danger that does not exist and undo
 * the one thing this screen is for.
 */
@Composable
fun ExitDoneScreen(
    done: ExitDoneModel,
    onDone: () -> Unit,
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
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(CenterGap, Alignment.CenterVertically),
        ) {
            Text(
                // Names the destination rather than the thing left behind. A holder never had to
                // learn where their money was living, so the screen that ends the story is the
                // worst possible place to teach them — what changed for them is where it is now.
                text = "It's all\non-chain now.",
                style = LarkTheme.typography.screenTitle.copy(fontSize = 32.sp, lineHeight = 35.sp),
                color = LarkColors.TextPrimary,
            )
            Text(
                text = "Your money moved out on its own keys. No server was involved, and " +
                    "nobody could have stopped it.",
                style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 25.sp),
                color = LarkColors.TextPrimary.copy(alpha = BODY_ALPHA),
            )
            DoneCard(done = done)
        }
        DoneButton(onDone = onDone)
    }
}

/** Landed / fee / duration, in the exit screen's card so the two read as one story. */
@Composable
private fun DoneCard(done: ExitDoneModel) {
    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        ExitRow(label = "Landed", value = done.landed, tabular = true)
        RowGroupDivider()
        ExitRow(label = "Miner fee", value = done.minerFee, tabular = true)
        RowGroupDivider()
        ExitRow(label = "Took", value = done.took)
    }
}

/** Gold, not the warning orange of the screen that started this: nothing here is a risk. */
@Composable
private fun DoneButton(onDone: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PillButtonHeight)
            .clip(CircleShape)
            .background(LarkColors.Gold)
            .clickableNoRipple(onDone),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Done",
            style = LarkTheme.typography.button,
            color = LarkColors.OnGold,
        )
    }
}
