@file:Suppress("MagicNumber")

package xyz.lark.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The LARK Wallet stroke icon set, hand-ported from the inline SVGs of
 * `docs/design/lark-wallet/LARK Wallet.dc.html` and `LARK Tab Bar.dc.html`.
 *
 * All icons use a 24x24 viewport with round caps/joins and are drawn in white so the
 * `Icon` tint at the call site decides the color.
 */
object LarkIcons {

    /** `M15 5l-7 7 7 7` — back navigation. */
    val BackChevron: ImageVector by lazy {
        strokeIcon("BackChevron") {
            moveTo(15f, 5f)
            lineToRelative(-7f, 7f)
            lineToRelative(7f, 7f)
        }
    }

    /** `M9 5l7 7-7 7` — trailing list chevron. */
    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            moveTo(9f, 5f)
            lineToRelative(7f, 7f)
            lineToRelative(-7f, 7f)
        }
    }

    /** `M6 6l12 12M18 6L6 18` — close / failed. */
    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            moveTo(6f, 6f)
            lineToRelative(12f, 12f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    /** Four corner brackets and a scan line — the scan tab / scanner. */
    val ScanFrame: ImageVector by lazy {
        strokeIcon("ScanFrame") {
            moveTo(4f, 8f)
            verticalLineTo(6f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
            horizontalLineToRelative(2f)
            moveTo(16f, 4f)
            horizontalLineToRelative(2f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 2f)
            verticalLineToRelative(2f)
            moveTo(20f, 16f)
            verticalLineToRelative(2f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, 2f)
            horizontalLineToRelative(-2f)
            moveTo(8f, 20f)
            horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
            verticalLineToRelative(-2f)
            moveTo(4f, 12f)
            horizontalLineToRelative(16f)
        }
    }

    /** `M7 17L17 7` + `M8 7h9v9` — pay / outgoing. */
    val ArrowUpRight: ImageVector by lazy {
        strokeIcon("ArrowUpRight", strokeWidth = 2f) {
            moveTo(7f, 17f)
            lineTo(17f, 7f)
            moveTo(8f, 7f)
            horizontalLineToRelative(9f)
            verticalLineToRelative(9f)
        }
    }

    /** `M17 7L7 17` + `M16 17H7V8` — receive / incoming. */
    val ArrowDownLeft: ImageVector by lazy {
        strokeIcon("ArrowDownLeft", strokeWidth = 2f) {
            moveTo(17f, 7f)
            lineTo(7f, 17f)
            moveTo(16f, 17f)
            horizontalLineTo(7f)
            verticalLineTo(8f)
        }
    }

    /** `M12 19V5M5 12l7-7 7 7` — send confirm arrow. */
    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp") {
            moveTo(12f, 19f)
            verticalLineTo(5f)
            moveTo(5f, 12f)
            lineToRelative(7f, -7f)
            lineToRelative(7f, 7f)
        }
    }

    /** `M5 13l4 4L19 7` — success check. */
    val Check: ImageVector by lazy {
        strokeIcon("Check", strokeWidth = 2.4f) {
            moveTo(5f, 13f)
            lineToRelative(4f, 4f)
            lineTo(19f, 7f)
        }
    }

    /** `M13 2L4.5 13.5H11l-1 8.5L19 10h-6.5z` — lightning bolt. */
    val Bolt: ImageVector by lazy {
        strokeIcon("Bolt") {
            moveTo(13f, 2f)
            lineTo(4.5f, 13.5f)
            horizontalLineTo(11f)
            lineToRelative(-1f, 8.5f)
            lineTo(19f, 10f)
            horizontalLineTo(12.5f)
            close()
        }
    }

    /** `M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z` — shield. */
    val Shield: ImageVector by lazy {
        strokeIcon("Shield") {
            moveTo(12f, 3f)
            lineToRelative(7f, 3f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 4.5f, -3f, 7.5f, -7f, 9f)
            curveToRelative(-4f, -1.5f, -7f, -4.5f, -7f, -9f)
            verticalLineTo(6f)
            close()
        }
    }

    /** Two arcs with arrow heads — refresh / renew. */
    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            moveTo(4f, 12f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13.7f, -5.6f)
            moveTo(20f, 12f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -13.7f, 5.6f)
            moveTo(18f, 3f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(-4f)
            moveTo(6f, 21f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(4f)
        }
    }

    /** Keypad delete key. */
    val Backspace: ImageVector by lazy {
        strokeIcon("Backspace", strokeWidth = 1.8f) {
            moveTo(20f, 5f)
            horizontalLineTo(9.5f)
            lineTo(3f, 12f)
            lineToRelative(6.5f, 7f)
            horizontalLineTo(20f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1f, -1f)
            verticalLineTo(6f)
            arcToRelative(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, -1f, -1f)
            close()
            moveTo(15f, 9.5f)
            lineToRelative(-5f, 5f)
            moveTo(10f, 9.5f)
            lineToRelative(5f, 5f)
        }
    }

    /** Rounded card outline with a stripe — add money / card. */
    val Card: ImageVector by lazy {
        strokeIcon("Card") {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
            verticalLineToRelative(7f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, 3f)
            horizontalLineTo(6f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, -3f)
            verticalLineToRelative(-7f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, -3f)
            close()
            moveTo(3f, 10f)
            horizontalLineToRelative(18f)
        }
    }

    /** `M4 7h16M4 12h16M4 17h16` — settings tab (hamburger). */
    val Menu: ImageVector by lazy {
        strokeIcon("Menu", strokeWidth = 1.8f) {
            moveTo(4f, 7f)
            horizontalLineToRelative(16f)
            moveTo(4f, 12f)
            horizontalLineToRelative(16f)
            moveTo(4f, 17f)
            horizontalLineToRelative(16f)
        }
    }

    /** Circled `i` — banners and info rows. */
    val Info: ImageVector by lazy {
        strokeIcon("Info", strokeWidth = 1.8f) {
            moveTo(12f, 8f)
            verticalLineToRelative(5f)
            moveTo(12f, 17f)
            horizontalLineToRelative(0.01f)
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, -18f, 0f)
        }
    }

    /** `M7 10l5-5 5 5M7 14l5 5 5-5` — denomination toggle. */
    val UpDownArrows: ImageVector by lazy {
        strokeIcon("UpDownArrows", strokeWidth = 2f) {
            moveTo(7f, 10f)
            lineToRelative(5f, -5f)
            lineToRelative(5f, 5f)
            moveTo(7f, 14f)
            lineToRelative(5f, 5f)
            lineToRelative(5f, -5f)
        }
    }
}

/** Builds a 24x24 stroke-only [ImageVector] in the design's stroke style. */
private fun strokeIcon(
    name: String,
    strokeWidth: Float = 1.7f,
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = "LarkIcons.$name",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder,
    )
}.build()
