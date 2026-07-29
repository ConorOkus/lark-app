package xyz.lark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

/** Corner radius of grouped row lists. */
val RowGroupCornerRadius = 14.dp

private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 14.dp

/** One label/value line of a [StatRowGroup]. */
@Immutable
data class StatRow(
    val label: String,
    val value: String,
    val valueColor: Color = Color.Unspecified,
)

/**
 * The grouped list container of the design: 14dp radius, #14161A surface,
 * 1dp rgba(255,255,255,.07) border. Rows inside should be separated with
 * [RowGroupDivider].
 */
@Composable
fun RowGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(RowGroupCornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LarkColors.Surface)
            .border(width = 1.dp, color = LarkColors.Border, shape = shape),
        content = content,
    )
}

/** A grouped stat-row list: [rows] rendered as [KeyValueRow]s with 1px separators. */
@Composable
fun StatRowGroup(
    rows: List<StatRow>,
    modifier: Modifier = Modifier,
) {
    RowGroup(modifier = modifier) {
        rows.forEachIndexed { index, row ->
            if (index > 0) RowGroupDivider()
            KeyValueRow(label = row.label, value = row.value, valueColor = row.valueColor)
        }
    }
}

/** A single key-value row: label left in rgba(244,241,234,.5), value right in #F4F1EA. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LarkTheme.typography.label,
            color = LarkColors.TextSecondary,
        )
        Spacer(modifier = Modifier.width(12.dp).weight(1f))
        Text(
            text = value,
            style = LarkTheme.typography.itemTitle,
            color = valueColor.takeOrElse { LarkColors.TextPrimary },
            textAlign = TextAlign.End,
        )
    }
}

/** The 1px rgba(255,255,255,.06) separator between rows of a [RowGroup]. */
@Composable
fun RowGroupDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LarkColors.Separator),
    )
}

/** Section eyebrow label: 11sp, 700, .14em tracking, uppercase, rgba(244,241,234,.35). */
@Composable
fun SectionEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = LarkTheme.typography.eyebrow,
        color = LarkColors.TextQuaternary,
        modifier = modifier,
    )
}
