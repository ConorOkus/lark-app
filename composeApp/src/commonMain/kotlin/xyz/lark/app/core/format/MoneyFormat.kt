package xyz.lark.app.core.format

import kotlin.math.abs
import xyz.lark.app.core.model.FiatRate

/**
 * Pure money formatting, identical on every target: grouping is hand-rolled
 * (no platform NumberFormat), so Android and iOS render the exact same strings.
 *
 * Bitcoin renders per BIP 177: `₿412,350` — the ₿ sign plus grouped whole sats, no "sats" suffix.
 * Fiat is a presentation of sats through a [FiatRate]: `$412.35`, always two decimals.
 * Signed variants match the prototype's activity list: `+` for incoming,
 * U+2212 minus `−` for outgoing, before the whole formatted amount.
 */
object MoneyFormat {

    private const val BTC_SIGN = "₿" // ₿
    private const val MINUS_SIGN = "−" // − (U+2212, not ASCII hyphen)
    private const val PLUS_SIGN = "+"
    private const val GROUP_SIZE = 3
    private const val GROUP_SEPARATOR = ","
    private const val CENTS_PER_DOLLAR = 100L
    private const val CENT_DIGITS = 2

    /** `412350` → `₿412,350`. Negative amounts render with a leading `−`. */
    fun btc(sats: Long): String =
        if (sats < 0) MINUS_SIGN + btc(-sats) else BTC_SIGN + grouped(sats)

    /** `250000` → `+₿250,000`; `-14200` → `−₿14,200`. */
    fun signedBtc(sats: Long): String = sign(sats) + btc(abs(sats))

    /** `412350` at the demo rate → `$412.35`. Rounds half-up to the cent. */
    fun fiat(sats: Long, rate: FiatRate): String {
        if (sats < 0) return MINUS_SIGN + fiat(-sats, rate)
        val cents = rate.satsToCents(sats)
        val whole = grouped(cents / CENTS_PER_DOLLAR)
        val fraction = (cents % CENTS_PER_DOLLAR).toString().padStart(CENT_DIGITS, '0')
        return "$$whole.$fraction"
    }

    /** `250000` → `+$250.00`; `-14200` → `−$14.20`. */
    fun signedFiat(sats: Long, rate: FiatRate): String = sign(sats) + fiat(abs(sats), rate)

    private fun sign(sats: Long): String = if (sats > 0) PLUS_SIGN else MINUS_SIGN

    /** Groups a non-negative number with comma separators: `1000000` → `1,000,000`. */
    private fun grouped(value: Long): String =
        value.toString()
            .reversed()
            .chunked(GROUP_SIZE)
            .joinToString(GROUP_SEPARATOR)
            .reversed()
}
