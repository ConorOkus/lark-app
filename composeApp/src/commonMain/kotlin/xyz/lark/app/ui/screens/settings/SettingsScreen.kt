package xyz.lark.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.AppModel
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import xyz.lark.app.ui.components.HealthDot
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val TitleTopPadding = 16.dp
private val TitleBottomPadding = 22.dp
private val ContentGap = 24.dp
private val GroupCornerRadius = 16.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 17.dp
private val RowGap = 14.dp
private val SubtitleGap = 3.dp
private val ChevronSize = 18.dp
private val TrailingGap = 8.dp
private val HealthDotSize = 7.dp

private const val SUBTITLE_ALPHA = 0.42f
private const val UNIT_LABEL_ALPHA = 0.55f
private const val HEALTH_WORD_ALPHA = 0.5f
private const val FOOTER_ALPHA = 0.28f

/**
 * Settings (spec block `data-screen-label="Settings"`): back chevron, the big Settings
 * title, the backup / unit / status group, the Advanced group, and the version footer.
 */
@Composable
fun SettingsScreen(
    model: AppModel,
    machine: AppStateMachine,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = SettingsTopPadding, bottom = SettingsBottomPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.padding(horizontal = SettingsHorizontalPadding)) {
            ScreenBackButton(onBack = machine::back)
        }
        Text(
            text = "Settings",
            style = LarkTheme.typography.screenTitle.copy(fontSize = 32.sp, lineHeight = 32.sp),
            color = LarkColors.TextPrimary,
            modifier = Modifier.padding(
                start = SettingsHorizontalPadding,
                top = TitleTopPadding,
                end = SettingsHorizontalPadding,
                bottom = TitleBottomPadding,
            ),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(ContentGap),
        ) {
            MainGroup(model = model, machine = machine)
            // Only shown by a core that can board. Without it the on-chain deposit screen would be
            // reachable during onboarding and never again — which is exactly when a tester who
            // skipped funding, or spent everything, needs it.
            if (model.deposit != null) {
                SettingsGroup {
                    ChevronRow(
                        title = "Add money",
                        subtitle = "Move bitcoin in from another wallet",
                        subtitleColor = LarkColors.TextPrimary.copy(alpha = SUBTITLE_ALPHA),
                        onClick = machine::goDeposit,
                    )
                }
            }
            SettingsGroup {
                ChevronRow(
                    title = "Advanced",
                    subtitle = "The machinery, for people who want it",
                    subtitleColor = LarkColors.TextPrimary.copy(alpha = SUBTITLE_ALPHA),
                    onClick = { machine.push(Route.ADVANCED) },
                )
            }
            Footer(networkLabel = model.networkLabel)
        }
    }
}

/** Group one: backup row with status subtitle, the unit toggle, and the wallet-status row. */
@Composable
private fun MainGroup(model: AppModel, machine: AppStateMachine) {
    SettingsGroup {
        ChevronRow(
            title = "Back up your wallet",
            subtitle = model.backup.statusLabel,
            subtitleColor = if (model.backup.backedUp) {
                LarkColors.TextPrimary.copy(alpha = SUBTITLE_ALPHA)
            } else {
                LarkColors.Warning
            },
            onClick = { machine.push(Route.BACKUP) },
        )
        RowGroupDivider()
        UnitRow(unitLabel = model.balance.unitLabel, onToggleUnit = machine::toggleUnit)
        RowGroupDivider()
        StatusRow(
            dotColorHex = model.health.dotColorHex,
            word = model.health.word,
            onClick = { machine.push(Route.HEALTH) },
        )
    }
}

/** The grouped settings container: 16dp radius, #14161A surface, hairline border. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(GroupCornerRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LarkColors.Surface)
            .border(width = 1.dp, color = LarkColors.Border, shape = shape),
        content = content,
    )
}

/** A pressable row: 600 16sp title, optional 13sp subtitle, trailing chevron. */
@Composable
private fun ChevronRow(
    title: String,
    subtitle: String?,
    subtitleColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SubtitleGap),
        ) {
            Text(text = title, style = rowTitleStyle(), color = LarkColors.TextPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = LarkTheme.typography.bodySmall, color = subtitleColor)
            }
        }
        Icon(
            imageVector = LarkIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(ChevronSize),
            tint = LarkColors.TextFaint,
        )
    }
}

/** "Show amounts in" with the tappable current unit label on the right. */
@Composable
private fun UnitRow(unitLabel: String, onToggleUnit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Show amounts in",
            style = rowTitleStyle(),
            color = LarkColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = unitLabel,
            style = LarkTheme.typography.itemTitle.copy(lineHeight = 15.sp),
            color = LarkColors.TextPrimary.copy(alpha = UNIT_LABEL_ALPHA),
            modifier = Modifier
                .clip(CircleShape)
                .clickableNoRipple(onToggleUnit),
        )
    }
}

/** "Wallet status" with the 7dp health dot and state word on the right. */
@Composable
private fun StatusRow(dotColorHex: String, word: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Wallet status",
            style = rowTitleStyle(),
            color = LarkColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(TrailingGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthDot(colorHex = dotColorHex, size = HealthDotSize)
            Text(
                text = word,
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    lineHeight = 15.sp,
                ),
                color = LarkColors.TextPrimary.copy(alpha = HEALTH_WORD_ALPHA),
            )
        }
    }
}

/**
 * The centered version footer: `LARK 0.4.1 · {network}`.
 *
 * The protocol names it used to carry moved to Advanced. Settings is an ordinary screen, and
 * naming the machinery here asks the user to know what an Ark is to read a version string.
 */
@Composable
private fun Footer(networkLabel: String) {
    Text(
        text = "LARK 0.4.1 · $networkLabel",
        style = LarkTheme.typography.caption,
        color = LarkColors.TextPrimary.copy(alpha = FOOTER_ALPHA),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Manrope 600 16/1.2 — the settings row title tier. */
@Composable
private fun rowTitleStyle() = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 19.sp)
