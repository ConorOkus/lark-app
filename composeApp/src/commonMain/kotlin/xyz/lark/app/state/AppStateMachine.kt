@file:OptIn(ExperimentalTime::class) // kotlin.time Clock: stdlib-experimental, stable enough for M2

package xyz.lark.app.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import xyz.lark.app.core.DemoControls
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.OnchainFunding
import xyz.lark.app.core.format.MoneyFormat
// Pure destination classification, kept beside the resolver and invoice parser it composes so the
// input screen and the send path cannot disagree about what counts as payable.
import xyz.lark.app.core.gateway.SendInput
import xyz.lark.app.core.gateway.classifySendInput
import xyz.lark.app.core.model.ChannelDisplay
import xyz.lark.app.core.model.ChannelState
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

private const val MAX_DIGITS = 8
private const val COUNTDOWN_SECONDS = 60
private const val ONE_SECOND_MILLIS = 1_000L
private const val COPY_FLIP_MILLIS = 1_600L
private const val DEFAULT_RECIPIENT = "Jack"
private const val INPUT_PLACEHOLDER = "Name, invoice or address"
private const val SCAN_NAME = "Ferry Building Coffee"
private const val SCAN_HANDLE = "ferry@sq.link"
private const val SCAN_SATS = 520L
private const val HIDDEN_BALANCE = "••••"

/**
 * How long the user's request for money to arrive stays current: seven days.
 *
 * Long because an exchange withdrawal can take hours and the user will close the app; bounded
 * because an intent expressed once should not authorise a deposit made months later. The figure is
 * a judgement call, not a measured one — it is a constant precisely so it is cheap to revise.
 */
private const val FUNDING_ARM_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1_000

/** How often the watcher looks while something is on its way. Covers mutinynet's 30s blocks. */
private const val FUNDING_POLL_ACTIVE_MILLIS = 20_000L

/**
 * How often the watcher looks while the on-chain wallet is empty.
 *
 * The watcher cannot stop entirely while armed — a deposit can land at any moment and nothing else
 * would notice — but polling a wallet with nothing in it every twenty seconds is a battery tax for
 * no information. Slower, until something shows up.
 */
private const val FUNDING_POLL_IDLE_MILLIS = 120_000L

/**
 * Consecutive failures before the user is told anything.
 *
 * A board can fail because a round was in progress or the server blinked, and both fix themselves.
 * Reporting the first one teaches the user that the warning means nothing; three in a row does not
 * happen by chance.
 */
private const val BOARD_FAILURES_BEFORE_SURFACING = 3

/**
 * The default reveal-countdown clock: monotonic elapsed millis since machine construction.
 * Monotonic so device wall-clock changes can't lengthen or shorten the reveal window.
 */
private fun monotonicNowMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/** Lowercase channel-state labels, matching the Advanced screen's voice. */
private val ChannelState.label: String
    get() = when (this) {
        ChannelState.USABLE -> "usable"
        ChannelState.OPENING -> "opening"
        ChannelState.UNUSABLE -> "unusable"
    }

/** The prototype's DEMO state-rail labels and notes, keyed by health state. */
private val DEMO_HEALTH_COPY = mapOf(
    HealthState.READY to ("Ready" to "Steady state"),
    HealthState.TIDYING to ("Refreshing" to "Silent refresh (invisible)"),
    HealthState.STALE to ("Needs a moment" to "Away too long"),
    HealthState.OFFLINE to ("Offline" to "Server unreachable"),
)

/** Everything the machine mutates; the rendered [AppModel] is a pure function of this + the core. */
private data class MachineState(
    val route: Route,
    val stack: List<Route> = emptyList(),
    val denomination: Denomination = Denomination.BTC,
    val balanceVisible: Boolean = true,
    val digits: String = "",
    val scannedSats: Long? = null,
    val mode: KeypadMode = KeypadMode.SEND,
    val sendWho: String = DEFAULT_RECIPIENT,
    val input: String = "",
    val txIndex: Int = 0,
    val confirmedRecipient: String = DEFAULT_RECIPIENT,
    val confirmedSats: Long = 0L,
    val confirmedAmountDisplay: String = "",
    val wordsRevealed: Boolean = false,
    val countdown: Int = COUNTDOWN_SECONDS,
    val copied: Boolean = false,
    /** The amount Get paid is currently requesting; 0 means the amountless code. */
    val receiveRequestSats: Long = 0L,
    /**
     * The code the core returned for [receiveRequestSats]. Held in state because minting is a
     * suspending call and rendering must stay pure — null falls back to the core's own code.
     */
    val receiveCode: String? = null,
    /** The paste affordance came back empty; the summary line says so instead of no-op'ing. */
    val pasteFailed: Boolean = false,
    /**
     * Consecutive failed attempts to make an arrived deposit spendable.
     *
     * A count rather than a flag because one failure is noise and three is a problem, and only a
     * count can tell them apart. Reset to zero by any success.
     */
    val boardFailures: Int = 0,
    /** A words-restore is in flight; the restore screen says so and blocks a second attempt. */
    val restoring: Boolean = false,
    /** The last words-restore did not open a wallet. Cleared when another attempt starts. */
    val restoreFailed: Boolean = false,
)

