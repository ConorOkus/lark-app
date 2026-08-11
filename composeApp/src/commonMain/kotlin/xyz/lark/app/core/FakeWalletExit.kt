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
 */
class FakeWalletExit(
    private val script: List<ExitStage> = DEFAULT_SCRIPT,
    private val vtxoCount: Int = 3,
    private val inFlightSats: Long = 250_000L,
    private var failingPasses: Int = 0,
    startedAlready: Boolean = false,
) : WalletExit {

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
                reason = "scripted failure",
            )
        } else {
            consecutiveFailures = 0
            if (index < script.lastIndex) index++
            statusAt(index)
        }
        return exitStatus
    }

    private fun statusAt(at: Int): ExitStatus {
        val stage = script[at]
        val claimed = if (stage == ExitStage.CLAIMED) vtxoCount else 0
        return ExitStatus(
            stage = stage,
            vtxoCount = vtxoCount,
            claimedCount = claimed,
            inFlightSats = if (stage == ExitStage.CLAIMED) 0 else inFlightSats,
        )
    }

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
