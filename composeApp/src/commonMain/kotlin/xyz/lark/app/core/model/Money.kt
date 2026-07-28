package xyz.lark.app.core.model

/**
 * Sats-per-cent exchange rate. Money stays integer sats end-to-end (KTD-6);
 * fiat exists only as a presentation of sats through this rate.
 *
 * The demo rate is 1 sat = $0.001, i.e. [satsPerCent] = 10 (prototype: `fiat(sats) = sats / 1000` dollars).
 */
data class FiatRate(val satsPerCent: Long) {

    init {
        require(satsPerCent > 0) { "satsPerCent must be positive" }
    }

    /** Converts non-negative sats to cents, rounding half-up like the prototype's `Math.round(sats / 10)`. */
    fun satsToCents(sats: Long): Long = (2 * sats + satsPerCent) / (2 * satsPerCent)

    /** Converts cents to sats (exact at the demo rate). */
    fun centsToSats(cents: Long): Long = cents * satsPerCent
}

/** Outcome of [xyz.lark.app.core.LarkCore.send]. */
sealed interface SendResult {
    data object Success : SendResult
    data object Failure : SendResult
}
