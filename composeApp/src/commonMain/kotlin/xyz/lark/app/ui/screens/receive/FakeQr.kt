package xyz.lark.app.ui.screens.receive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import xyz.lark.app.ui.theme.LarkColors

/** Cells per side of the decorative code (the spec's `repeat(21,1fr)` grid). */
internal const val QR_GRID = 21

// LCG parameters of the spec's `qrCells` script. The arithmetic deliberately runs in
// [Double]s: the reference implementation is JavaScript, whose numbers are IEEE 754
// doubles, and `seed * 1103515245` overflows the 53-bit mantissa — exact integer math
// would drift from the reference pattern after the first few cells (KTD-7).
private const val LCG_SEED = 7.0
private const val LCG_MULTIPLIER = 1103515245.0
private const val LCG_INCREMENT = 12345.0
private const val LCG_MODULUS = 2147483648.0

private const val NOISE_SHIFT = 7
private const val NOISE_RANGE = 100
private const val NOISE_ON_ABOVE = 52

private const val FINDER_SIZE = 7
private const val FINDER_CENTER_START = 2
private const val FINDER_CENTER_END = 5
private const val FINDER_FAR = 14

private val QrCardRadius = 28.dp
private val QrCardPadding = 18.dp
private val QrSize = 246.dp

/**
 * Row-major on/off cells of the deterministic fake QR: three finder squares at (0,0),
 * (14,0) and (0,14) over LCG noise, ported faithfully from the spec's `qrCells` loop —
 * including its `&&`-over-`||` precedence (ring OR center) and one LCG step per cell
 * regardless of whether the noise term is consulted.
 */
internal fun fakeQrCells(): List<Boolean> {
    var seed = LCG_SEED
    return List(QR_GRID * QR_GRID) { i ->
        seed = (seed * LCG_MULTIPLIER + LCG_INCREMENT) % LCG_MODULUS
        val x = i % QR_GRID
        val y = i / QR_GRID
        val noise = (seed.toLong() shr NOISE_SHIFT) % NOISE_RANGE > NOISE_ON_ABOVE
        isFinderCell(x, y, 0, 0) ||
            isFinderCell(x, y, FINDER_FAR, 0) ||
            isFinderCell(x, y, 0, FINDER_FAR) ||
            noise
    }
}

/** The 7x7 outer ring or the 3x3 center of the finder square anchored at ([fx], [fy]). */
private fun isFinderCell(x: Int, y: Int, fx: Int, fy: Int): Boolean {
    val inSquare = x - fx in 0 until FINDER_SIZE && y - fy in 0 until FINDER_SIZE
    val inInnerBand = x - fx in 1 until FINDER_SIZE - 1 && y - fy in 1 until FINDER_SIZE - 1
    val inCenter = x - fx in FINDER_CENTER_START until FINDER_CENTER_END &&
        y - fy in FINDER_CENTER_START until FINDER_CENTER_END
    return (inSquare && !inInnerBand) || inCenter
}

/**
 * The get-paid screen's 246dp fake QR, drawn inside its 18dp-padded white rounded card
 * (the spec's `padding:18px;border-radius:28px;background:#fff` wrapper).
 */
@Composable
internal fun FakeQr(modifier: Modifier = Modifier) {
    val cells = remember { fakeQrCells() }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(QrCardRadius))
            .background(Color.White)
            .padding(QrCardPadding)
            .size(QrSize),
    ) {
        // Shared cell edges so adjacent on-cells tile without seams.
        val edges = FloatArray(QR_GRID + 1) { it * size.width / QR_GRID }
        cells.forEachIndexed { i, on ->
            if (on) {
                val x = i % QR_GRID
                val y = i / QR_GRID
                drawRect(
                    color = LarkColors.Background,
                    topLeft = Offset(edges[x], edges[y]),
                    size = Size(edges[x + 1] - edges[x], edges[y + 1] - edges[y]),
                )
            }
        }
    }
}
