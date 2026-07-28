package xyz.lark.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.theme.LarkColors

private const val FULL_SWEEP = 360f
private const val ARC_SWEEP = 90f
private const val SPIN_DURATION_MS = 900

/**
 * The gold ring spinner of the design (`lk-spin`): a 2dp rgba(232,193,92,.2) track
 * with a rotating #E8C15C arc.
 */
@Composable
fun GoldSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    strokeWidth: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "GoldSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_SWEEP,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "GoldSpinnerAngle",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = LarkColors.GoldTrack,
            startAngle = 0f,
            sweepAngle = FULL_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = LarkColors.Gold,
            startAngle = angle,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}
