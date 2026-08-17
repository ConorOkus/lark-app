package xyz.lark.app.core

/**
 * A [WalletExit] that walks a scripted progression, one stage per pass.
 *
 * Exists because the real thing is unfalsifiable in a test: an exit crosses the exit delay, which
 * is 144 blocks of chain time no amount of test setup can shorten. The app's side of exit — mode
 * entry, the guards, resume, stall reporting, leaving the mode — is all state logic, and this is
 * what makes it provable in milliseconds instead of hours.
 *
 * [failingPasses] injects consecutive failures before progress resumes, which is the only way to
 * exercise the stall threshold. The failures are transient by construction: once they are spent,
 * the exit advances again, so a test can assert both that a stall raises and that it clears.
 *
 * [failureReason] scripts which category those failures carry. It matters because the categories
 * are not interchangeable to the app: only [ExitStallReason.NEEDS_ONCHAIN_FUNDS] comes with an
 * action, so a test that cannot choose the category cannot cover the branch that offers one.
 */
class FakeWalletExit(
    private val script: List<ExitStage> = DEFAULT_SCRIPT,
    private val vtxoCount: Int = 3,
    private val inFlightSats: Long = 250_000L,
    private val failure: FakeExitFailure = FakeExitFailure(),
    private val heights: FakeExitHeights = FakeExitHeights(),
    startedAlready: Boolean = false,
) : WalletExit {

    /**
     * How many reads answer "cannot tell" before the wallet can speak.
     *
     * Models the real core's asynchronous open, which is the gap every resume lands in. A knob
     * rather than a constructor parameter only because the constructor is already at its limit;
     * it is the single most important thing this fake can express, because a caller that reads an
     * unreadable status as "nothing is exiting" abandons a live exit and no other setup catches it.
     */
    var unreadableReads: Int = 0

    private var failingPasses = failure.passes

    private var index = if (startedAlready) 0 else NOT_STARTED
    private var consecutiveFailures = 0

    /** How many times [progressExit] has been called. Lets a test prove the watcher is driving. */
    var passes: Int = 0
        private set

    /**
     * Cold until something asks, exactly like the real core.
     *
     * `DelegateBackedLarkCore` seeds this field with [ExitStatus.NOT_EXITING] and only fills it in
     * once an asynchronous read returns, because reading the crate is a suspending call and this
     * is a plain property. So a wallet reopened mid-exit reports "not exiting" until the first
     * pass lands, and anything that decides from this field at construction time decides wrong.
     *
     * The fake used to be eager here, which is why its resume test passed against a core that
     * could not resume. Being cold is the whole point: a fake that is easier to satisfy than the
     * thing it stands in for turns a green suite into evidence of nothing.
     */
    override var exitStatus: ExitStatus = ExitStatus.NOT_EXITING
        private set

    override suspend fun startExit() {
        // Starting an exit that is already running is harmless, mirroring the crate: bark skips
        // VTXOs it is already exiting rather than duplicating them.
        if (index != NOT_STARTED) return
        index = 0
        exitStatus = statusAt(0)
    }

    /**
     * Always readable: the fake has no async open to wait on. It still warms [exitStatus] here,
     * because that is what the real implementation does and a fake that stays colder than the
     * thing it stands in for is as misleading as one that starts warmer.
     */
    override suspend fun readExitStatus(): ExitStatus? {
        if (unreadableReads > 0) {
            unreadableReads--
            return null
        }
        exitStatus = if (index == NOT_STARTED) ExitStatus.NOT_EXITING else statusAt(index)
        return exitStatus
    }

    override suspend fun progressExit(): ExitStatus {
        if (index == NOT_STARTED) return ExitStatus.NOT_EXITING
        passes++

        exitStatus = if (failingPasses > 0) {
            failingPasses--
            consecutiveFailures++
            statusAt(index).copy(
                stalled = consecutiveFailures >= EXIT_STALL_THRESHOLD,
                reason = failure.reason,
            )
        } else {
            consecutiveFailures = 0
            if (index < script.lastIndex) index++
            statusAt(index)
        }
        return exitStatus
    }

    /**
     * Null by default, because a wallet with no reachable server is the case worth defaulting to
     * here: it is both the scenario exit exists for and the one where a screen is most likely to
     * be handed an unknown it must not render as a number.
     */
    override suspend fun exitDeltaBlocks(): Int? = heights.deltaBlocks

    private var receiptAcknowledged = false

    /**
     * Mirrors the real thing's rule rather than its storage: a receipt exists once the script has
     * reached the end and until the holder dismisses it. Tests that never advance that far never
     * see one, which is the same shape as a wallet mid-exit.
     */
    override suspend fun pendingReceipt(): ExitReceipt? =
        if (exitStatus.stage == ExitStage.CLAIMED && !receiptAcknowledged) {
            ExitReceipt(landedSats = inFlightSats, tookMillis = TOOK_MILLIS)
        } else {
            null
        }

    override suspend fun acknowledgeReceipt() {
        receiptAcknowledged = true
    }

    private fun statusAt(at: Int): ExitStatus {
        val stage = script[at]
        val claimed = if (stage == ExitStage.CLAIMED) vtxoCount else 0
        return ExitStatus(
            stage = stage,
            vtxoCount = vtxoCount,
            claimedCount = claimed,
            inFlightSats = if (stage == ExitStage.CLAIMED) 0 else inFlightSats,
            landedSats = if (stage == ExitStage.CLAIMED) inFlightSats else 0,
            claimableAtHeight = heights.claimableAtHeight,
        )
    }

    /**
     * How a scripted exit fails: for how many consecutive passes, and as what.
     *
     * The two travel together because neither is useful alone — a stall with no category cannot
     * exercise the surface that reads one, and a category with no passes never raises a stall.
     * [reason] matters because the categories are not interchangeable to the app: only
     * [ExitStallReason.NEEDS_ONCHAIN_FUNDS] comes with an action, so a test that cannot choose the
     * category cannot cover the branch that offers one.
     */
    data class FakeExitFailure(
        val passes: Int = 0,
        val reason: ExitStallReason = ExitStallReason.CHAIN_UNREACHABLE,
    )

    /**
     * The two chain-derived figures an exit can report, grouped because they answer the same
     * question at different times: what a wait *would* be before an exit exists, and what it *is*
     * once one does. Both default to null — the no-server case, which is the one worth defaulting
     * to for a feature that exists for it.
     */
    data class FakeExitHeights(
        val deltaBlocks: Int? = null,
        val claimableAtHeight: Long? = null,
    )


    companion object {
        private const val NOT_STARTED = -1

        /**
         * How long a scripted exit is said to have taken.
         *
         * A constant rather than measured virtual time: the fake's passes advance a test scheduler,
         * not a wall clock, and the receipt's duration is wall-clock by definition — it spans app
         * launches. Three hours is what a real mutinynet exit roughly costs.
         */
        const val TOOK_MILLIS: Long = 3L * 60 * 60 * 1_000

        /** The ordinary path: start, broadcast, wait out the delay, become claimable, claim. */
        val DEFAULT_SCRIPT = listOf(
            ExitStage.STARTING,
            ExitStage.BROADCASTING,
            ExitStage.WAITING_OUT_DELAY,
            ExitStage.CLAIMABLE,
            ExitStage.CLAIMING,
            ExitStage.CLAIMED,
        )
    }
}
