@file:Suppress("MagicNumber")

package xyz.lark.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import lark_app.composeapp.generated.resources.Res
import lark_app.composeapp.generated.resources.bricolage_grotesque_bold
import lark_app.composeapp.generated.resources.bricolage_grotesque_medium
import lark_app.composeapp.generated.resources.bricolage_grotesque_semibold
import lark_app.composeapp.generated.resources.jetbrains_mono_medium
import lark_app.composeapp.generated.resources.jetbrains_mono_regular
import lark_app.composeapp.generated.resources.manrope_bold
import lark_app.composeapp.generated.resources.manrope_medium
import lark_app.composeapp.generated.resources.manrope_regular
import lark_app.composeapp.generated.resources.manrope_semibold
import org.jetbrains.compose.resources.Font

/** OpenType feature turning on tabular (fixed-width) numerals — required wherever money renders. */
const val TABULAR_NUMERALS = "tnum"

/** Manrope — body and UI text. */
@Composable
fun manropeFamily(): FontFamily = FontFamily(
    Font(Res.font.manrope_regular, FontWeight.Normal),
    Font(Res.font.manrope_medium, FontWeight.Medium),
    Font(Res.font.manrope_semibold, FontWeight.SemiBold),
    Font(Res.font.manrope_bold, FontWeight.Bold),
)

/** Bricolage Grotesque — display headlines and large numerals. */
@Composable
fun bricolageGrotesqueFamily(): FontFamily = FontFamily(
    Font(Res.font.bricolage_grotesque_medium, FontWeight.Medium),
    Font(Res.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(Res.font.bricolage_grotesque_bold, FontWeight.Bold),
)

/** JetBrains Mono — codes and addresses. */
@Composable
fun jetBrainsMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
)

/**
 * Text roles of the LARK Wallet design language, taken from the inline styles of
 * `docs/design/lark-wallet/LARK Wallet.dc.html`.
 */
@Immutable
data class LarkTypography(
    /** Onboarding hero — Bricolage 600 72/.92, -.05em, tabular numerals. */
    val displayHero: TextStyle,
    /** Keypad amount — Bricolage 600 68/1, -.05em, tabular numerals. */
    val amountDisplay: TextStyle,
    /** Balance and detail amounts — Bricolage 600 46/1, -.035em, tabular numerals. */
    val balance: TextStyle,
    /** Screen title — Bricolage 600 34/1.1, -.03em. */
    val screenTitle: TextStyle,
    /** Section / card title — Manrope 600 17. */
    val sectionTitle: TextStyle,
    /** List item title and row values — Manrope 600 15. */
    val itemTitle: TextStyle,
    /** Body copy — Manrope 400 15/1.5. */
    val body: TextStyle,
    /** Small body copy — Manrope 400 13/1.5. */
    val bodySmall: TextStyle,
    /** Caption — Manrope 400 12/1.4. */
    val caption: TextStyle,
    /** Row label — Manrope 400 14. */
    val label: TextStyle,
    /** Pill CTA label — Manrope 600 17. */
    val button: TextStyle,
    /** Section eyebrow — Manrope 700 11, .14em, uppercase. */
    val eyebrow: TextStyle,
    /** Codes and addresses — JetBrains Mono 500 13/1.5. */
    val mono: TextStyle,
)

/** Builds the [LarkTypography] set from the bundled font resources. */
@Composable
fun larkTypography(): LarkTypography {
    val manrope = manropeFamily()
    val bricolage = bricolageGrotesqueFamily()
    val mono = jetBrainsMonoFamily()
    return remember(manrope, bricolage, mono) {
        LarkTypography(
            displayHero = moneyStyle(bricolage, 72.sp, 66.sp, (-0.05).em),
            amountDisplay = moneyStyle(bricolage, 68.sp, 68.sp, (-0.05).em),
            balance = moneyStyle(bricolage, 46.sp, 46.sp, (-0.035).em),
            screenTitle = textStyle(bricolage, FontWeight.SemiBold, 34.sp, 37.sp, (-0.03).em),
            sectionTitle = textStyle(manrope, FontWeight.SemiBold, 17.sp, 22.sp, (-0.02).em),
            itemTitle = textStyle(manrope, FontWeight.SemiBold, 15.sp, 20.sp),
            body = textStyle(manrope, FontWeight.Normal, 15.sp, 22.sp),
            bodySmall = textStyle(manrope, FontWeight.Normal, 13.sp, 19.sp),
            caption = textStyle(manrope, FontWeight.Normal, 12.sp, 17.sp),
            label = textStyle(manrope, FontWeight.Normal, 14.sp, 18.sp),
            button = textStyle(manrope, FontWeight.SemiBold, 17.sp, 17.sp),
            eyebrow = textStyle(manrope, FontWeight.Bold, 11.sp, 14.sp, 0.14.em),
            mono = textStyle(mono, FontWeight.Medium, 13.sp, 19.sp),
        )
    }
}

private fun textStyle(
    family: FontFamily,
    weight: FontWeight,
    size: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

/** SemiBold Bricolage display style with tabular numerals — the money/display tier. */
private fun moneyStyle(
    family: FontFamily,
    size: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit,
): TextStyle = textStyle(family, FontWeight.SemiBold, size, lineHeight, letterSpacing)
    .copy(fontFeatureSettings = TABULAR_NUMERALS)