/**
 * The app-wide state machine (KTD-2, Bitkey-style: plain class + coroutines, no androidx
 * ViewModel). Owns the hand-rolled route stack (KTD-4) and all screen state, and exposes one
 * [StateFlow] of [AppModel] plus intent functions mirroring the design prototype's Component
 * class (`docs/design/lark-wallet/LARK Wallet.dc.html`).
 *
 * All timing (send/refresh spinners inside the core, the 60s backup countdown, the 1.6s
 * copy flip) runs on the injected [scope], so tests drive it with virtual time (KTD-9).
 * The backup countdown additionally reads the injected wall clock [nowMillis] so its deadline
 * is absolute — coroutine suspension can't stretch it. [demo] is the demo-only seam (KTD-3):
 * when absent, demo affordances vanish from the model.
 */
@Suppress("TooManyFunctions") // one small intent function per prototype interaction, by design
class AppStateMachine(
    private val core: LarkCore,
    private val demo: DemoControls? = null,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = monotonicNowMillis(),
    /**
     * On-chain funding, when the active core can do it (M2). Null for the demo and the gateway,
     * which is what hides the deposit step rather than showing an address nothing can board.
     */
    private val funding: OnchainFunding? = null,
    /**
     * Wall-clock epoch millis, for the one deadline that has to survive the app being closed.
     *
     * Separate from [nowMillis], which is monotonic from construction: that is the right clock for
     * the backup countdown (a device clock change must not lengthen it) and the wrong one for the
     * funding window, which is measured across process restarts that reset the monotonic origin.
     */
    private val wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private var state = MachineState(route = restingRoute())
    private val modelFlow = MutableStateFlow(render(state))

    /** The single immutable model the UI renders. */
    val model: StateFlow<AppModel> = modelFlow.asStateFlow()

    private var workJob: Job? = null
    private var countdownJob: Job? = null
    private var copyJob: Job? = null
    private var receiveCodeJob: Job? = null
    private var fundingWatcherJob: Job? = null

    init {
        // A wallet that already exists may be carrying a deposit the user asked for before they
        // last closed the app. Nothing else would notice it, so the watcher starts at launch
        // rather than waiting for the user to revisit the funding screen.
        startFundingWatcher()
        // The core is the source of truth for wallet facts; a real (push-based) core emits
        // outside our intents, so any emission re-renders the current state. Render is pure
        // and reads the core's current values; StateFlow equality drops no-op re-renders.
        scope.launch {
            combine(
                core.balanceSats,
                core.health,
                core.walletExists,
                core.backedUp,
                core.channels,
            ) { _, _, _, _, _ -> }
                .collect { update { it } }
        }
    }

    // --- Navigation (KTD-4) ---

    /** Navigates to [route], remembering the current route on the stack. */
    fun push(route: Route) = update { it.copy(route = route, stack = it.stack + it.route) }

    /**
     * Pops the stack; on an empty stack lands on the resting route — home with a wallet,
     * welcome without — so back can never skip onboarding. A no-op while a send/refresh is
     * in flight (SENDING): backing out would race its completion.
     */
    fun back() = update {
        when {
            it.route == Route.SENDING -> it
            // Onboarding's root is welcome, not the resting route. Since the wallet is now created
            // partway through onboarding (goFund), consulting restingRoute() here would send
            // someone still setting up straight to home.
            it.stack.isEmpty() && it.route.isOnboarding -> it.copy(route = Route.WELCOME)
            it.stack.isEmpty() -> it.copy(route = restingRoute())
            else -> it.copy(route = it.stack.last(), stack = it.stack.dropLast(1))
        }
    }

    /** Navigates to [route] and resets the stack (tab-level navigation). */
    fun go(route: Route) = update { it.copy(route = route, stack = emptyList()) }

    // --- Onboarding ---

    fun goHowItWorks() = push(Route.HOW_IT_WORKS)

    /**
     * Enters the funding step — and creates the wallet first, because funding needs one.
     *
     * With an on-device core the deposit address comes from the wallet itself, so the wallet has to
     * exist before the fund screen can show anything; creating it only at [finishOnboarding] (which
     * is where it used to happen, when the address came from a gateway) leaves this screen with
     * nothing to display. Starting the open here also means its slow first sync overlaps with the
     * user reading this screen instead of stalling the end of onboarding.
     *
     * Idempotent in every core, so [finishOnboarding] can still call it for the paths that skip
     * funding entirely.
     */
    fun goFund() {
        core.createWallet()
        push(Route.FUND)
    }

    fun goRestore() = push(Route.RESTORE)

    /**
     * Shows the deposit address — and takes opening this screen as the user asking for money.
     *
     * This is the whole of the user's consent to their deposit becoming spendable. There is no
     * second confirmation, because there was never a second decision: nobody comes here, sends
     * bitcoin to the address the app gave them, and then declines to be able to spend it.
     *
     * Arming here rather than anywhere broader is what keeps a unilateral exit safe. Exit puts
     * funds into the same on-chain wallet the watcher reads, so an app that boarded whatever it
     * found would undo the exit; an app that boards only what the user asked for cannot.
     */
    fun goDeposit() {
        push(Route.DEPOSIT)
        funding?.armFunding(wallClockMillis())
        startFundingWatcher()
    }

    /**
     * Begins a unilateral exit, revoking the funding intent first.
     *
     * The order matters and is the entire point: exit funds land in the same on-chain wallet the
     * watcher reads, so the intent has to be gone before they arrive. Clearing it here — rather
     * than relying on the window having lapsed — is what makes pulling an exit back in impossible
     * rather than merely unlikely.
     */
    fun startExit() {
        funding?.disarmFunding()
        fundingWatcherJob?.cancel()
        fundingWatcherJob = null
        go(Route.HOME)
    }

    /** Completing onboarding ("Later" on fund, or leaving the deposit screen) lands home. */
    fun finishOnboarding() {
        core.createWallet()
        go(Route.HOME)
    }

    /** Restoring creates the wallet and lands home. */
    fun finishRestore() {
        core.restoreWallet()
        go(Route.HOME)
    }

    /**
     * Restores from a typed phrase: lands home if a wallet opened, and stays put saying so if not.
     *
     * Navigating on failure is the one thing this must not do — it would drop the user into an empty
     * home screen having silently lost what they typed. The words are passed straight through to the
     * core and never stored in machine state (see [xyz.lark.app.ui.screens.onboarding.RestoreScreen]).
     */
    fun finishRestore(words: List<String>) {
        if (state.restoring) return
        update { it.copy(restoring = true, restoreFailed = false) }
        scope.launch {
            val opened = core.restoreWallet(words)
            update { it.copy(restoring = false, restoreFailed = !opened) }
            if (opened) go(Route.HOME)
        }
    }

    // --- Balance ---

    /** Flips btc ↔ fiat everywhere money shows (R6). */
    fun toggleUnit() = update {
        val flipped = if (it.denomination == Denomination.BTC) Denomination.FIAT else Denomination.BTC
        it.copy(denomination = flipped)
    }

    /** Hides/shows the balance (R7). */
    fun toggleBalance() = update { it.copy(balanceVisible = !it.balanceVisible) }

    // --- Keypad (KTD-6) ---

    /** Appends [digit] ('0'–'9'): max 8 digits, leading zero suppressed on empty. */
    fun keyPress(digit: Char) {
        if (digit !in '0'..'9') return
        if (state.digits.length >= MAX_DIGITS || (state.digits.isEmpty() && digit == '0')) return
        update { it.copy(digits = it.digits + digit, scannedSats = null) }
    }

    /** Removes the last digit; no-op when empty. */
    fun backspace() {
        if (state.digits.isEmpty()) return
        update { it.copy(digits = it.digits.dropLast(1)) }
    }

    /** The keypad's primary action: send mode pushes review, receive mode returns to receive. */
    fun keypadConfirm() {
        val s = state
        if (s.digits.isEmpty() || isOverBalance(s)) return
        if (s.mode == KeypadMode.RECEIVE) {
            requestReceiveAmount(typedSats(s))
            back()
        } else {
            push(Route.REVIEW)
        }
    }

    /**
     * Asks the core for a code that requests [sats] — which a core with channels answers with a
     * BIP-321 URI carrying a Lightning invoice as well as the Ark address.
     *
     * The amount is recorded immediately so the screen can state what it is asking for, while the
     * code arrives asynchronously (minting is a network call). Until it lands, the amountless
     * code stands: never a blank screen, and never a stale code attributed to a new amount.
     */
    private fun requestReceiveAmount(sats: Long) {
        update { it.copy(receiveRequestSats = sats, receiveCode = null) }
        receiveCodeJob?.cancel()
        receiveCodeJob = scope.launch {
            val code = core.requestReceiveCode(sats)
            // A later request (or a clear) wins: only apply while this amount is still the ask.
            update { if (it.receiveRequestSats == sats) it.copy(receiveCode = code) else it }
        }
    }

    /** Drops the requested amount, returning Get paid to the amountless code. */
    fun clearReceiveAmount() {
        receiveCodeJob?.cancel()
        update { it.copy(receiveRequestSats = 0L, receiveCode = null) }
    }

    /**
     * Get paid's amount affordance: set one when there is none, drop it when there is. The
     * decision lives here rather than in the screen, which stays a thin renderer.
     */
    fun toggleReceiveAmount() {
        if (state.receiveRequestSats > 0L) clearReceiveAmount() else goReceiveAmount()
    }

    // --- Send flow ---

    /** Opens the keypad to pay the current recipient; digits reset, send mode. */
    fun goSendAmount() {
        update { it.copy(digits = "", scannedSats = null, mode = KeypadMode.SEND) }
        push(Route.AMOUNT)
    }

    /** Opens the keypad to request an amount; digits reset, receive mode. */
    fun goReceiveAmount() {
        update { it.copy(digits = "", scannedSats = null, mode = KeypadMode.RECEIVE) }
        push(Route.AMOUNT)
    }

    /**
     * Sets the recipient from what the user typed or pasted.
     *
     * [raw] is kept verbatim so the field stays editable; resolution is derived in [renderSend]
     * rather than stored, so screen and core can never disagree about what is payable. The name
     * is cleared because a raw destination has none — the render falls back to its abbreviation.
     */
    fun setSendInput(raw: String) = update {
        it.copy(input = raw, sendWho = "", scannedSats = null, digits = "", pasteFailed = false)
    }

    /**
     * The paste affordance produced nothing.
     *
     * iOS gates programmatic clipboard reads behind a system prompt, so a read can legitimately
     * come back empty — the user dismissed the prompt, or the clipboard holds no text. Saying so
     * matters: silently doing nothing reads as a dead button, and the field's own long-press
     * paste goes through system UI and is not gated, so there is a working alternative to point at.
     */
    fun sendInputPasteFailed() = update { it.copy(pasteFailed = true) }

    /**
     * Continue from the recipient screen.
     *
     * An amount-bearing invoice fixes what will be paid, so it goes straight to review rather
     * than through the keypad — offering to type an amount there would imply the user could
     * change it, and `ldk-pay` would pay the invoice's figure regardless. Anything without its
     * own amount goes to the keypad as before.
     */
    fun continueFromSendInput() {
        val fixedAmount = classifySendInput(state.input).amountSat
        if (fixedAmount == null) {
            goSendAmount()
            return
        }
        update { it.copy(digits = "", scannedSats = fixedAmount, mode = KeypadMode.SEND) }
        push(Route.REVIEW)
    }

    /** Picking a recent pre-fills the recipient and jumps to a fresh send keypad. */
    fun pickRecent(contact: Contact) {
        update {
            it.copy(
                input = contact.handle,
                sendWho = contact.who,
                digits = "",
                scannedSats = null,
                mode = KeypadMode.SEND,
            )
        }
        push(Route.AMOUNT)
    }

    /**
     * The simulated scan finds Ferry Building Coffee for 520 sats and jumps straight to review.
     * The invoice amount is stored as sats — never as keypad digits, whose meaning depends on
     * the current denomination (digits are cents in fiat mode).
     */
    fun scanFound() {
        update {
            it.copy(
                input = SCAN_HANDLE,
                sendWho = SCAN_NAME,
                digits = "",
                scannedSats = SCAN_SATS,
                mode = KeypadMode.SEND,
            )
        }
        go(Route.REVIEW)
    }

    /**
     * Confirms the payment: snapshots the recipient and amount (so later keypad edits can't
     * alter what's sent or shown on the sent screen), emits the sending route first, then
     * awaits [LarkCore.send] (the working delay lives inside the core) and lands on sent
     * or failed.
     */
    fun confirmSend() {
        val sats = typedSats(state)
        update {
            it.copy(
                confirmedRecipient = it.input.ifEmpty { it.sendWho },
                confirmedSats = sats,
                confirmedAmountDisplay = primary(sats, it.denomination),
            )
        }
        startSend()
    }

    /** The failed screen's "Try again": re-runs the same send from the confirmed snapshot. */
    fun tryAgain() = startSend()

    /** Runs the confirmed-snapshot send behind the working spinner. */
    private fun startSend() {
        push(Route.SENDING)
        workJob?.cancel()
        workJob = scope.launch {
            val result = core.send(state.confirmedRecipient, state.confirmedSats)
            landIfStillSending(landingFor(result))
        }
    }

    /**
     * Where a send outcome lands. Matched exhaustively over the sealed [SendResult] on purpose:
     * a future outcome must fail the build rather than fall silently into one of these screens,
     * which is how [SendResult.Pending] would otherwise have landed on "Didn't go through."
     */
    private fun landingFor(result: SendResult): Route = when (result) {
        SendResult.Success -> Route.SENT
        SendResult.Pending -> Route.PENDING
        SendResult.Failure -> Route.FAILED
    }

    /** Lands [route] only if the user is still on the working screen — a stale job must not steal the route. */
    private fun landIfStillSending(route: Route) = update {
        if (it.route == Route.SENDING) it.copy(route = route, stack = emptyList()) else it
    }

    // --- Funding ---

    /**
     * Whether the user's request for money to arrive is still current.
     *
     * Purely a question about the window, with no reference to the balance — deliberately.
     *
     * The tempting alternative, "expired *unless* money is sitting there", reads the balance at the
     * moment the question is asked, which means money arriving long after a request lapsed would
     * revive it. That is precisely the money nobody asked for. Money that arrives *while* the
     * request is live is kept safe the other way round: [pollFundingOnce] pushes the deadline
     * forward for as long as it can see funds, so a deposit under the minimum can sit for weeks
     * without going quiet, while an empty wallet lets the request lapse on schedule.
     */
    private val fundingArmed: Boolean
        get() {
            val armedAt = funding?.fundingArmedAtMillis ?: return false
            return wallClockMillis() - armedAt < FUNDING_ARM_WINDOW_MILLIS
        }

    /**
     * The one place that makes an arrived deposit spendable.
     *
     * Single call site by design: "does the app ever move money the user did not ask it to" is a
     * question worth being able to answer by reading one function, and every alternative — a
     * button, a screen-scoped poll, a convenience helper — reintroduces a second place to audit.
     *
     * Runs while armed, syncing the chain (bark's own maintenance deliberately does not) and
     * boarding whatever has confirmed. Polls briskly while something is on its way and slowly
     * while the wallet is empty, because an armed wallet with nothing in it still has to notice a
     * deposit that has not been made yet.
     */
    private fun startFundingWatcher() {
        if (funding == null) return
        fundingWatcherJob?.cancel()
        fundingWatcherJob = scope.launch {
            while (true) {
                if (!fundingArmed) {
                    // Erase rather than merely stop. A lapsed request left on disk would spring
                    // back to life the next time any money appeared on-chain — including funds
                    // from an exit — because the balance clause above would re-qualify it.
                    funding.disarmFunding()
                    return@launch
                }
                pollFundingOnce()
                delay(if (funding.onchainSats > 0) FUNDING_POLL_ACTIVE_MILLIS else FUNDING_POLL_IDLE_MILLIS)
            }
        }
    }

    /**
     * One watcher tick: see what has arrived, make it spendable if it can be, then let it register.
     *
     * The trailing refresh is not optional. A board becomes a spendable VTXO only once its
     * transaction confirms *and* the wallet registers it, and registration happens inside the
     * engine's maintenance pass — without this the balance would never move and the pending line
     * would never clear.
     */
    private suspend fun pollFundingOnce() {
        val funding = funding ?: return
        funding.syncOnchain()
        // Money the user is already waiting on keeps their request current. Without this a deposit
        // stuck under the minimum would go quiet after a week — no board, and no watcher left to
        // notice the top-up that would have fixed it.
        //
        // Only once the request is half spent, rather than every pass: this is a write to disk, and
        // a deposit in flight ticks every twenty seconds.
        val armedAt = funding.fundingArmedAtMillis
        val now = wallClockMillis()
        if (funding.onchainSats > 0 && armedAt != null && now - armedAt > FUNDING_ARM_WINDOW_MILLIS / 2) {
            funding.armFunding(now)
        }
        if (funding.confirmedSats >= funding.minBoardSats) {
            // The whole balance, never a named amount: the fee comes out of the same coins, so
            // asking for exactly the confirmed balance can never succeed. See OnchainFunding.
            val boarded = funding.boardAll()
            update { it.copy(boardFailures = if (boarded) 0 else it.boardFailures + 1) }
        }
        core.refresh()
    }

    // --- Refresh ---

    /** Runs the health refresh behind the working spinner, then lands home ready. */
    fun runRefresh() {
        go(Route.SENDING)
        workJob?.cancel()
        workJob = scope.launch {
            core.refresh()
            landIfStillSending(Route.HOME)
        }
    }

    // --- Backup (KTD-9) ---

    /**
     * Reveals the words and starts the 60s countdown; on expiry they re-hide (AE4).
     * The deadline is absolute — [nowMillis] plus 60s — and every tick recomputes the
     * remaining time from the clock, so suspended ticks (e.g. iOS backgrounding) cannot
     * extend the reveal: the first tick after resume counts all elapsed real time.
     */
    fun revealWords() {
        val deadline = nowMillis() + COUNTDOWN_SECONDS * ONE_SECOND_MILLIS
        update { it.copy(wordsRevealed = true, countdown = COUNTDOWN_SECONDS) }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (true) {
                delay(ONE_SECOND_MILLIS)
                val remainingMillis = deadline - nowMillis()
                if (remainingMillis <= 0) {
                    update { it.copy(wordsRevealed = false, countdown = COUNTDOWN_SECONDS) }
                    break
                }
                update { it.copy(countdown = wholeSecondsCeil(remainingMillis)) }
            }
        }
    }

    /** Millis → whole seconds, rounding up: 1ms left still shows 1, never 0. */
    private fun wholeSecondsCeil(millis: Long): Int =
        ((millis + ONE_SECOND_MILLIS - 1) / ONE_SECOND_MILLIS).toInt()

    /**
     * "I've written them down": marks backed up, hides the words (cancelling the reveal
     * countdown so a stale timer can't fire later), and pops back to settings.
     */
    fun finishBackup() {
        countdownJob?.cancel()
        countdownJob = null
        core.markBackedUp()
        update { it.copy(wordsRevealed = false, countdown = COUNTDOWN_SECONDS) }
        back()
    }

    // --- Receive ---

    /** Copy flips to "Copied" and clears itself after 1.6s. */
    fun copyCode() {
        update { it.copy(copied = true) }
        copyJob?.cancel()
        copyJob = scope.launch {
            delay(COPY_FLIP_MILLIS)
            update { it.copy(copied = false) }
        }
    }

    // --- Transaction detail ---

    /** Opens the payment detail for activity row [index]. */
    fun openTx(index: Int) {
        update { it.copy(txIndex = index) }
        push(Route.TX_DETAIL)
    }

    // --- Demo controls (KTD-3) ---

    /** Forces a health state via [DemoControls] and returns home; no-op without demo controls. */
    fun forceHealth(health: HealthState) {
        val controls = demo ?: return
        controls.forceHealth(health)
        go(Route.HOME)
    }

    // --- Rendering ---

    private fun update(transform: (MachineState) -> MachineState) {
        state = transform(state)
        modelFlow.value = render(state)
    }

    private fun restingRoute(): Route = if (core.walletExists.value) Route.HOME else Route.WELCOME

    /**
     * The amount in play, always in sats. A scanned invoice amount ([MachineState.scannedSats])
     * is already sats and wins outright; typed digits are sats in btc mode and cents in fiat
     * mode (KTD-6).
     */
    private fun typedSats(s: MachineState): Long {
        s.scannedSats?.let { return it }
        val typed = s.digits.toLongOrNull() ?: 0L
        return if (s.denomination == Denomination.FIAT) core.fiatRate.centsToSats(typed) else typed
    }

    private fun isOverBalance(s: MachineState): Boolean =
        s.mode == KeypadMode.SEND && typedSats(s) > core.balanceSats.value

    private fun primary(sats: Long, denomination: Denomination): String =
        if (denomination == Denomination.FIAT) MoneyFormat.fiat(sats, core.fiatRate) else MoneyFormat.btc(sats)

    private fun secondary(sats: Long, denomination: Denomination): String =
        if (denomination == Denomination.FIAT) MoneyFormat.btc(sats) else MoneyFormat.fiat(sats, core.fiatRate)

    private fun render(s: MachineState): AppModel {
        val advanced = core.advancedStats()
        return AppModel(
            route = s.route,
            canGoBack = s.route != Route.SENDING && (s.stack.isNotEmpty() || s.route != restingRoute()),
            screenLabel = s.route.screenLabel,
            denomination = s.denomination,
            balance = renderBalance(s),
            exitAmount = primary(core.balanceSats.value, s.denomination),
            health = renderHealth(),
            keypad = renderKeypad(s),
            send = renderSend(s),
            sentAmount = s.confirmedAmountDisplay,
            txDetail = renderTxDetail(s),
            activity = core.activity.map { renderActivityRow(it, s.denomination) },
            recents = core.recents,
            backup = renderBackup(s),
            receive = renderReceive(s),
            advanced = advanced,
            channels = renderChannels(s, placeholder = advanced.network.lightningBridge),
            demoHealth = renderDemoHealth(),
            networkLabel = core.networkLabel,
            restore = RestoreModel(busy = s.restoring, failed = s.restoreFailed),
            deposit = renderDeposit(s),
        )
    }

    /**
     * Advanced's Lightning bridge value + channel rows (plan U5, R7). A null snapshot means
     * never fetched (the demo core and the stock gateway stay there forever): the bridge row
     * keeps the core's own [placeholder] string exactly as today. Channel sat amounts follow
     * the hidden-balance mask; counts, state labels, and expiry lines stay visible.
     */
    private fun renderChannels(s: MachineState, placeholder: String): ChannelsModel {
        val snapshot = core.channels.value
        return when {
            snapshot == null -> ChannelsModel(bridgeValue = placeholder, rows = emptyList())
            snapshot.channels.isEmpty() -> ChannelsModel(bridgeValue = "0 channels", rows = emptyList())
            else -> {
                val count = snapshot.channels.size
                val noun = if (count == 1) "channel" else "channels"
                ChannelsModel(
                    // R7: the rows sum to the bridge total, so the total is their sum.
                    bridgeValue = "$count $noun · ${maskableBtc(snapshot.channels.sumOf { it.localSat }, s)}",
                    rows = snapshot.channels.map { renderChannelRow(it, s) },
                )
            }
        }
    }

    private fun renderChannelRow(channel: ChannelDisplay, s: MachineState): ChannelRowModel = ChannelRowModel(
        shortId = channel.shortId,
        value = "${maskableBtc(channel.localSat, s)} of ${maskableBtc(channel.capacitySat, s)}" +
            " · ${channel.state.label}",
        expiryLabel = channel.expiryLabel,
    )

    /** A sat figure honoring the hidden-balance mask (R7): btc when visible, dots when hidden. */
    private fun maskableBtc(sats: Long, s: MachineState): String =
        if (s.balanceVisible) MoneyFormat.btc(sats) else HIDDEN_BALANCE

    private fun renderBalance(s: MachineState): BalanceModel {
        val sats = core.balanceSats.value
        return BalanceModel(
            visible = s.balanceVisible,
            hideLabel = if (s.balanceVisible) "Hide" else "Show",
            primary = if (s.balanceVisible) primary(sats, s.denomination) else HIDDEN_BALANCE,
            secondary = if (s.balanceVisible) secondary(sats, s.denomination) else HIDDEN_BALANCE,
            unitLabel = if (s.denomination == Denomination.FIAT) "Dollars" else "Bitcoin (₿)",
            arriving = renderArriving(s, masked = !s.balanceVisible),
        )
    }

    /**
     * What to say about money that has arrived but cannot be spent yet, or null when none has.
     *
     * One renderer for both Home and the deposit screen. The three notes are the only places the
     * app explains this wait, and all three are phrased in terms of spending — never movement,
     * never a second place money lives.
     */
    private fun renderArriving(s: MachineState, masked: Boolean): ArrivingModel? {
        val funding = funding
        if (funding == null || funding.onchainSats == 0L) return null
        val arriving = funding.onchainSats
        // Only definite when nothing is still confirming: pending funds may yet carry the total
        // over the minimum, and calling that a shortfall would send the user to top up for nothing.
        val belowMinimum = funding.pendingSats == 0L && funding.confirmedSats < funding.minBoardSats
        return ArrivingModel(
            amount = if (masked) HIDDEN_BALANCE else MoneyFormat.btc(arriving),
            note = when {
                s.boardFailures >= BOARD_FAILURES_BEFORE_SURFACING ->
                    "This is taking longer than it should. LARK is still trying."
                belowMinimum ->
                    "You need at least ${MoneyFormat.btc(funding.minBoardSats)} before you can " +
                        "spend it. Send a little more and it will all come through together."
                else -> "You will be able to spend this in a few minutes."
            },
        )
    }

    private fun renderHealth(): HealthModel {
        val health = core.health.value
        val display = health.display
        return HealthModel(
            word = display.indicator.word,
            wordVisible = display.banner == null, // AE5: the banner carries the message
            dotColorHex = display.indicator.dotColorHex,
            banner = display.banner,
            offline = health == HealthState.OFFLINE,
            statusTitle = display.status.title,
            statusBody = display.status.body,
            actionLabel = display.status.actionLabel,
            aspStatus = display.aspStatus,
        )
    }

    private fun renderKeypad(s: MachineState): KeypadModel {
        val sats = typedSats(s)
        val over = isOverBalance(s)
        val fiatFirst = s.denomination == Denomination.FIAT
        val display = when {
            s.digits.isEmpty() && s.scannedSats == null -> if (fiatFirst) "$0" else "₿0"
            else -> primary(sats, s.denomination)
        }
        val availability = when {
            over -> "More than you have"
            s.mode == KeypadMode.RECEIVE -> "Any amount"
            else -> "${primary(core.balanceSats.value, s.denomination)} available"
        }
        return KeypadModel(
            digits = s.digits,
            mode = s.mode,
            amountDisplay = display,
            amountSecondary = secondary(sats, s.denomination),
            header = if (s.mode == KeypadMode.RECEIVE) "Request" else "Paying ${s.sendWho}",
            availability = availability,
            overBalance = over,
            primaryLabel = if (s.mode == KeypadMode.RECEIVE) "Make the code" else "Review",
            primaryEnabled = s.digits.isNotEmpty() && !over,
        )
    }

    /**
     * [SendModel.inputResolved] means *recognized as payable*, not merely non-empty: the Continue
     * pill and the gold border key off it, so treating unparseable text as resolved would invite
     * a send the core is going to refuse.
     */
    private fun renderSend(s: MachineState): SendModel {
        val input = classifySendInput(s.input)
        return SendModel(
            recipientName = s.sendWho.ifEmpty { input.display },
            recipientHandle = s.input,
            inputDisplay = s.input.ifEmpty { INPUT_PLACEHOLDER },
            inputResolved = input.isResolved,
            inputSummary = sendInputSummary(s, input),
            fixedAmount = input.amountSat != null,
        )
    }

    /** The line under the input card: what was recognized, or that nothing was. */
    private fun sendInputSummary(s: MachineState, input: SendInput): String = when {
        s.input.isBlank() && s.pasteFailed ->
            "Nothing came through from the clipboard. Long-press the field to paste, or type it in."
        s.input.isBlank() -> "A name, an invoice, or a bitcoin address — LARK works out the rest."
        !input.isResolved -> "That doesn’t look like an invoice or address LARK can pay."
        input.amountSat != null -> "Invoice for ${primary(input.amountSat, s.denomination)}."
        else -> "Ready to pay ${input.display}."
    }

    private fun renderTxDetail(s: MachineState): TxDetailModel {
        val tx = core.activity.getOrNull(s.txIndex) ?: core.activity.firstOrNull()
            ?: return placeholderTxDetail(s)
        val sats = if (tx.sats < 0) -tx.sats else tx.sats
        return TxDetailModel(
            verb = if (tx.isSent) "Sent" else "Received",
            amount = (if (tx.isSent) "" else "+") + primary(sats, s.denomination),
            secondaryAmount = secondary(sats, s.denomination),
            partyLabel = if (tx.isSent) "To" else "From",
            party = tx.who,
            whenLabel = tx.whenLabel,
            fee = "None",
        )
    }

    /** Benign placeholder for a core with empty payment history (the [LarkCore] contract permits it). */
    private fun placeholderTxDetail(s: MachineState): TxDetailModel = TxDetailModel(
        verb = "Sent",
        amount = primary(0L, s.denomination),
        secondaryAmount = secondary(0L, s.denomination),
        partyLabel = "To",
        party = "",
        whenLabel = "",
        fee = "None",
    )

    private fun renderActivityRow(tx: Transaction, denomination: Denomination): ActivityRowModel =
        ActivityRowModel(
            who = tx.who,
            whenLabel = tx.whenLabel,
            initial = tx.initial,
            amount = if (denomination == Denomination.FIAT) {
                MoneyFormat.signedFiat(tx.sats, core.fiatRate)
            } else {
                MoneyFormat.signedBtc(tx.sats)
            },
            incoming = tx.sats > 0,
            pending = tx.pending,
        )

    /**
     * The deposit step, or null when the core has no on-chain wallet — which is what removes the
     * step from the funding screen rather than offering an address that leads nowhere.
     */
    private fun renderDeposit(s: MachineState): DepositModel? {
        val funding = funding ?: return null
        return DepositModel(
            address = core.depositAddress,
            copyLabel = if (s.copied) "Copied" else "Copy",
            minLabel = MoneyFormat.btc(funding.minBoardSats),
            // Never masked, following the exit screen's precedent: a screen whose whole job is to
            // report what arrived should not hide it because the home balance is hidden.
            arriving = renderArriving(s, masked = false),
        )
    }

    private fun renderBackup(s: MachineState): BackupModel {
        val backedUp = core.backedUp.value
        return BackupModel(
            words = core.backupWords,
            revealed = s.wordsRevealed,
            countdown = s.countdown,
            backedUp = backedUp,
            statusLabel = if (backedUp) "Done" else "Not done yet",
        )
    }

    /**
     * The requested-amount code when one has landed, else the core's amountless code — so the
     * QR and the code box always show the same live string (the one-source rule).
     */
    private fun renderReceive(s: MachineState): ReceiveModel = ReceiveModel(
        // A blank answer counts as no answer: a core asked for a code before it had minted an
        // address returns "", and treating that as a real value would pin Get paid blank even
        // after a later poll produced a usable code.
        code = s.receiveCode?.takeIf { it.isNotEmpty() } ?: core.receiveCode,
        copied = s.copied,
        copyLabel = if (s.copied) "Copied" else "Copy",
        requestedAmount = if (s.receiveRequestSats > 0L) primary(s.receiveRequestSats, s.denomination) else null,
    )

    private fun renderDemoHealth(): List<DemoHealthOption>? {
        if (demo == null) return null
        val current = core.health.value
        return DEMO_HEALTH_COPY.map { (health, copy) ->
            DemoHealthOption(
                state = health,
                label = copy.first,
                note = copy.second,
                dotColorHex = health.display.indicator.dotColorHex,
                selected = health == current,
            )
        }
    }
}
