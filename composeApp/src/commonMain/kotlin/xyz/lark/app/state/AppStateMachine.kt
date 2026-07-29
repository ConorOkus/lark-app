package xyz.lark.app.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.lark.app.core.DemoControls
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.format.MoneyFormat
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction

private const val MAX_DIGITS = 8
private const val COUNTDOWN_SECONDS = 60
private const val ONE_SECOND_MILLIS = 1_000L
private const val COPY_FLIP_MILLIS = 1_600L
private const val DEFAULT_RECIPIENT = "Jack"
private const val INPUT_PLACEHOLDER = "Name, invoice or address"
private const val PASTED_HANDLE = "jack@lark.money"
private const val SCAN_NAME = "Ferry Building Coffee"
private const val SCAN_HANDLE = "ferry@sq.link"
private const val SCAN_DIGITS = "520"
private const val HIDDEN_BALANCE = "••••"

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
)

/**
 * The app-wide state machine (KTD-2, Bitkey-style: plain class + coroutines, no androidx
 * ViewModel). Owns the hand-rolled route stack (KTD-4) and all screen state, and exposes one
 * [StateFlow] of [AppModel] plus intent functions mirroring the design prototype's Component
 * class (`docs/design/lark-wallet/LARK Wallet.dc.html`).
 *
 * All timing (send/refresh spinners inside the core, the 60s backup countdown, the 1.6s
 * copy flip) runs on the injected [scope], so tests drive it with virtual time (KTD-9).
 * [demo] is the demo-only seam (KTD-3): when absent, demo affordances vanish from the model.
 */
@Suppress("TooManyFunctions") // one small intent function per prototype interaction, by design
class AppStateMachine(
    private val core: LarkCore,
    private val demo: DemoControls? = null,
    private val scope: CoroutineScope,
) {

    private var state = MachineState(route = restingRoute())
    private val modelFlow = MutableStateFlow(render(state))

    /** The single immutable model the UI renders. */
    val model: StateFlow<AppModel> = modelFlow.asStateFlow()

    private var workJob: Job? = null
    private var countdownJob: Job? = null
    private var copyJob: Job? = null

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
        update { it.copy(digits = it.digits + digit) }
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
        if (s.mode == KeypadMode.RECEIVE) back() else push(Route.REVIEW)
    }

    // --- Send flow ---

    /** Opens the keypad to pay the current recipient; digits reset, send mode. */
    fun goSendAmount() {
        update { it.copy(digits = "", mode = KeypadMode.SEND) }
        push(Route.AMOUNT)
    }

    /** Opens the keypad to request an amount; digits reset, receive mode. */
    fun goReceiveAmount() {
        update { it.copy(digits = "", mode = KeypadMode.RECEIVE) }
        push(Route.AMOUNT)
    }

    /** The paste affordance resolves the demo invoice: jack@lark.money / Jack. */
    fun pasteInvoice() = update { it.copy(input = PASTED_HANDLE, sendWho = DEFAULT_RECIPIENT) }

    /** Picking a recent pre-fills the recipient and jumps to a fresh send keypad. */
    fun pickRecent(contact: Contact) {
        update { it.copy(input = contact.handle, sendWho = contact.who, digits = "", mode = KeypadMode.SEND) }
        push(Route.AMOUNT)
    }

    /** The simulated scan finds Ferry Building Coffee for 520 sats and jumps straight to review. */
    fun scanFound() {
        update { it.copy(input = SCAN_HANDLE, sendWho = SCAN_NAME, digits = SCAN_DIGITS, mode = KeypadMode.SEND) }
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
            val landing = if (result is SendResult.Success) Route.SENT else Route.FAILED
            landIfStillSending(landing)
        }
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

    /** Reveals the words and starts the 60s countdown; on expiry they re-hide (AE4). */
    fun revealWords() {
        update { it.copy(wordsRevealed = true, countdown = COUNTDOWN_SECONDS) }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = COUNTDOWN_SECONDS
            while (remaining > 0) {
                delay(ONE_SECOND_MILLIS)
                remaining -= 1
                if (remaining == 0) {
                    update { it.copy(wordsRevealed = false, countdown = COUNTDOWN_SECONDS) }
                } else {
                    update { it.copy(countdown = remaining) }
                }
            }
        }
    }

    /** "I've written them down": marks backed up and pops back to settings. */
    fun finishBackup() {
        core.markBackedUp()
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

    /** Digits are sats in btc mode and cents in fiat mode (KTD-6). */
    private fun typedSats(s: MachineState): Long {
        val typed = s.digits.toLongOrNull() ?: 0L
        return if (s.denomination == Denomination.FIAT) core.fiatRate.centsToSats(typed) else typed
    }

    private fun isOverBalance(s: MachineState): Boolean =
        s.mode == KeypadMode.SEND && typedSats(s) > core.balanceSats.value

    private fun primary(sats: Long, denomination: Denomination): String =
        if (denomination == Denomination.FIAT) MoneyFormat.fiat(sats, core.fiatRate) else MoneyFormat.btc(sats)

    private fun secondary(sats: Long, denomination: Denomination): String =
        if (denomination == Denomination.FIAT) MoneyFormat.btc(sats) else MoneyFormat.fiat(sats, core.fiatRate)

    private fun render(s: MachineState): AppModel = AppModel(
        route = s.route,
        canGoBack = s.route != Route.SENDING && (s.stack.isNotEmpty() || s.route != restingRoute()),
        screenLabel = s.route.screenLabel,
        denomination = s.denomination,
        balance = renderBalance(s),
        health = renderHealth(),
        keypad = renderKeypad(s),
        send = renderSend(s),
        sentAmount = s.confirmedAmountDisplay,
        txDetail = renderTxDetail(s),
        activity = core.activity.map { renderActivityRow(it, s.denomination) },
        recents = core.recents,
        backup = renderBackup(s),
        receive = renderReceive(s),
        advanced = core.advancedStats(),
        demoHealth = renderDemoHealth(),
        networkLabel = core.networkLabel,
    )

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
            s.digits.isEmpty() -> if (fiatFirst) "$0" else "₿0"
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

    private fun renderSend(s: MachineState): SendModel = SendModel(
        recipientName = s.sendWho,
        recipientHandle = s.input,
        inputDisplay = s.input.ifEmpty { INPUT_PLACEHOLDER },
        inputResolved = s.input.isNotEmpty(),
    )

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

    private fun renderReceive(s: MachineState): ReceiveModel = ReceiveModel(
        code = core.receiveCode,
        copied = s.copied,
        copyLabel = if (s.copied) "Copied" else "Copy",
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
