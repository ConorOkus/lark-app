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
     * Everything sitting on-chain, confirmed or not.
     *
     * The figure that answers "is anything on its way?", which is a different question from
     * "can anything be boarded?" ([confirmedSats]) and the one the user's waiting experience is
     * built on: from where they stand, money that has not confirmed and money that has confirmed
     * but not yet boarded are the same wait.
     */
    val onchainSats: Long get() = confirmedSats + pendingSats

    /**
     * The smallest amount the Ark server will accept as a board.
     *
     * Surfaced because it decides what the funding screen can honestly ask for: a faucet payment
     * under this figure is not a failure to retry, it is not enough money, and the difference has
     * to be explainable before the user waits for confirmations that will not help.
     */
    val minBoardSats: Long

    /**
     * When the user last asked for money to arrive, as epoch millis, or null if they never have.
     *
     * A timestamp rather than a boolean because the app has to answer "is this intent still
     * current?", and only a time can. Persisted, because the deposit it authorises can take hours
     * and the user will close the app in the meantime.
     *
     * The fact lives here; the policy that turns it into "armed" does not. Deciding how long an
     * intent lasts needs a clock and a window, both of which belong to the caller — the seam's job
     * is to remember what the user asked for, not to judge when they stopped meaning it.
     */
    val fundingArmedAtMillis: Long?

    /**
     * Record that the user has asked for money to arrive, at [atMillis].
     *
     * Called when the user opens the funding screen, which is the only place they can express that
     * intent. Calling it again refreshes the timestamp, so returning to the screen renews the
     * intent rather than leaving it to lapse on its original deadline.
     */
    fun armFunding(atMillis: Long)

    /**
     * Forget the user's funding intent.
     *
     * The important caller is a unilateral exit: it puts funds into this same on-chain wallet, and
     * nothing may pull them back out from under the user. Clearing the intent first is what makes
     * that impossible rather than merely unlikely.
     */
    fun disarmFunding()

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
