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
) {

    private var state = MachineState(route = restingRoute())
    private val modelFlow = MutableStateFlow(render(state))

    /** The single immutable model the UI renders. */
    val model: StateFlow<AppModel> = modelFlow.asStateFlow()

    private var workJob: Job? = null
    private var countdownJob: Job? = null
    private var copyJob: Job? = null
    private var receiveCodeJob: Job? = null

    init {
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
            it.stack.isEmpty() -> it.copy(route = restingRoute())
            else -> it.copy(route = it.stack.last(), stack = it.stack.dropLast(1))
        }
    }

    /** Navigates to [route] and resets the stack (tab-level navigation). */
    fun go(route: Route) = update { it.copy(route = route, stack = emptyList()) }

    // --- Onboarding ---

    fun goHowItWorks() = push(Route.HOW_IT_WORKS)

    fun goFund() = push(Route.FUND)

    fun goRestore() = push(Route.RESTORE)

    fun startBoarding() = push(Route.BOARDING)

    /** Completing onboarding (settling done, or "Later" on fund) creates the wallet and lands home. */
    fun finishOnboarding() {
        core.createWallet()
        go(Route.HOME)
    }

    /** Restoring creates the wallet and lands home. */
    fun finishRestore() {
        core.restoreWallet()
        go(Route.HOME)
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
        it.copy(input = raw, sendWho = "", scannedSats = null, digits = "")
    }

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
        )

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
