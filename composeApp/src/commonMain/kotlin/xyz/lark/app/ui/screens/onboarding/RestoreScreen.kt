package xyz.lark.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.core.MnemonicInput
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 24.dp
private val TitleBottomPadding = 8.dp
private val InputTopGap = 28.dp
private val InputPadding = 18.dp
private val InputMinHeight = 120.dp
private val StatusTopGap = 12.dp

private const val WORDS_HINT = "tide margin ocean …"

/**
 * Restore from 12 words (spec block `data-screen-label="Restore from 12 words"`).
 *
 * The phrase lives in this composable's own state and nowhere else — not in the app-wide model.
 * That is on purpose: the model is a long-lived StateFlow, and a recovery phrase is the wallet, so
 * it should exist for exactly as long as this screen does and no longer.
 *
 * [onRestore] receives the parsed words and answers whether a wallet opened; false is shown here
 * rather than navigating, because the user's next move is to check what they typed.
 */
@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    onRestore: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    busy: Boolean = false,
) {
    var typed by remember { mutableStateOf("") }
    val words = MnemonicInput.words(typed)
    val plausible = MnemonicInput.isPlausible(typed)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = OnboardingHorizontalPadding,
                top = OnboardingTopPadding,
                end = OnboardingHorizontalPadding,
                bottom = OnboardingBottomPadding,
            ),
    ) {
        ScreenBackButton(onBack = onBack)
        Text(
            text = "Type your\n12 words.",
            style = LarkTheme.typography.screenTitle,
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopPadding, bottom = TitleBottomPadding),
        )
        Text(
            text = "In order, separated by spaces. LARK rebuilds the rest.",
            style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = LarkColors.TextSecondary,
        )
        WordsField(typed = typed, onTyped = { typed = it })
        Text(
            text = statusLine(words.size, plausible = plausible, failed = failed, busy = busy),
            style = LarkTheme.typography.body.copy(fontSize = 14.sp),
            color = if (failed) LarkColors.TextPrimary else LarkColors.TextSecondary,
            modifier = Modifier.padding(top = StatusTopGap),
        )
        Spacer(modifier = Modifier.weight(1f))
        GoldPillButton(
            text = if (busy) "Restoring…" else "Restore wallet",
            onClick = { onRestore(words) },
            modifier = Modifier.fillMaxWidth(),
            enabled = plausible && !busy,
        )
    }
}

/** The phrase input: a hint when empty, and a field that does not fight the user. */
@Composable
private fun WordsField(typed: String, onTyped: (String) -> Unit) {
    SurfaceCard(
        modifier = Modifier
            .padding(top = InputTopGap)
            .fillMaxWidth()
            .heightIn(min = InputMinHeight),
        contentPadding = PaddingValues(InputPadding),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (typed.isEmpty()) {
                Text(
                    text = WORDS_HINT,
                    style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 27.sp),
                    color = LarkColors.TextQuaternary,
                )
            }
            BasicTextField(
                value = typed,
                onValueChange = onTyped,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LarkTheme.typography.body.copy(
                    fontSize = 16.sp,
                    lineHeight = 27.sp,
                    color = LarkColors.TextPrimary,
                ),
                cursorBrush = SolidColor(LarkColors.Gold),
                // A phrase is lowercase words: autocorrect and capitalisation actively fight the
                // user here, and a "corrected" word is a phrase that will not open.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
            )
        }
    }
}

/**
 * The one line under the input. Counts up while typing, warns after a failed attempt, and says
 * nothing at all before anything is typed.
 */
private fun statusLine(count: Int, plausible: Boolean, failed: Boolean, busy: Boolean): String = when {
    busy -> "Opening your wallet. This can take a minute."
    failed -> "That phrase did not open a wallet. Check the words and the order, then try again."
    count == 0 -> ""
    plausible -> "$count words. Ready."
    else -> "$count words so far."
}
