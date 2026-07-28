package xyz.lark.app.ui.screens.activity

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.lark.app.state.TxDetailModel
import xyz.lark.app.ui.components.KeyValueRow
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.RowGroupDivider
import xyz.lark.app.ui.components.ScreenBackButton
import xyz.lark.app.ui.components.SurfaceCard
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme
import xyz.lark.app.ui.theme.TABULAR_NUMERALS

private val AmountBlockTopGap = 20.dp
private val AmountLineGap = 6.dp
private val CardTopGap = 28.dp
private val TechRowRadius = 16.dp
private val TechRowPadding = 18.dp
private val TechChevronSize = 18.dp

private const val TECH_BORDER_ALPHA = 0.1f
private const val TECH_LABEL_ALPHA = 0.6f

/**
 * Transaction detail (spec block `data-screen-label="Transaction detail"`): back chevron,
 * verb + amounts, the To/From · When · Fee card, and the outline "Technical details" row.
 */
@Composable
fun TxDetailScreen(
    model: TxDetailModel,
    onBack: () -> Unit,
    onTechnicalDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = DetailHorizontalPadding,
                top = DetailTopPadding,
                end = DetailHorizontalPadding,
                bottom = DetailBottomPadding,
            ),
    ) {
        ScreenBackButton(onBack = onBack)
        Spacer(modifier = Modifier.height(AmountBlockTopGap))
        Text(
            text = model.verb,
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 15.sp,
            ),
            color = LarkColors.TextTertiary,
        )
        Spacer(modifier = Modifier.height(AmountLineGap))
        Text(
            text = model.amount,
            style = LarkTheme.typography.balance.copy(
                fontSize = 52.sp,
                lineHeight = 52.sp,
                letterSpacing = (-0.04).em,
            ),
            color = LarkColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(AmountLineGap))
        Text(
            text = model.secondaryAmount,
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 16.sp,
                fontFeatureSettings = TABULAR_NUMERALS,
            ),
            color = LarkColors.TextTertiary,
        )
        Spacer(modifier = Modifier.height(CardTopGap))
        SurfaceCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
        ) {
            KeyValueRow(label = model.partyLabel, value = model.party)
            RowGroupDivider()
            KeyValueRow(label = "When", value = model.whenLabel)
            RowGroupDivider()
            KeyValueRow(label = "Fee", value = model.fee)
        }
        Spacer(modifier = Modifier.weight(1f))
        TechnicalDetailsRow(onClick = onTechnicalDetails)
    }
}

/** The bottom outline row card leading into the technical-details screen. */
@Composable
private fun TechnicalDetailsRow(onClick: () -> Unit) {
    val shape = RoundedCornerShape(TechRowRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(width = 1.dp, color = Color.White.copy(alpha = TECH_BORDER_ALPHA), shape = shape)
            .clickableNoRipple(onClick)
            .padding(TechRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Technical details",
            style = LarkTheme.typography.itemTitle.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 15.sp,
            ),
            color = LarkColors.TextPrimary.copy(alpha = TECH_LABEL_ALPHA),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = LarkIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(TechChevronSize),
            tint = LarkColors.TextFaint,
        )
    }
}
