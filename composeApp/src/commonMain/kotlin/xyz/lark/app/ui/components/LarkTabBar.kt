package xyz.lark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.AppStateMachine
import xyz.lark.app.state.Route
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

/** Total height of the tab bar, top border included (`LARK Tab Bar.dc.html`). */
val TabBarHeight: Dp = 68.dp

private val TabBarHorizontalPadding = 12.dp
private val TabBarBottomPadding = 8.dp
private val TabBarItemGap = 4.dp
private val TabIconButtonSize = 48.dp
private val TabIconSize = 23.dp
private val TabPillHeight = 44.dp
private val TabBarBorderWidth = 1.dp

private const val TAB_ICON_ALPHA = 0.55f

/**
 * The three-slot tab bar of `docs/design/lark-wallet/LARK Tab Bar.dc.html`: leading scan
 * frame, two flex pills (WALLET / ACTIVITY, the [current] one filled #F4F1EA), trailing
 * menu. Pills reset the stack via [AppStateMachine.go]; scan pushes so back returns here.
 */
@Composable
fun LarkTabBar(
    current: Route,
    machine: AppStateMachine,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(TabBarHeight)
            .background(LarkColors.Background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TabBarBorderWidth)
                .background(LarkColors.Separator),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(
                    start = TabBarHorizontalPadding,
                    end = TabBarHorizontalPadding,
                    bottom = TabBarBottomPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(TabBarItemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabIconButton(
                icon = LarkIcons.ScanFrame,
                contentDescription = "Scan",
                onClick = { machine.push(Route.SCAN) },
            )
            TabPill(
                label = "WALLET",
                selected = current == Route.HOME,
                onClick = { machine.go(Route.HOME) },
                modifier = Modifier.weight(1f),
            )
            TabPill(
                label = "ACTIVITY",
                selected = current == Route.ACTIVITY,
                onClick = { machine.go(Route.ACTIVITY) },
                modifier = Modifier.weight(1f),
            )
            TabIconButton(
                icon = LarkIcons.Menu,
                contentDescription = "Settings",
                onClick = { machine.go(Route.SETTINGS) },
            )
        }
    }
}

/** 48dp square icon slot with a 23dp rgba(244,241,234,.55) stroke glyph. */
@Composable
private fun TabIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TabIconButtonSize)
            .clip(CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(TabIconSize),
            tint = LarkColors.TextPrimary.copy(alpha = TAB_ICON_ALPHA),
        )
    }
}

/** A flex tab pill — filled #F4F1EA with #0B0C0E text when selected, ghost otherwise. */
@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(TabPillHeight)
            .clip(CircleShape)
            .background(if (selected) LarkColors.TextPrimary else Color.Transparent)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LarkTheme.typography.itemTitle.copy(
                fontSize = 13.sp,
                lineHeight = 13.sp,
                letterSpacing = 0.1.em,
            ),
            color = if (selected) LarkColors.Background else LarkColors.TextTertiary,
        )
    }
}
