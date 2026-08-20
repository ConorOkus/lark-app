@file:OptIn(ExperimentalTime::class) // kotlin.time Clock/Instant: stdlib-experimental, stable enough for M2

package xyz.lark.app.core.ffi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.format.blockExpiryLabel
import xyz.lark.app.core.format.roundCountdownLabel
import xyz.lark.app.core.EXIT_STALL_THRESHOLD
import xyz.lark.app.core.ExitReceipt
import xyz.lark.app.core.ExitStage
import xyz.lark.app.core.ExitStatus
import xyz.lark.app.core.OnchainFunding
import xyz.lark.app.core.OnchainSend
import xyz.lark.app.core.OnchainSendQuote
import xyz.lark.app.core.WalletExit
import xyz.lark.app.core.CachedInvoice
import xyz.lark.app.core.receiveCodeFor
import xyz.lark.app.core.reusableInvoice
import xyz.lark.app.core.gateway.arkReceiveUri
import xyz.lark.app.core.model.AdvancedStats
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.FiatRate
import xyz.lark.app.core.model.FundsStats
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.NetworkStats
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/** Em-dash for numbers the in-process core cannot supply yet; never a fake value (R9). */
private const val PLACEHOLDER = "—"

/** No price source exists on device, exactly as for the other cores (R8). */
private const val DEMO_SATS_PER_CENT = 10L

/** captaind's board minimum on the team stack; see [DelegateBackedLarkCore.minBoardSats]. */
private const val MIN_BOARD_SATS = 20_000L

/** The Ark round schedule arrives in seconds; the injected wall clock is in millis. */
private const val MILLIS_PER_SECOND = 1_000L

/** How often the poll loop re-reads wallet state once the wallet is open. */
private val DEFAULT_POLL_INTERVAL = 15.seconds

/**
 * Bound on one server round trip taken inside a poll cycle; see [awaitServerValue].
 *
 * Comfortably under [DEFAULT_POLL_INTERVAL] so a timing-out call cannot let one cycle overrun the
 * next, and well above any healthy round trip to captaind.
 */
private val SERVER_CALL_TIMEOUT = 5.seconds

/**
 * Run the engine's maintenance pass every Nth poll cycle — at the default cadence, about once a
 * minute.
 *
 * Not optional housekeeping. Reading the balance only reads the database; it is *maintenance* that
 * registers a confirmed board, claims an incoming Lightning payment, and refreshes VTXOs nearing
 * expiry. With a gateway a daemon did this whether or not anyone opened the app; on device nothing
 * does it unless the core does, and without it money can sit boarded-but-invisible indefinitely.
 * Onboarding promises "LARK keeps your wallet ready in the background" — this is that promise.
 */
private const val MAINTENANCE_EVERY_N_CYCLES = 4

/**
 * How long a minted invoice is reused for the same requested amount.
 *
 * Deliberately far short of the server's own `invoice_expiry` (48h on our captaind): the window
 * exists to stop a holder who adjusts the amount, backs out, and asks again from leaving a trail
 * of live invoices the server must hold and every maintenance pass must try to claim. It is not
 * an attempt to track how long the invoice stays payable, which is the server's business.
 */
private const val MINTED_INVOICE_REUSE_SECONDS = 600L

/**
 * The in-process core's tunable knobs, grouped like `GatewayTuning` so the constructor stays short
 * and tests can vary timing without touching the rest.
 *
 * [secondsPerBlock] exists only to turn an expiry height into human time. It defaults to mutinynet's
 * 30 seconds; computing a countdown with the wrong spacing is off by the ratio between them, which
 * against Bitcoin's 10-minute target is 20x — the difference between a useful expiry warning and a
 * dangerously reassuring one.
 */
data class FfiTuning(
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    val secondsPerBlock: Int = MUTINYNET_SECONDS_PER_BLOCK,
    /**
     * Wall clock, here rather than on the constructor for the same reason the rest of this class
     * is: it is a knob, not a collaborator. Wall rather than monotonic because the exit timings it
     * stamps are compared across app launches, where a monotonic reading means nothing.
     */
    val wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
)

/** mutinynet targets 30-second blocks; see [FfiTuning.secondsPerBlock]. */
private const val MUTINYNET_SECONDS_PER_BLOCK = 30

