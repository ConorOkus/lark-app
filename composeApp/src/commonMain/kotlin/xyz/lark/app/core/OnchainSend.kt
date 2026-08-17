package xyz.lark.app.core

/**
 * Optional capability: spending the wallet's on-chain balance to an address the holder names.
 *
 * Separate from [OnchainFunding] because they are opposite directions on the same wallet, and only
 * one of them is a way for money to leave: conflating them would put a spend behind an interface
 * whose whole subject is money arriving.
 *
 * Deliberately not tied to exiting. Exit proceeds are the reason it exists, but the same path
 * rescues a board that never completed and change left behind by one that did — so it is offered
 * whenever there is an on-chain balance, not only after an exit. A capability that appeared only
 * in the wake of an exit would make an ordinary spend look like exit machinery.
 */
interface OnchainSend {

    /** On-chain sats available to spend. */
    val spendableSats: Long

    /**
     * What sending [sats] to [address] would cost, without sending it.
     *
     * Null when the quote cannot be produced — an unreachable chain source, an address this
     * network will not accept, an amount the balance cannot cover. The caller shows an unknown and
     * refuses the send rather than guessing; a fee invented here would be attached to something
     * irreversible.
     */
    suspend fun quoteOnchainSend(address: String, sats: Long): OnchainSendQuote?

    /**
     * Broadcast the spend, returning its transaction id.
     *
     * Null means it did not go out. There is no partial outcome to report: either a transaction
     * reached the network or nothing happened.
     */
    suspend fun sendOnchain(address: String, sats: Long): String?
}

/**
 * What an on-chain spend would cost.
 *
 * [totalSats] is amount plus fee — what actually leaves the wallet — because that is the figure a
 * holder checks against their balance, and the one a fee shown alone quietly omits.
 */
data class OnchainSendQuote(
    val feeSats: Long,
    val totalSats: Long,
)
