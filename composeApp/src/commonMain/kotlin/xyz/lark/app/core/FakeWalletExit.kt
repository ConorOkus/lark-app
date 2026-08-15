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

    private var failingPasses = failure.passes

    private var index = if (startedAlready) 0 else NOT_STARTED
    private var consecutiveFailures = 0

    /** How many times [progressExit] has been called. Lets a test prove the watcher is driving. */
    var passes: Int = 0
        private set

    override var exitStatus: ExitStatus = if (startedAlready) statusAt(0) else ExitStatus.NOT_EXITING
        private set

    override suspend fun startExit() {
        // Starting an exit that is already running is harmless, mirroring the crate: bark skips
        // VTXOs it is already exiting rather than duplicating them.
        if (index != NOT_STARTED) return
        index = 0
        exitStatus = statusAt(0)
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

    private fun statusAt(at: Int): ExitStatus {
        val stage = script[at]
        val claimed = if (stage == ExitStage.CLAIMED) vtxoCount else 0
        return ExitStatus(
            stage = stage,
            vtxoCount = vtxoCount,
            claimedCount = claimed,
            inFlightSats = if (stage == ExitStage.CLAIMED) 0 else inFlightSats,
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