/**
 * The seam over the in-process Rust core, reached through a platform [LarkCoreDelegate] (M2 U3).
 *
 * Three problems this solves, none of them business logic:
 *
 * 1. **Swift cannot implement `suspend`** (KT-38974). Every delegate call is completion-handler
 *    shaped and is lifted back into a suspending seam member here, with `suspendCancellableCoroutine`.
 * 2. **The seam is partly synchronous while the engine is not.** `createWallet` returns immediately
 *    and `receiveCode` is a plain property, but opening a wallet is a couple of seconds of chain
 *    work and minting an address is a server round-trip. So state is cached in fields written only by
 *    the poll loop, the same shape `GatewayLarkCore` uses.
 * 3. **The resting route is decided before any open can finish.** [walletExists] therefore starts
 *    from [LarkSecureStore.walletFileExists] — a synchronous disk check — so a returning user lands
 *    on home immediately instead of seeing welcome flash past.
 */
@Suppress("TooManyFunctions") // one small member per seam concern, mirroring GatewayLarkCore
class DelegateBackedLarkCore(
    private val delegate: LarkCoreDelegate,
    private val store: LarkSecureStore,
    private val scope: CoroutineScope,
    private val config: FfiWalletConfig,
    override val networkLabel: String,
    private val tuning: FfiTuning = FfiTuning(),
) : LarkCore, OnchainFunding, WalletExit, OnchainSend {

    /** Seeded from disk, not from the open: see the class comment's point 3. */
    private val walletExistsFlow = MutableStateFlow(store.walletFileExists())
    private val balanceFlow = MutableStateFlow(0L)

    /**
     * OFFLINE until the wallet is actually open.
     *
     * The honest choice among the four design states: TIDYING and READY both tell the user their
     * money is spendable right now, which is false while `open_wallet` is still running. OFFLINE
     * overstates the cause ("can't reach the network") but not the capability. A fifth "opening"
     * state is a design change, and is noted as such rather than invented here.
     */
    private val healthFlow = MutableStateFlow(HealthState.OFFLINE)
    private val backedUpFlow = MutableStateFlow(store.isBackedUp())

    /** Serializes poll cycles (loop and refresh) against each other. */
    private val pollMutex = Mutex()

    /** Serializes send's check-then-pay so racing sends cannot jointly overdraw. */
    private val sendMutex = Mutex()
    /** Serializes check-then-mint so two rapid asks for one amount cannot mint twice. */
    private val mintMutex = Mutex()

    /** Conflated "poll now" signal; senders never block and repeats collapse into one cycle. */
    private val pollTrigger = Channel<Unit>(Channel.CONFLATED)

    /** True once `open_wallet` has succeeded. Every other delegate call requires it. */
    private var walletOpen = false
    private var openInFlight = false

    // Delegate-fed state behind the seam's plain properties; written only by poll cycles.
    private var activityRows: List<Transaction> = emptyList()
    private var recentRows: List<Contact> = emptyList()
    private var words: List<String> = store.loadWords().orEmpty()
    private var receiveCodeCache: String? = null
    private var mintedInvoice: CachedInvoice? = null
    private var depositAddressCache: String? = null
    private var onchain: FfiOnchainBalance? = null
    private var vtxos: FfiVtxoSummary? = null

    /**
     * Last known chain tip, refreshed on the maintenance cadence rather than every poll.
     *
     * Reading it is an uncached HTTP request, and its only consumer is a countdown measured in days
     * — so fetching it four times a minute on a phone would be pure waste. Held across cycles so a
     * failed read leaves the previous value standing instead of blanking the countdown.
     */
    private var tipHeight: Long? = null

    /**
     * When the Ark server expects to start its next round, as a UNIX timestamp in seconds.
     *
     * Stored as the server's instant rather than as a rendered countdown so the label is computed
     * fresh on every read: the poll interval is long enough that a duration captured here would be
     * visibly stale on screen. Refetched only once the stored instant has passed — a round schedule
     * does not move, so re-asking before then would spend a round trip to learn what we know.
     */
    private var nextRoundEpochSeconds: Long? = null

    override val walletExists: StateFlow<Boolean> = walletExistsFlow.asStateFlow()
    override val balanceSats: StateFlow<Long> = balanceFlow.asStateFlow()
    override val health: StateFlow<HealthState> = healthFlow.asStateFlow()
    override val backedUp: StateFlow<Boolean> = backedUpFlow.asStateFlow()

    override val fiatRate: FiatRate = FiatRate(satsPerCent = DEMO_SATS_PER_CENT)
    override val activity: List<Transaction> get() = activityRows
    override val recents: List<Contact> get() = recentRows
    override val receiveCode: String get() = receiveCodeCache.orEmpty()
    override val depositAddress: String get() = depositAddressCache.orEmpty()

    /** The words this device generated, straight from secure storage — never from the crate. */
    override val backupWords: List<String> get() = words

    // --- OnchainFunding ---

    override val confirmedSats: Long get() = onchain?.confirmedSat ?: 0L
    override val pendingSats: Long get() = onchain?.pendingSat ?: 0L

    /**
     * captaind's `min_board_amount` on the team's mutinynet stack.
     *
     * A constant rather than a value read from the server: the crate exposes no accessor for the
     * server's board minimum, and boarding under it fails with an error the user cannot act on. It
     * has to move in step with deploy/fly/captaind.toml.template.
     */
    override val minBoardSats: Long = MIN_BOARD_SATS

    /**
     * Read through to the store on every access rather than cached in a field.
     *
     * The value is a small file read, and caching it would mean holding a copy that a future
     * second reader (a widget, a background refresh) could silently contradict. Correctness over a
     * saving that does not matter at this call rate.
     */
    override val fundingArmedAtMillis: Long? get() = store.loadFundingArmedAt()

    override fun armFunding(atMillis: Long) = store.storeFundingArmedAt(atMillis)

    override fun disarmFunding() = store.storeFundingArmedAt(null)

    // --- WalletExit ---

    /**
     * Consecutive progress passes that came back with an error.
     *
     * Counted here rather than in the crate because "how many failures means stalled" is app
     * policy, and a threshold compiled into the FFI would be beyond the app's reach. Any clean
     * pass resets it, so a stall reported once can clear.
     */
    private var consecutiveExitFailures = 0

    override var exitStatus: ExitStatus = ExitStatus.NOT_EXITING
        private set

    override suspend fun startExit() {
        delegate.awaitDone { onDone -> startExit(onDone) }
        // Stamped before the first status read so a crash between the two still leaves a start to
        // measure from. An exit whose duration is unknown is a smaller loss than one whose start
        // is recorded after hours of progress.
        if (store.loadExitTimes() == null) store.storeExitTimes(ExitTimes(startedAt = tuning.wallClockMillis()))
        refreshExitStatus()
    }

    override suspend fun progressExit(): ExitStatus {
        val reported = delegate.awaitValue<FfiExitStatus> { onResult -> progressExit(onResult) }
        consecutiveExitFailures = when {
            // A pass that could not run at all is as much a failure to advance as one the crate
            // reported an error for; treating a dropped call as "fine" would hide a real stall.
            reported == null -> consecutiveExitFailures + 1
            reported.errors.isEmpty() -> 0
            else -> consecutiveExitFailures + 1
        }
        // A pass that could not be read still counts, and still has to be sayable. Falling back to
        // the previous status unchanged would drop the stall along with the failure that caused
        // it, leaving the surface on a stage name forever.
        exitStatus = reported.toExitStatus(consecutiveExitFailures)
            ?: exitStatus.afterUnreadablePass(consecutiveExitFailures)
        recordCompletionIfFinished()
        return exitStatus
    }

    /**
     * Stamp the finish the first time a pass reports one.
     *
     * Guarded on the stamp being absent rather than on the transition, because the transition is
     * only observable by whichever pass happens to catch it — and a relaunch mid-exit means no
     * pass in this process ever saw the earlier stages. Presence of the stamp is the fact; when it
     * was written is not.
     */
    private fun recordCompletionIfFinished() {
        if (exitStatus.stage != ExitStage.CLAIMED) return
        store.loadExitTimes()
            ?.takeIf { it.completedAt == null }
            ?.let { store.storeExitTimes(it.copy(completedAt = tuning.wallClockMillis())) }
    }

    /**
     * Null unless an exit has finished and the holder has not dismissed its receipt.
     *
     * The landed figure comes from the live status rather than from storage: bark keeps claimed
     * exit VTXOs, so the amount is still readable after any number of relaunches, and storing a
     * second copy would only create a way for the two to disagree about money.
     */
    override suspend fun pendingReceipt(): ExitReceipt? =
        store.loadExitTimes()?.takeIf { it.completedAt != null }?.let { times ->
            ExitReceipt(
                landedSats = exitStatus.landedSats,
                feeSats = exitStatus.claimFeeSats,
                tookMillis = (times.completedAt!! - times.startedAt).coerceAtLeast(0L),
            )
        }

    override suspend fun acknowledgeReceipt() = store.storeExitTimes(null)

    // --- On-chain send ---

    /** Confirmed only: an unconfirmed input cannot reliably fund a spend the holder is about to make. */
    override val spendableSats: Long get() = onchain?.confirmedSat ?: 0L

    override suspend fun quoteOnchainSend(address: String, sats: Long): OnchainSendQuote? =
        delegate.awaitValue<FfiOnchainFeeQuote> { onResult -> onchainSendFee(address, sats, onResult) }
            ?.let { OnchainSendQuote(feeSats = it.feeSat, totalSats = it.totalSat) }

    override suspend fun sendOnchain(address: String, sats: Long): String? =
        delegate.awaitValue<String> { onResult -> onchainSend(address, sats, onResult) }

    /**
     * Null when the crate cannot answer, which includes both "no server to ask" and a failed call.
     * Both are the same fact to a caller — the delta is not known — and neither may become a
     * number on screen.
     */
    override suspend fun exitDeltaBlocks(): Int? =
        delegate.awaitValue<Long> { onResult -> exitDeltaBlocks(onResult) }?.toInt()

    /**
     * Null while the wallet is still opening, which is the case that matters: the machine resumes
     * at construction and the open is a second of async work behind it. Reporting NOT_EXITING here
     * would tell the caller there is nothing to resume, for every exit, on every launch.
     */
    override suspend fun readExitStatus(): ExitStatus? =
        delegate.awaitValue<FfiExitStatus> { onResult -> exitStatus(onResult) }
            ?.toExitStatus(consecutiveExitFailures)
            ?.also { exitStatus = it }

    /** Re-read the exit without advancing it, for the status the machine resumes from. */
    private suspend fun refreshExitStatus() {
        val reported = delegate.awaitValue<FfiExitStatus> { onResult -> exitStatus(onResult) }
        exitStatus = reported.toExitStatus(consecutiveExitFailures) ?: exitStatus
    }

    init {
        // A wallet already on disk is opened without waiting for the user to ask (R3).
        if (walletExistsFlow.value) openExistingWallet()
        scope.launch { pollLoop() }
    }

    // --- Wallet lifecycle ---

    /**
     * Generate a mnemonic, store it, and open a new wallet — all in the background.
     *
     * [walletExists] flips as soon as the words are stored rather than when the open completes, so
     * onboarding can proceed to the backup screen (which needs only the words) while the slow open
     * continues behind it.
     */
    override fun createWallet() {
        if (walletOpen || openInFlight) return
        scope.launch {
            val existing = store.loadWords()
            val mnemonic = existing ?: delegate.awaitValue { onResult -> generateMnemonic(onResult) } ?: return@launch
            if (existing == null && !store.storeWords(mnemonic)) {
                // Refusing to open a wallet whose words were not saved: that is unrecoverable
                // funds, and it must not look like success.
                return@launch
            }
            words = mnemonic
            walletExistsFlow.value = true
            open(mnemonic)
        }
    }

    /** Re-open the wallet already on this device. Restoring from words is [restoreWallet] with words. */
    override fun restoreWallet() {
        openExistingWallet()
    }

    /**
     * Adopt the wallet belonging to [words]: store them, then open.
     *
     * The words are stored *before* the open rather than after it succeeds. That is deliberate: the
     * open reaches the network and can fail on a flaky one, and a user who has just typed a 12-word
     * phrase should not have to type it again. A wrong phrase leaves an unopenable wallet
     * rather than losing anything — the datadir is keyed to the seed and nothing has been spent.
     */
    override suspend fun restoreWallet(words: List<String>): Boolean {
        if (!walletOpen && store.storeWords(words)) {
            this.words = words
            open(words)
            if (walletOpen) walletExistsFlow.value = true
        }
        return walletOpen
    }

    override fun markBackedUp() {
        store.markBackedUp()
        backedUpFlow.value = true
    }

    private fun openExistingWallet() {
        val stored = store.loadWords() ?: return
        words = stored
        scope.launch { open(stored) }
    }

    private suspend fun open(mnemonic: List<String>) {
        if (walletOpen || openInFlight) return
        openInFlight = true
        val error = suspendCancellableCoroutine { continuation ->
            delegate.openWallet(
                config = config,
                words = mnemonic,
                onDone = { error -> continuation.resume(error) },
            )
        }
        openInFlight = false
        if (error != null) return
        walletOpen = true
        walletExistsFlow.value = true
        healthFlow.value = HealthState.READY
        pollTrigger.trySend(Unit)
    }

    // --- Reads ---

    private var pollCycle = 0

    private suspend fun pollLoop() {
        while (true) {
            if (walletOpen) {
                // The first cycle after an open always maintains: it is the one most likely to have
                // work waiting (a board that confirmed while the app was closed, say).
                val maintain = pollCycle % MAINTENANCE_EVERY_N_CYCLES == 0
                pollCycle++
                if (maintain) runMaintenance()
                pollOnce()
            }
            // Waits out the interval, but a triggered poll cuts the wait short.
            withTimeoutOrNull(tuning.pollInterval) { pollTrigger.receive() }
        }
    }

    /**
     * Maintenance plus an on-chain sync, the two things a poll cycle cannot do for itself.
     * Failures are left to [pollOnce] to classify — a maintenance pass that cannot reach the server
     * is the same reachability signal as a balance read that cannot.
     */
    private suspend fun runMaintenance() {
        delegate.awaitDone { onDone -> refresh(onDone) }
        delegate.awaitDone { onDone -> onchainSync(onDone) }
        // The network half of the expiry countdown, on the slow cadence. A failure keeps the last
        // known tip rather than clearing it: a slightly stale countdown beats no countdown.
        delegate.awaitValue<Long> { onResult -> chainTip(onResult) }?.let { tipHeight = it }
    }

    /** The injected wall clock in the unit the Ark server's round schedule speaks. */
    private fun nowEpochSeconds(): Long = tuning.wallClockMillis() / MILLIS_PER_SECOND

    private suspend fun pollOnce() = pollMutex.withLock {
        val balance = delegate.awaitValue { onResult -> balanceSats(onResult) }
        if (balance == null) {
            // A failed read is a reachability signal, not a zero balance: leaving the last known
            // number in place beats showing ₿0 to someone who has money.
            healthFlow.value = HealthState.OFFLINE
            // The round schedule is the exception to that rule, because it is the one cached value
            // that decays into a false statement rather than a stale one. This early return skips
            // the refresh below, so without clearing it here a countdown would keep ticking down
            // past zero on a cycle that read nothing at all. Note this branch is NOT the offline
            // case: a failed *local* balance read is a much stranger fault than an unreachable
            // server, and captaind being down never reaches it.
            nextRoundEpochSeconds = null
            return@withLock
        }
        // A balance that rose means something arrived, possibly the very invoice being cached.
        // Serving a settled invoice again would hand out a destination the payer's wallet
        // refuses, and the wallet cannot ask whether one specific receive settled — so the cache
        // is dropped rather than reasoned about. Re-minting costs one round trip.
        if (balance > balanceFlow.value) mintedInvoice = null
        balanceFlow.value = balance
        healthFlow.value = HealthState.READY

        delegate.awaitValue { onResult -> movements(onResult) }?.let { movements ->
            val ordered = ffiMovementsNewestFirst(movements)
            activityRows = ffiActivity(ordered, Clock.System.now())
            recentRows = ffiRecents(ordered)
        }
        if (depositAddressCache == null) {
            depositAddressCache = delegate.awaitValue { onResult -> depositAddress(onResult) }
        }
        if (receiveCodeCache == null) {
            // No code yet is the one piece of evidence available here that the wallet may have
            // opened without an Ark server — the open is deliberately tolerant of a server that
            // does not answer, and nothing reconnects on its own afterwards. Reconnecting first
            // is what stops a wallet opened during an outage from having no receive code (and no
            // way to send) for the whole life of the process. It is a no-op round trip when the
            // connection is already up, and mint is still attempted if it fails: the reconnect is
            // an attempt to help, never a gate on the call that actually matters.
            delegate.awaitServerDone { onDone -> reconnectArk(onDone) }
            // Needs a reachable Ark server, so it is retried on later cycles rather than once.
            receiveCodeCache = delegate.awaitValue { onResult -> mintAddress(onResult) }
                ?.let { address -> arkReceiveUri(address) }
        }
        // Only once the last known round time has passed, so the steady state costs no round trip.
        //
        // The result is assigned unconditionally, which is what makes an outage read as unknown.
        // Keeping the old instant on failure looks like the conservative choice and is the opposite
        // of one: this branch is only reached once that instant is already in the past, so a failed
        // refresh would pin the row to "any moment now" for as long as the server stays away —
        // stating that a round is imminent on the evidence of a call that just failed. The balance
        // read above cannot cover for this, because it is served from the local VTXO database and
        // succeeds happily while captaind is unreachable.
        if (nextRoundEpochSeconds.let { it == null || it <= nowEpochSeconds() }) {
            nextRoundEpochSeconds = delegate.awaitServerValue { onResult -> nextRoundTime(onResult) }
        }
        onchain = delegate.awaitValue { onResult -> onchainBalance(onResult) }
        // A local read, so it belongs on the fast cadence: it is what makes the VTXO count and the
        // expiry deadline visible at all, and it costs nothing to keep current.
        vtxos = delegate.awaitValue { onResult -> vtxoSummary(onResult) }
    }

    override fun advancedStats(): AdvancedStats = AdvancedStats(
        funds = FundsStats(
            // Null, not zero, until the first summary arrives: "0 VTXOs" alongside a real balance is
            // a contradiction, and a count is exactly the sort of unknown that must read as one.
            vtxoCount = vtxos?.count,
            // The VTXO row's own total, so count and amount come from one read and agree.
            vtxoTotalSats = vtxos?.totalSat ?: balanceFlow.value,
            soonestExpiry = blockExpiryLabel(
                expiryHeight = vtxos?.soonestExpiryHeight,
                tipHeight = tipHeight ?: 0L,
                secondsPerBlock = tuning.secondsPerBlock,
            ),
            // The engine records no refresh timestamp, so this one stays honestly unknown.
            lastRefresh = PLACEHOLDER,
            onChainReserveSats = onchain?.confirmedSat,
            depositAddress = depositAddress,
        ),
        network = NetworkStats(
            arkServerStatus = healthFlow.value.display.aspStatus,
            // Computed at read time, not at fetch time, so the countdown is current on every render
            // rather than as old as the last poll.
            nextRound = roundCountdownLabel(nextRoundEpochSeconds, tuning.wallClockMillis()),
            lightningBridge = PLACEHOLDER,
            chainTip = tipHeight?.takeIf { it > 0 },
        ),
    )

    // --- Writes ---

    override suspend fun refresh() {
        if (!walletOpen) return
        runMaintenance()
        pollOnce()
    }

    /**
     * The amount-carrying receive code: today's Ark URI, plus a Lightning destination when the
     * holder has named an amount and the server minted an invoice for it.
     *
     * Thin on purpose. Which code to serve is [receiveCodeFor]'s decision, in `commonMain` where
     * it can be tested; this method only supplies the three inputs and honours the seam's promise
     * never to fail. Every failure — no URI yet, an unreachable server, an invoice that would
     * break the URI — degrades to the code the wallet already had.
     */
    override suspend fun requestReceiveCode(sats: Long): String {
        val arkUri = receiveCodeCache
        // No URI or no amount means there is nothing to mint for, and no round trip worth making.
        if (arkUri.isNullOrEmpty() || sats <= 0L) return receiveCodeFor(arkUri, sats, null)
        return receiveCodeFor(arkUri, sats, invoiceFor(sats))
    }

    /** The invoice for [sats]: the one already minted for that amount, or a freshly minted one. */
    private suspend fun invoiceFor(sats: Long): String? = mintMutex.withLock {
        reusableInvoice(mintedInvoice, sats, nowEpochSeconds(), MINTED_INVOICE_REUSE_SECONDS)
            ?: delegate.awaitValue { onResult -> mintBolt11Invoice(sats, onResult) }
                ?.also { mintedInvoice = CachedInvoice(sats, it, nowEpochSeconds()) }
    }

    override suspend fun send(recipient: String, sats: Long): SendResult {
        if (!walletOpen || sats <= 0) return SendResult.Failure
        return sendMutex.withLock {
            val destination = recipient.trim()
            val summary = if (isArkDestination(destination)) {
                delegate.awaitValue { onResult -> sendArk(destination, sats, onResult) }
            } else {
                delegate.awaitValue { onResult -> sendBolt11(destination, sats, onResult) }
            }
            if (summary == null) {
                SendResult.Failure
            } else {
                pollTrigger.trySend(Unit)
                SendResult.Success
            }
        }
    }

    override suspend fun boardAll(): Boolean {
        if (!walletOpen) return false
        val summary = delegate.awaitValue { onResult -> boardAll(onResult) }
        pollTrigger.trySend(Unit)
        return summary != null
    }

    override suspend fun syncOnchain() {
        if (!walletOpen) return
        delegate.awaitDone { onDone -> onchainSync(onDone) }
        onchain = delegate.awaitValue { onResult -> onchainBalance(onResult) }
        // A local read, so it belongs on the fast cadence: it is what makes the VTXO count and the
        // expiry deadline visible at all, and it costs nothing to keep current.
        vtxos = delegate.awaitValue { onResult -> vtxoSummary(onResult) }
    }
}

