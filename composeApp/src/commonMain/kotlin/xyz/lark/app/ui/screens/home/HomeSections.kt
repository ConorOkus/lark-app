package xyz.lark.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.core.model.BannerCopy
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val SectionHorizontalPadding = 20.dp
private val BannerBottomMargin = 12.dp
private val BannerRadius = 16.dp
private val BannerPadding = 16.dp
private val BannerGap = 14.dp
private val BannerIconCircleSize = 34.dp
private val BannerIconSize = 18.dp
private val BannerChevronSize = 18.dp
private val ActionRowBottomPadding = 12.dp
private val TileGap = 12.dp
private val TileHeight = 84.dp
private val TileRadius = 20.dp
private val TilePadding = 16.dp
private val TileIconSize = 22.dp

private const val BANNER_BG_ALPHA = 0.10f
private const val BANNER_BORDER_ALPHA = 0.28f
private const val BANNER_ICON_BG_ALPHA = 0.18f

/** Stale/offline attention banner — orange-tinted when offline, gold when stale. */
@Composable
internal fun AttentionBanner(banner: BannerCopy, offline: Boolean, onClick: () -> Unit) {
    val tint = if (offline) LarkColors.Warning else LarkColors.Gold
    val shape = RoundedCornerShape(BannerRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SectionHorizontalPadding,
                end = SectionHorizontalPadding,
                bottom = BannerBottomMargin,
            )
            .clip(shape)
            .background(tint.copy(alpha = BANNER_BG_ALPHA))
            .border(width = 1.dp, color = tint.copy(alpha = BANNER_BORDER_ALPHA), shape = shape)
            .clickableNoRipple(onClick)
            .padding(BannerPadding),
        horizontalArrangement = Arrangement.spacedBy(BannerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(BannerIconCircleSize)
                .clip(CircleShape)
                .background(tint.copy(alpha = BANNER_ICON_BG_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LarkIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(BannerIconSize),
                tint = tint,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = banner.title,
                style = LarkTheme.typography.itemTitle.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = LarkColors.TextPrimary,
            )
            Text(
                text = banner.body,
                style = LarkTheme.typography.bodySmall,
                color = LarkColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = LarkIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(BannerChevronSize),
            tint = LarkColors.TextFaint,
        )
    }
}

/** The gold Pay tile and the outline Get paid tile. */
@Composable
internal fun ActionTiles(onPay: () -> Unit, onGetPaid: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SectionHorizontalPadding,
                end = SectionHorizontalPadding,
                bottom = ActionRowBottomPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        ActionTile(
            label = "Pay",
            icon = LarkIcons.ArrowUpRight,
            gold = true,
            onClick = onPay,
            modifier = Modifier.weight(1f),
        )
        ActionTile(
            label = "Get paid",
            icon = LarkIcons.ArrowDownLeft,
            gold = false,
            onClick = onGetPaid,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One 84dp action tile: icon top-left, label bottom-left. One gold moment per screen. */
@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    gold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TileRadius)
    val contentColor = if (gold) LarkColors.OnGold else LarkColors.TextPrimary
    val borderModifier = if (gold) {
        Modifier
    } else {
        Modifier.border(width = 1.dp, color = LarkColors.BorderStrong, shape = shape)
    }
    Column(
        modifier = modifier
            .height(TileHeight)
            .clip(shape)
            .background(if (gold) LarkColors.Gold else Color.Transparent)
            .then(borderModifier)
            .clickableNoRipple(onClick)
            .padding(TilePadding),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(TileIconSize),
            tint = contentColor,
        )
        Text(
            text = label,
            style = LarkTheme.typography.sectionTitle.copy(fontSize = 18.sp, lineHeight = 18.sp),
            color = contentColor,
        )
    }
}
