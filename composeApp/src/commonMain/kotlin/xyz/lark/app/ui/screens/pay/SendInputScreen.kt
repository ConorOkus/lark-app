package xyz.lark.app.ui.screens.pay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.core.model.Contact
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import xyz.lark.app.state.SendModel
import xyz.lark.app.ui.components.GoldPillButton
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TopRowHeight = 44.dp
private val TitleTopGap = 16.dp
private val TitleBottomGap = 20.dp
private val ScanIconSize = 23.dp
private val InputCardRadius = 16.dp
private val InputCardPadding = 18.dp
private val InputCardMinHeight = 60.dp
private val InputCardGap = 12.dp
private val HintTopGap = 10.dp
private val ContinueTopGap = 22.dp
private val ContinueHeight = 60.dp
private val RecentEyebrowTopGap = 34.dp
private val RecentListTopGap = 8.dp
private val RecentRowGap = 14.dp
private val RecentRowVerticalPadding = 12.dp
private val RecentCircleSize = 38.dp

private const val RESOLVED_BORDER_ALPHA = 0.4f
private const val UNRESOLVED_BORDER_ALPHA = 0.08f
private const val PLACEHOLDER_ALPHA = 0.32f
private const val HINT_ALPHA = 0.38f
private const val HANDLE_ALPHA = 0.4f
private const val GOLD_CIRCLE_ALPHA = 0.18f
private const val PLAIN_CIRCLE_ALPHA = 0.08f

/**
 * Pay — who (spec block `data-screen-label="Pay — who"`): back + scan buttons, the
 * "Who are you paying?" title, the paste-to-resolve input card with its hint, the
 * Continue pill once the input resolves, and the RECENT payees list.
 */
@Composable
fun SendInputScreen(
    model: AppModel,
    machine: AppStateMachine,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = PayHorizontalPadding,
                top = PayTopPadding,
                end = PayHorizontalPadding,
                bottom = PayBottomPadding,
            ),
    ) {
        SendInputTopRow(onBack = machine::back, onScan = { machine.push(Route.SCAN) })
        Text(
            text = "Who are you\npaying?",
            style = LarkTheme.typography.screenTitle.copy(fontSize = 30.sp, lineHeight = 33.sp),
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(top = TitleTopGap, bottom = TitleBottomGap),
        )
        InputCard(send = model.send, onClick = machine::pasteInvoice)
        Text(
            text = "A name, an invoice, or a bitcoin address — LARK works out the rest.",
            style = LarkTheme.typography.bodySmall,
            color = LarkColors.TextPrimary.copy(alpha = HINT_ALPHA),
            modifier = Modifier.padding(top = HintTopGap),
        )
        if (model.send.inputResolved) {
            GoldPillButton(
                text = "Continue",
                onClick = machine::goSendAmount,
                modifier = Modifier.fillMaxWidth().padding(top = ContinueTopGap),
                height = ContinueHeight,
            )
        }
        Text(
            text = "RECENT",
            style = LarkTheme.typography.eyebrow,
            color = LarkColors.TextFaint,
            modifier = Modifier.padding(top = RecentEyebrowTopGap),
        )
        Column(modifier = Modifier.padding(top = RecentListTopGap)) {
            model.recents.forEachIndexed { index, contact ->
                RecentRow(contact = contact, gold = index == 0, onClick = { machine.pickRecent(contact) })
            }
        }
    }
}

/** Back chevron left, scan-frame button right, both nudged 10dp into the margins. */
@Composable
private fun SendInputTopRow(onBack: () -> Unit, onScan: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(TopRowHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PayBackButton(onBack = onBack)
        PayIconButton(
            icon = LarkIcons.ScanFrame,
            contentDescription = "Scan",
            onClick = onScan,
            modifier = Modifier.offset(x = PayIconButtonInset),
            iconSize = ScanIconSize,
        )
    }
}

/** The recipient input card: gold border once resolved, trailing PASTE while empty. */
@Composable
private fun InputCard(send: SendModel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(InputCardRadius)
    val borderColor = if (send.inputResolved) {
        LarkColors.Gold.copy(alpha = RESOLVED_BORDER_ALPHA)
    } else {
        Color.White.copy(alpha = UNRESOLVED_BORDER_ALPHA)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InputCardMinHeight)
            .clip(shape)
            .background(LarkColors.Surface)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(InputCardPadding),
        horizontalArrangement = Arrangement.spacedBy(InputCardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textColor = if (send.inputResolved) {
            LarkColors.TextPrimary
        } else {
            LarkColors.TextPrimary.copy(alpha = PLACEHOLDER_ALPHA)
        }
        Text(
            text = send.inputDisplay,
            style = LarkTheme.typography.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        if (!send.inputResolved) {
            Text(
                text = "PASTE",
                style = LarkTheme.typography.itemTitle.copy(
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    letterSpacing = 0.06.em,
                ),
                color = LarkColors.Gold,
            )
        }
    }
}

/** One recent payee row: 38dp initial circle (gold-tinted for the first), who + handle. */
@Composable
private fun RecentRow(contact: Contact, gold: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = RecentRowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(RecentRowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecentInitialCircle(initial = contact.initial, gold = gold)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.who,
                style = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 21.sp),
                color = LarkColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = contact.handle,
                style = LarkTheme.typography.bodySmall,
                color = LarkColors.TextPrimary.copy(alpha = HANDLE_ALPHA),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The 38dp leading circle with the payee's initial; gold tint on the first recent. */
@Composable
private fun RecentInitialCircle(initial: String, gold: Boolean) {
    val background = if (gold) {
        LarkColors.Gold.copy(alpha = GOLD_CIRCLE_ALPHA)
    } else {
        Color.White.copy(alpha = PLAIN_CIRCLE_ALPHA)
    }
    Box(
        modifier = Modifier
            .size(RecentCircleSize)
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