/**
 * Runs a value-returning delegate call and suspends until its callback fires, returning null on
 * failure.
 *
 * The error message is dropped on purpose at this boundary: every caller above turns failure into
 * the same coarse seam outcome, and threading a message through would invite branching on wording.
 */
private suspend fun <T> LarkCoreDelegate.awaitValue(
    call: LarkCoreDelegate.((T?, String?) -> Unit) -> Unit,
): T? = suspendCancellableCoroutine { continuation ->
    call { value, _ -> continuation.resume(value) }
}

/**
 * [awaitValue] for a call that leaves the device, bounded by [SERVER_CALL_TIMEOUT].
 *
 * The whole poll body runs under one mutex, and the transport's own request timeout is ten minutes
 * — long enough that a server which accepts a connection and then goes quiet would hold that mutex
 * for the rest of the user's session, blocking every later cycle *and* the pull-to-refresh that
 * shares it, while the health dot keeps reporting the READY it was assigned earlier in the same
 * pass. A timeout is what keeps a wedged server a stale row instead of a frozen wallet.
 *
 * The abandoned call is not cancelled on the platform side — nothing here can reach into a detached
 * Swift task — so it runs on harmlessly and its answer is discarded. That is the trade: an ignored
 * reply costs nothing, a held mutex costs everything after it.
 */
private suspend fun <T> LarkCoreDelegate.awaitServerValue(
    call: LarkCoreDelegate.((T?, String?) -> Unit) -> Unit,
): T? = withTimeoutOrNull(SERVER_CALL_TIMEOUT) { awaitValue(call) }

/** [awaitDone] for a call that leaves the device; a timeout reads as the failure it is. */
private suspend fun LarkCoreDelegate.awaitServerDone(
    call: LarkCoreDelegate.((String?) -> Unit) -> Unit,
): Boolean = withTimeoutOrNull(SERVER_CALL_TIMEOUT) { awaitDone(call) } == true

/** Runs an effect-only delegate call and suspends until it reports. True when it worked. */
private suspend fun LarkCoreDelegate.awaitDone(
    call: LarkCoreDelegate.((String?) -> Unit) -> Unit,
): Boolean = suspendCancellableCoroutine { continuation ->
    call { error -> continuation.resume(error == null) }
}
