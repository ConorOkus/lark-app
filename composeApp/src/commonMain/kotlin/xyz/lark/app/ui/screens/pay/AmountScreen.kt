package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.KeypadModel
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.LarkIconButton
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val TopRowHeight = 44.dp
private val TopRowHorizontalPadding = 10.dp
private val TopRowSpacerWidth = 44.dp
private val CenterHorizontalPadding = 20.dp
private val CenterGap = 10.dp
private val AvailabilityTopGap = 6.dp
private val ChipHorizontalPadding = 12.dp
private val ChipVerticalPadding = 8.dp
private val ChipGap = 7.dp
private val ChipArrowSize = 14.dp
private val SheetTopRadius = 24.dp
private val SheetTopPadding = 16.dp
private val SheetHorizontalPadding = 20.dp
private val SheetBottomPadding = 28.dp
private val PrimaryButtonHeight = 58.dp
private val KeyGridTopGap = 14.dp
private val KeyGap = 6.dp
private val KeyHeight = 62.dp
private val KeyCornerRadius = 14.dp
private val BackspaceIconSize = 26.dp

private const val HEADER_ALPHA = 0.55f
private const val EMPTY_AMOUNT_ALPHA = 0.3f
private const val CHIP_BG_ALPHA = 0.05f
private const val CHIP_ARROW_ALPHA = 0.4f
private const val AVAILABILITY_ALPHA = 0.4f
private const val BACKSPACE_ALPHA = 0.7f
private const val KEY_PRESSED_ALPHA = 0.12f

private val KeypadDigitRows = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
)

/**
 * Amount keypad (spec block `data-screen-label="Amount keypad"`): back + header row,
 * the centered amount with its unit-flip chip and availability line, and the #14161A
 * bottom sheet holding the primary pill and the 3-column digit grid.
 */
@Composable
fun AmountScreen(
    keypad: KeypadModel,
    machine: AppStateMachine,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(top = PayTopPadding)) {
        AmountTopRow(header = keypad.header, onBack = machine::back)
        AmountCenter(
            keypad = keypad,
            onToggleUnit = machine::toggleUnit,
            modifier = Modifier.weight(1f),
        )
        KeypadSheet(keypad = keypad, machine = machine)
    }
}

/** Back button left, the keypad header centered, a 44dp spacer balancing the right. */
@Composable
private fun AmountTopRow(header: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopRowHeight)
            .padding(horizontal = TopRowHorizontalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LarkIconButton(icon = LarkIcons.BackChevron, onClick = onBack, contentDescription = "Back")
        Text(
            text = header,
            style = LarkTheme.typography.itemTitle.copy(fontSize = 15.sp, lineHeight = 15.sp),
            color = LarkColors.TextPrimary.copy(alpha = HEADER_ALPHA),
        )
        Spacer(modifier = Modifier.width(TopRowSpacerWidth))
    }
}

/** The centered amount, the secondary/unit-flip chip, and the availability line. */
@Composable
private fun AmountCenter(
    keypad: KeypadModel,
    onToggleUnit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountColor = when {
        keypad.overBalance -> LarkColors.Warning
        keypad.digits.isEmpty() -> LarkColors.TextPrimary.copy(alpha = EMPTY_AMOUNT_ALPHA)
        else -> LarkColors.TextPrimary
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = CenterHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(CenterGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = keypad.amountDisplay,
            style = LarkTheme.typography.amountDisplay,
            color = amountColor,
        )
        SecondaryChip(secondary = keypad.amountSecondary, onToggleUnit = onToggleUnit)
        Text(
            text = keypad.availability,
            style = LarkTheme.typography.label,
            color = if (keypad.overBalance) {
                LarkColors.Warning
            } else {
                LarkColors.TextPrimary.copy(alpha = AVAILABILITY_ALPHA)
            },
            modifier = Modifier.padding(top = AvailabilityTopGap),
        )
    }
}

/** The secondary-amount chip flipping the leading unit (KTD-6). */
@Composable
private fun SecondaryChip(secondary: String, onToggleUnit: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = CHIP_BG_ALPHA))
            .clickableNoRipple(onToggleUnit)
            .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = secondary,
            style = LarkTheme.typography.label.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp,
                fontFeatureSettings = TABULAR_NUMERALS,
            ),
            color = LarkColors.TextSecondary,
        )
        Icon(
            imageVector = LarkIcons.UpDownArrows,
            contentDescription = null,
            modifier = Modifier.size(ChipArrowSize),
            tint = LarkColors.TextPrimary.copy(alpha = CHIP_ARROW_ALPHA),
        )
    }
}

/** The bottom sheet: primary pill (dimmed while disabled, AE2/AE3) over the digit grid. */
@Composable
private fun KeypadSheet(keypad: KeypadModel, machine: AppStateMachine) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = LarkColors.Surface,
                shape = RoundedCornerShape(topStart = SheetTopRadius, topEnd = SheetTopRadius),
            )
            .padding(
                start = SheetHorizontalPadding,
                top = SheetTopPadding,
                end = SheetHorizontalPadding,
                bottom = SheetBottomPadding,
            ),
    ) {
        GoldPillButton(
            text = keypad.primaryLabel,
            onClick = machine::keypadConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = keypad.primaryEnabled,
            height = PrimaryButtonHeight,
        )
        Spacer(modifier = Modifier.height(KeyGridTopGap))
        KeypadGrid(onKey = machine::keyPress, onBackspace = machine::backspace)
    }
}

/** The 3-column grid: 1–9, then blank / 0 / backspace. */
@Composable
private fun KeypadGrid(onKey: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        KeypadDigitRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEach { digit ->
                    DigitKey(digit = digit, onKey = onKey, modifier = Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            Spacer(modifier = Modifier.weight(1f))
            DigitKey(digit = '0', onKey = onKey, modifier = Modifier.weight(1f))
            KeypadCell(onClick = onBackspace, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = LarkIcons.Backspace,
                    contentDescription = "Backspace",
                    modifier = Modifier.size(BackspaceIconSize),
                    tint = LarkColors.TextPrimary.copy(alpha = BACKSPACE_ALPHA),
                )
            }
        }
    }
}

/** One digit key: Bricolage 600 26sp glyph in a 62dp cell. */
@Composable
private fun DigitKey(digit: Char, onKey: (Char) -> Unit, modifier: Modifier = Modifier) {
    KeypadCell(onClick = { onKey(digit) }, modifier = modifier) {
        Text(
            text = digit.toString(),
            style = LarkTheme.typography.screenTitle.copy(fontSize = 26.sp, lineHeight = 26.sp),
            color = LarkColors.TextPrimary,
        )
    }
}

/** A 62dp, 14dp-radius keypad cell; pressed state fills rgba(255,255,255,.12). */
@Composable
private fun KeypadCell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background = if (pressed) Color.White.copy(alpha = KEY_PRESSED_ALPHA) else Color.Transparent
    Box(
        modifier = modifier
            .height(KeyHeight)
            .clip(RoundedCornerShape(KeyCornerRadius))
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
