package xyz.lark.app.ui.screens.pay

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.lark.app.ui.components.IconButtonInset
import xyz.lark.app.ui.components.LarkIconButton
import xyz.lark.app.ui.components.LarkIcons
import xyz.lark.app.ui.components.clickableNoRipple
import xyz.lark.app.ui.theme.LarkColors
import xyz.lark.app.ui.theme.LarkTheme

private val CloseIconSize = 24.dp
private val FrameSize = 250.dp
private val FrameCornerRadius = 28.dp
private val FrameBorderWidth = 2.dp
private val ScanLineHeight = 2.dp
private val ScanGlowHeight = 14.dp
private val CaptionGap = 28.dp
private val ScanBottomPadding = 46.dp
private val SimulateButtonHeight = 56.dp

private const val FRAME_BORDER_ALPHA = 0.25f
private const val CAPTION_ALPHA = 0.65f
private const val SIMULATE_BG_ALPHA = 0.12f
private const val GLOW_ALPHA = 0.3f
private const val PULSE_MIN_ALPHA = 0.35f
private const val PULSE_HALF_MS = 800
private const val GRADIENT_CENTER_Y = 0.42f
private const val GRADIENT_RADIUS_FRACTION = 0.7f

@Suppress("MagicNumber")
private val GradientInner = Color(0xFF20242B)

/** CSS `ease-in-out`, driving the spec's `lk-pulse` scan-line animation. */
@Suppress("MagicNumber")
private val PulseEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * Scan (spec block `data-screen-label="Scan"`): a full-black viewfinder with a radial
 * glow, a close button, the 250dp scan frame with its pulsing gold line, and the
 * demo-only "Simulate a scan" pill.
 */
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onScanFound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(GradientInner, LarkColors.Background),
                        center = Offset(x = size.width / 2f, y = size.height * GRADIENT_CENTER_Y),
                        radius = size.height * GRADIENT_RADIUS_FRACTION,
                    ),
                )
            },
    ) {
        Box(
            modifier = Modifier.padding(
                start = PayHorizontalPadding,
                top = PayTopPadding,
                end = PayHorizontalPadding,
            ),
        ) {
            LarkIconButton(
                icon = LarkIcons.Close,
                onClick = onClose,
                modifier = Modifier.offset(x = -IconButtonInset),
                contentDescription = "Close",
                iconSize = CloseIconSize,
                tint = Color.White,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(CaptionGap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanFrame()
            Text(
                text = "Point at any payment code",
                style = LarkTheme.typography.itemTitle.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                ),
                color = Color.White.copy(alpha = CAPTION_ALPHA),
            )
        }
        SimulateScanButton(
            onClick = onScanFound,
            modifier = Modifier.padding(
                start = PayHorizontalPadding,
                end = PayHorizontalPadding,
                bottom = ScanBottomPadding,
            ),
        )
    }
}

/** The 250dp rounded frame with the alpha-pulsing gold scan line and its glow band. */
@Composable
private fun ScanFrame() {
    val transition = rememberInfiniteTransition(label = "ScanPulse")
    val pulse = transition.animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_HALF_MS, easing = PulseEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ScanPulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(FrameSize)
            .border(
                width = FrameBorderWidth,
                color = Color.White.copy(alpha = FRAME_BORDER_ALPHA),
                shape = RoundedCornerShape(FrameCornerRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = pulse.value },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScanGlowHeight)
                    .background(LarkColors.Gold.copy(alpha = GLOW_ALPHA), CircleShape),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScanLineHeight)
                    .background(LarkColors.Gold),
            )
        }
    }
}

/** The bottom rgba(255,255,255,.12) pill firing the simulated scan. */
@Composable
private fun SimulateScanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SimulateButtonHeight)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = SIMULATE_BG_ALPHA))
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Simulate a scan",
            style = LarkTheme.typography.itemTitle.copy(fontSize = 16.sp, lineHeight = 16.sp),
            color = Color.White,
        )
    }
}
