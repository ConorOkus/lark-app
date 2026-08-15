package xyz.lark.app.core

/**
 * Optional capability: leaving the Ark unilaterally.
 *
 * Separate from [LarkCore] for the same reason [OnchainFunding] is — only a core that holds keys
 * and owns an on-chain wallet can exit. Putting it on the seam would force the demo and both
 * gateway cores to carry members they can only answer dishonestly.
 *
 * ## There is no cancel, and that is the contract
 *
 * This interface deliberately exposes no way to stop, abort, or abandon an exit. Once an exit
 * transaction is in the mempool it cannot be recalled, so a stop control would promise something
 * no implementation can deliver. The absence is enforced here rather than by UI omission, because
 * a missing button is a decision anyone can reverse and a missing member is not.
 *
 * An exit therefore ends exactly one way: every VTXO reaches [ExitStage.CLAIMED]. A stalled exit
 * is reported and retried, never abandoned.
 */
interface WalletExit {

    /** Where the wallet's exit stands. [ExitStatus.NOT_EXITING] when nothing is exiting. */
    val exitStatus: ExitStatus

    /**
     * Begin a unilateral exit for the whole VTXO set.
     *
     * Amount-free and selection-free: exit is the wallet leaving the Ark, not a partial
     * withdrawal. Needs no Ark server — that is the entire point — so an implementation must not
     * gate it on one being reachable. Calling it while an exit is already in flight is harmless.
     */
    suspend fun startExit()

    /**
     * Advance the exit by one pass and report where it now stands.
     *
     * One call does not finish an exit. Broadcasting, waiting out the exit delta, and claiming
     * are separate passes, and the middle one is bounded by the chain rather than by effort — so
     * callers drive this repeatedly for as long as the wallet is exiting.
     *
     * Safe to call when nothing is exiting; it returns [ExitStatus.NOT_EXITING] without effect.
     */
    suspend fun progressExit(): ExitStatus

    /**
     * How many blocks a started exit must wait out, or null when it cannot be known.
     *
     * Needed only *before* an exit exists, to estimate how long one would take. Once an exit is
     * running its claimable height is persisted and [ExitStatus.claimableAtHeight] answers without
     * this.
     *
     * Null is an honest answer rather than a failure: the delta lives on the Ark server, so a
     * wallet that cannot reach one cannot know it — which is exactly the wallet most likely to be
     * reading an exit screen. Callers show an unknown; substituting a default would put a wrong
     * wait under a button that cannot be taken back.
     */
    suspend fun exitDeltaBlocks(): Int?
}

/**
 * How far a unilateral exit has got.
 *
 * The wallet's stage is the least advanced of its exiting VTXOs, so [CLAIMED] means every one of
 * them is — a wallet with three claimed and one still broadcasting has not left the Ark.
 */
enum class ExitStage {
    /** Nothing is exiting. */
    NONE,
    STARTING,
    BROADCASTING,
    WAITING_OUT_DELAY,
    CLAIMABLE,
    CLAIMING,
    CLAIMED,

    /**
     * A stage this build cannot advance, which today means a channel-funded VTXO.
     *
     * Reported as itself rather than mapped onto an ordinary stage: calling a parked exit
     * "broadcasting" would claim progress that is not happening.
     */
    UNSUPPORTED,
}

/**
 * Why an exit is not progressing, in terms a screen can speak.
 *
 * A category rather than a message. The engine's own error type has 26 variants naming internals
 * a holder can neither act on nor understand, and no amount of per-variant copy would fix that —
 * so the classification happens below the seam and the string never arrives. That is also what
 * keeps an engine message or a VTXO id out of a headline: there is nothing here to leak.
 */
enum class ExitStallReason {
    /** The chain source did not answer. Transient; waiting is the whole remedy. */
    CHAIN_UNREACHABLE,

    /**
     * Not enough confirmed on-chain balance to pay what the exit costs.
     *
     * The engine checks this before an exit leaves its first state, so a wallet that boarded its
     * whole balance cannot start an exit at all until on-chain funds arrive.
     */
    NEEDS_ONCHAIN_FUNDS,

    /**
     * The exit would cost more than it recovers, or a VTXO is below the dust limit.
     *
     * Kept apart from [NEEDS_ONCHAIN_FUNDS] because the shortfall is between a VTXO's value and
     * its own exit cost, not in the wallet's balance — so depositing cannot clear it.
     */
    NOT_ECONOMIC,

    /** A transaction was assembled but the network would not take it. */
    BROADCAST_REJECTED,

    /** Anything else, including a stall from an engine version this build predates. */
    UNKNOWN;

    /**
     * Whether the holder can clear this stall themselves by funding the wallet on-chain.
     *
     * Lives here rather than in the UI so that only one place decides which stalls come with an
     * action. Every other reason is reported and retried with nothing offered, because offering a
     * remedy that cannot work is worse than offering none.
     */
    val isClearableByDeposit: Boolean get() = this == NEEDS_ONCHAIN_FUNDS
}

/**
 * The wallet's exit, as the app needs to see it.
 *
 * [stalled] is the app-facing judgement, not the engine's: an implementation counts consecutive
 * failing passes and raises it at a threshold. A single failing pass is a blip — a chain source
 * that timed out once — and reporting a stall on one would cry wolf on every flaky network.
 */
data class ExitStatus(
    val stage: ExitStage,
    val vtxoCount: Int = 0,
    val claimedCount: Int = 0,
    val inFlightSats: Long = 0,
    val stalled: Boolean = false,
    val reason: ExitStallReason? = null,
    /**
     * Height at which every exiting VTXO becomes claimable, or null while the exit does not know.
     *
     * Persisted with the exit, so it survives the Ark server being gone — which is what lets an
     * in-flight exit show a real countdown when [exitDeltaBlocks] cannot be answered at all.
     */
    val claimableAtHeight: Long? = null,
) {
    /** Whether the wallet is in the exiting state, and so cannot send, receive, or board. */
    val isExiting: Boolean get() = stage != ExitStage.NONE && stage != ExitStage.CLAIMED

    companion object {
        val NOT_EXITING = ExitStatus(stage = ExitStage.NONE)
    }
}

/**
 * Consecutive failing passes before an exit is called stalled.
 *
 * Three rather than one so a single transient chain-source failure does not raise the alarm, and
 * a small number rather than a large one because the user is owed an explanation while they are
 * still watching. The threshold lives on this side of the FFI on purpose: it is app policy, and
 * baking it into the crate would put it beyond the app's reach.
 */
const val EXIT_STALL_THRESHOLD: Int = 3
