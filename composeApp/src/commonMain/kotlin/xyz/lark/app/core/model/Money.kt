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

    /** The payment settled. The money is gone and the recipient has it. */
    data object Success : SendResult

    /** The payment did not happen. The balance is untouched. */
    data object Failure : SendResult

    /**
     * The payment was accepted but had not reached a terminal state before the core stopped
     * waiting — the money may still be in flight (plan R5).
     *
     * Neither "Sent." nor a failure would be honest here: claiming success repeats the bug
     * where an acknowledgement is presented as settlement, and claiming failure tells the user
     * to retry a payment that may yet succeed. Only the channel path can produce this, because
     * it is the only path that can observe settlement at all; the Ark path stays binary
     * until its own acknowledgement gap is closed.
     */
    data object Pending : SendResult
}
