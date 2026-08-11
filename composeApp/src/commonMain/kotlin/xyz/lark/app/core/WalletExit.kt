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
    val reason: String? = null,
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
