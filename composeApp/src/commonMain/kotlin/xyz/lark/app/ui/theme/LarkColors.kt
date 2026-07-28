package xyz.lark.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color roles of the LARK Wallet design language.
 *
 * Values come from the inline styles of the vendored design spec at
 * `docs/design/lark-wallet/LARK Wallet.dc.html`.
 */
@Suppress("MagicNumber")
object LarkColors {

    /** App background. */
    val Background = Color(0xFF0B0C0E)

    /** Canvas behind the app frame (status/system areas). */
    val Canvas = Color(0xFF08090A)

    /** Card and sheet surface. */
    val Surface = Color(0xFF14161A)

    /** Surface in its hover / pressed state. */
    val SurfacePressed = Color(0xFF1C1F24)

    /** Hairline border on surfaces — rgba(255,255,255,.07). */
    val Border = Color.White.copy(alpha = 0.07f)

    /** Stronger border for outline pill buttons — rgba(255,255,255,.16). */
    val BorderStrong = Color.White.copy(alpha = 0.16f)

    /** 1px separators inside row groups — rgba(255,255,255,.06). */
    val Separator = Color.White.copy(alpha = 0.06f)

    /** Subtle fill for leading icon circles — rgba(255,255,255,.06). */
    val IconCircle = Color.White.copy(alpha = 0.06f)

    /** Primary text. */
    val TextPrimary = Color(0xFFF4F1EA)

    /** Secondary text — rgba(244,241,234,.5). */
    val TextSecondary = TextPrimary.copy(alpha = 0.5f)

    /** Tertiary text (inactive tabs, sublabels) — rgba(244,241,234,.45). */
    val TextTertiary = TextPrimary.copy(alpha = 0.45f)

    /** Quaternary text (eyebrows, hidden-balance dots) — rgba(244,241,234,.35). */
    val TextQuaternary = TextPrimary.copy(alpha = 0.35f)

    /** Faint text and glyphs (trailing chevrons) — rgba(244,241,234,.3). */
    val TextFaint = TextPrimary.copy(alpha = 0.3f)

    /** Monospace codes and addresses — rgba(244,241,234,.7). */
    val TextCode = TextPrimary.copy(alpha = 0.7f)

    /** Gold accent. One gold moment per screen. */
    val Gold = Color(0xFFE8C15C)

    /** Gold in its hover / pressed state. */
    val GoldPressed = Color(0xFFF3D68C)

    /** Text and glyphs placed on gold. */
    val OnGold = Color(0xFF14100A)

    /** Spinner track — rgba(232,193,92,.2). */
    val GoldTrack = Gold.copy(alpha = 0.2f)

    /** Danger / warning orange. */
    val Warning = Color(0xFFFF7A4D)

    /** Text and glyphs placed on warning orange. */
    val OnWarning = Color(0xFF241008)

    /** Success green (health dots, received amounts). */
    val Success = Color(0xFF6FE3A8)
}
