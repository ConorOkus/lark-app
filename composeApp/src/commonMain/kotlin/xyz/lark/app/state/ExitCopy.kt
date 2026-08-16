package xyz.lark.app.state

import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.ExitStallReason
import xyz.lark.app.core.ExitStatus
import xyz.lark.app.core.format.MUTINYNET_BLOCK_SECONDS
import xyz.lark.app.core.format.approxDurationLabel
import xyz.lark.app.core.format.elapsedLabel

/**
 * Every word the exiting surface says, as pure functions of the exit's status.
 *
 * Separate from the machine because this is copy, not state: it changes for reasons of tone and
 * honesty rather than of behaviour, and it is the part most worth reading on its own. Keeping it
 * pure is also what makes the awkward cases — a stall with no category, a countdown with no tip —
 * testable without constructing a wallet.
 */

/**
 * The headline: a wait where one can be computed, the state's own name where it cannot.
 *
 * Only `WAITING_OUT_DELAY` has a knowable end — the exit's claimable height, persisted with
 * the exit, so this keeps working with no Ark server and no chain source. Every other stage's
 * remaining time depends on how long a confirmation takes, which nothing here can know, and a
 * countdown invented for those would be wrong for hours at a stretch on a screen the holder
 * checks precisely because they cannot do anything else.
 *
 * A missing tip is treated the same as a missing height: no tip, no countdown, no guess.
 */
internal fun exitHeadline(status: ExitStatus, tipHeight: Long?): String {
    val blocksLeft = status.claimableAtHeight
        ?.takeIf { status.stage == ExitStage.WAITING_OUT_DELAY }
        ?.let { claimableAt -> tipHeight?.takeIf { it > 0 }?.let { claimableAt - it } }
    return when {
        // A stall outranks everything. A countdown while nothing is advancing is the most
        // visible lie the surface could tell — it keeps promising an end that is not coming.
        status.stalled -> exitStallHeadline(status.reason)
        blocksLeft != null -> approxDurationLabel(blocksLeft, MUTINYNET_BLOCK_SECONDS)
        else -> exitStageName(status.stage)
    }
}

/**
 * Why a stalled exit is not moving, in one line the holder can act on or stop worrying about.
 *
 * One string per category rather than per engine variant. The engine has 26 of those, naming
 * internals nobody outside it can use; these five are the distinctions that change what the
 * holder should do — and only the second changes it to anything.
 */
internal fun exitStallHeadline(reason: ExitStallReason?): String = when (reason) {
    ExitStallReason.CHAIN_UNREACHABLE -> "Can't reach the network"
    ExitStallReason.NEEDS_ONCHAIN_FUNDS -> "Needs on-chain funds"
    ExitStallReason.NOT_ECONOMIC -> "Costs more than it holds"
    ExitStallReason.BROADCAST_REJECTED -> "The network refused it"
    // Null lands here too: a pass that failed without a category is an engine this build
    // predates, which is the same thing to a reader as one it cannot explain.
    ExitStallReason.UNKNOWN, null -> "Something isn't working"
}

/**
 * A stage in the holder's words, for the headline slot.
 *
 * Deliberately not the protocol's vocabulary: the surface is the one thing standing between a
 * holder and three hours of silence, and "AwaitingDelta" explains nothing to the person
 * waiting it out.
 */
internal fun exitStageName(stage: ExitStage): String = when (stage) {
    ExitStage.STARTING -> "Getting ready"
    ExitStage.BROADCASTING -> "Confirming your exit"
    ExitStage.WAITING_OUT_DELAY -> "Waiting out the delay"
    ExitStage.CLAIMABLE, ExitStage.CLAIMING -> "Claiming your funds"
    ExitStage.CLAIMED -> "Done"
    // A parked channel exit: honest about being stuck rather than dressed as progress.
    ExitStage.UNSUPPORTED -> "Can't continue this exit"
    ExitStage.NONE -> "Leaving the Ark"
}

/**
 * What the exit is doing, in the user's terms.
 *
 * A stall outranks the stage: "not advancing" is the fact that matters, and showing the stage
 * it is stuck at as though it were progress would be the misreport the stall signal exists to
 * prevent.
 */
internal fun exitDetail(status: ExitStatus, stalledSince: Long?, nowMillis: Long): String = when {
    status.stalled -> exitStallDetail(status.reason, stalledSince, nowMillis)
    else -> when (status.stage) {
        ExitStage.STARTING -> "Preparing to leave the Ark."
        ExitStage.BROADCASTING -> "Putting your exit on the chain."
        ExitStage.WAITING_OUT_DELAY -> "Waiting out the exit delay."
        ExitStage.CLAIMABLE -> "Ready to claim."
        ExitStage.CLAIMING -> "Claiming your funds."
        ExitStage.UNSUPPORTED -> "This exit needs a newer version of LARK."
        ExitStage.NONE, ExitStage.CLAIMED -> ""
    }
}

/**
 * The stall's subline: what it means, and how long it has been true.
 *
 * The elapsed time goes here rather than in the headline because it is context, not the news.
 * It is also the only honest number available — a stall has no knowable end, so the surface
 * reports what has already happened instead of predicting what will.
 *
 * Every line ends by saying the funds are safe and the exit is still running, because the
 * reasonable fear on this screen is that the money is gone, and only one of these five
 * categories is anything the holder can act on.
 */
internal fun exitStallDetail(
    reason: ExitStallReason?,
    stalledSince: Long?,
    nowMillis: Long,
): String {
    val stuckFor = stalledSince
        ?.let { "Stuck for ${elapsedLabel(nowMillis - it)}. " }
        .orEmpty()
    val what = when (reason) {
        ExitStallReason.NEEDS_ONCHAIN_FUNDS ->
            "An exit pays miner fees from your on-chain balance, and there isn't enough " +
                "there yet. Add some and this picks up on its own."
        ExitStallReason.CHAIN_UNREACHABLE ->
            "LARK can't reach the chain right now. Your funds are safe and the exit " +
                "resumes on its own once the connection is back."
        ExitStallReason.NOT_ECONOMIC ->
            "Claiming would cost more than these funds are worth at the current fee rate. " +
                "The exit stays open and retries as fees change."
        ExitStallReason.BROADCAST_REJECTED ->
            "The network wouldn't accept the transaction. Your funds are safe and LARK " +
                "keeps retrying."
        ExitStallReason.UNKNOWN, null ->
            "Your funds are safe and LARK keeps trying. If this doesn't clear, the logs " +
                "will say why."
    }
    return stuckFor + what
}
