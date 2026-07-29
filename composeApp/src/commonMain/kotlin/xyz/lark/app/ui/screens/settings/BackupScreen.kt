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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.BackupModel
import xyz.lark.app.ui.components.OutlinePillButton
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val TitleTopGap = 18.dp
private val TitleBottomGap = 8.dp
private val GridTopGap = 24.dp
private val GridGap = 8.dp
private val CellCornerRadius = 12.dp
private val CellHorizontalPadding = 14.dp
private val CellVerticalPadding = 12.dp
private val CellGap = 10.dp
private val NumberWidth = 14.dp
private val RevealPillHorizontalPadding = 22.dp
private val RevealPillVerticalPadding = 14.dp
private val CountdownBottomGap = 14.dp

private const val WORDS_PER_ROW = 2
private const val NUMBER_ALPHA = 0.3f
private const val SUBTITLE_ALPHA = 0.5f
private const val COUNTDOWN_ALPHA = 0.35f

/**
 * Backup — 12 words (spec block `data-screen-label="Backup — 12 words"`): the two-column
 * word grid hidden behind an opaque reveal scrim (KTD: opacity must not depend on
 * `Modifier.blur` support, which is a no-op below Android API 31), the 60s re-hide
 * countdown line, and the outline "I've written them down" pill.
 */
@Composable
fun BackupScreen(
    backup: BackupModel,
    onBack: () -> Unit,
    onReveal: () -> Unit,
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
        ScreenBackButton(onBack = onBack)
        Text(
            text = "Write these\n12 words down.",
            style = LarkTheme.typography.screenTitle.copy(fontSize = 30.sp, lineHeight = 33.sp),
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopGap, bottom = TitleBottomGap),
        )
        Text(
            text = "They are the only way back to your money if you lose this phone. " +
                "On paper, not in photos.",
            style = LarkTheme.typography.body,
            color = LarkColors.TextPrimary.copy(alpha = SUBTITLE_ALPHA),
        )
        val wordsAvailable = backup.words.isNotEmpty()
        if (wordsAvailable) {
            Box(modifier = Modifier.padding(top = GridTopGap)) {
                WordGrid(words = backup.words)
                if (!backup.revealed) {
                    RevealScrim(onReveal = onReveal, modifier = Modifier.matchParentSize())
                }
            }
        } else {
            WordsUnavailableNotice(modifier = Modifier.padding(top = GridTopGap))
        }
        Spacer(modifier = Modifier.weight(1f))
        if (wordsAvailable && backup.revealed) {
            Text(
                text = "Hides itself in ${backup.countdown} seconds.",
                style = LarkTheme.typography.bodySmall,
                color = LarkColors.TextPrimary.copy(alpha = COUNTDOWN_ALPHA),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = CountdownBottomGap),
            )
        }
        OutlinePillButton(
            text = "I've written them down",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The words-unavailable state (plan R5, U6's named above-seam touch): the gateway answered 404
 * on the mnemonic — `--expose-mnemonic` is off — so there is no grid and nothing to reveal.
 */
@Composable
private fun WordsUnavailableNotice(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GridGap)) {
        Text(
            text = "Backup words aren't available from this gateway.",
            style = LarkTheme.typography.body,
            color = LarkColors.TextPrimary,
        )
        Text(
            text = "The gateway must run with mnemonic exposure enabled.",
            style = LarkTheme.typography.body,
            color = LarkColors.TextPrimary.copy(alpha = SUBTITLE_ALPHA),
        )
    }
}

/** The 2-column, 8dp-gap grid of numbered word cells. */
@Composable
private fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(GridGap)) {
        words.chunked(WORDS_PER_ROW).forEachIndexed { rowIndex, rowWords ->
            Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                rowWords.forEachIndexed { columnIndex, word ->
                    WordCell(
                        number = rowIndex * WORDS_PER_ROW + columnIndex + 1,
                        word = word,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One word cell: tabular index in a fixed 14dp slot, then the word. */
@Composable
private fun WordCell(number: Int, word: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(CellCornerRadius)
    Row(
        modifier = modifier
            .clip(shape)
            .background(LarkColors.Surface)
            .border(width = 1.dp, color = LarkColors.Separator, shape = shape)
            .padding(horizontal = CellHorizontalPadding, vertical = CellVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(CellGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number.toString(),
            style = LarkTheme.typography.caption.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                fontFeatureSettings = TABULAR_NUMERALS,
            ),
            color = LarkColors.TextPrimary.copy(alpha = NUMBER_ALPHA),
            modifier = Modifier.width(NumberWidth),
        )
        Text(
            text = word,
            style = LarkTheme.typography.itemTitle.copy(lineHeight = 15.sp),
            color = LarkColors.TextPrimary,
        )
    }
}

/**
 * The hidden state: an opaque background-colored panel over the grid (never a blur-only
 * veil) carrying the gold "Tap to reveal" pill.
 */
@Composable
private fun RevealScrim(onReveal: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CellCornerRadius))
            .background(LarkColors.Background)
            .clickableNoRipple(onReveal),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Tap to reveal",
            style = LarkTheme.typography.itemTitle.copy(lineHeight = 15.sp),
            color = LarkColors.OnGold,
            modifier = Modifier
                .clip(CircleShape)
                .background(LarkColors.Gold)
                .padding(
                    horizontal = RevealPillHorizontalPadding,
                    vertical = RevealPillVerticalPadding,
                ),
        )
    }
}
