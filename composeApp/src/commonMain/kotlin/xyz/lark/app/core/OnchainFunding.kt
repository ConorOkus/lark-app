package xyz.lark.app.core

/**
 * Optional capability: moving on-chain funds into Ark (boarding).
 *
 * Separate from [LarkCore] for the same reason [DemoControls] is — it is not something every engine
 * can do, and putting it on the seam would force three cores to carry members two of them can only
 * answer dishonestly. Only a core that holds keys and owns an on-chain wallet can board; the demo
 * has no chain, and the gateway's funding lives on the daemon's side of the wire.
 *
 * Where it is absent the funding step has nothing to offer and says so, rather than showing a
 * deposit address that leads nowhere.
 */
interface OnchainFunding {

    /** On-chain sats that have confirmed, and can therefore be boarded. */
    val confirmedSats: Long

    /** On-chain sats seen but not yet confirmed — arrived, not yet usable. */
    val pendingSats: Long

    /**
     * The smallest amount the Ark server will accept as a board.
     *
     * Surfaced because it decides what the funding screen can honestly ask for: a faucet payment
     * under this figure is not a failure to retry, it is not enough money, and the difference has
     * to be explainable before the user waits for confirmations that will not help.
     */
    val minBoardSats: Long

    /** Re-read the chain so a fresh deposit becomes visible. Safe to call repeatedly. */
    suspend fun syncOnchain()

    /**
     * Board the whole on-chain balance. False means nothing was boarded and the money is untouched.
     *
     * No amount, because the fee comes out of the same coins: asking to board exactly the confirmed
     * balance always fails, and that is the only amount this flow would ever pass.
     */
    suspend fun boardAll(): Boolean
}
