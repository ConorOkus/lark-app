// TooManyFunctions: the core is deliberately decomposed into one small function per gateway
// concern (poll cycle, health classification, wallet lifecycle, recovery probing).
@file:Suppress("TooManyFunctions")
@file:OptIn(ExperimentalTime::class) // kotlin.time Instant/Clock: stdlib-experimental, stable enough for M1

package xyz.lark.app.core.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.model.AdvancedStats
import xyz.lark.app.core.model.ChannelsSnapshot
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.FiatRate
import xyz.lark.app.core.model.FundsStats
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.NetworkStats
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val DEFAULT_POLL_INTERVAL_SECONDS = 15
private const val BACKOFF_START_SECONDS = 2
private const val BACKOFF_STEPS = 5
private const val DEFAULT_LONG_POLL_RETRY_SECONDS = 5

/** Consecutive poll cycles containing an HTTP-5xx before the streak reads as OFFLINE (R6). */
private const val SERVER_ERROR_STREAK_LIMIT = 3
private const val HTTP_SERVER_ERROR_MIN = 500
private const val HTTP_NOT_FOUND = 404

/**
 * STALE when the soonest VTXO expiry is within `vtxo_expiry_delta / 2` blocks of the tip:
 * half the refresh window gone without a refresh is a reasonable "open me more often" line,
 * pending a product-tuned threshold (R6 leaves the derivation to us).
 */
private const val STALE_THRESHOLD_DIVISOR = 2

/** Rejected `addresses/next` mints re-try on later cycles, but only this many times. */
private const val MAX_REJECTED_MINT_ATTEMPTS = 3

/** barkd has no fiat/price endpoint (R8): the demo rate stands in, clearly marked. */
private const val DEMO_SATS_PER_CENT = 10L

private val DEFAULT_POLL_INTERVAL = DEFAULT_POLL_INTERVAL_SECONDS.seconds
private val DEFAULT_OFFLINE_BACKOFF = List(BACKOFF_STEPS) { BACKOFF_START_SECONDS.seconds * (1 shl it) }
private val DEFAULT_LONG_POLL_RETRY = DEFAULT_LONG_POLL_RETRY_SECONDS.seconds

/** Poll-loop tuning and wall clock, injectable so virtual-time tests can pin both (KTD-7). */
data class GatewayTuning(
    val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    /** Waits between recovery probes while OFFLINE; the schedule caps at its last step. */
    val offlineBackoff: List<Duration> = DEFAULT_OFFLINE_BACKOFF,
    /** Pause before re-issuing `notifications/wait` after a failed or idle long poll. */
    val longPollRetryDelay: Duration = DEFAULT_LONG_POLL_RETRY,
    /** Wall clock behind the relative display timestamps. */
    val now: () -> Instant = { Clock.System.now() },
) {
    init {
        require(offlineBackoff.isNotEmpty()) { "offlineBackoff needs at least one step" }
    }
}

/**
 * Where the fork's wallet create points the daemon ([BarkdCapabilities.usesForkCreateRequest]
 * requires both): the Ark server (captaind) and the esplora chain source. Never consulted on
 * the stock surface, whose create carries only the network.
 */
data class ForkWalletConfig(
    val arkServerUrl: String = "",
    val esploraUrl: String = "",
)

/** Why the gateway core is reporting [HealthState.OFFLINE]; null while not offline. */
enum class GatewayOfflineReason {
    /** Connect failure or timeout on a state-fetch request. */
    UNREACHABLE,

    /** The gateway answered 401/403: auth is required or our token was rejected (plan R4). */
    AUTH_REQUIRED,

    /** A persistent HTTP-5xx streak on the data endpoints. */
    SERVER_ERROR,

    /** barkd is reachable but reports it cannot reach its Ark server (`connected: false`). */
    ARK_DISCONNECTED,

    /**
     * The gateway failed the R16 identity check — wrong network, or (on the channel fork)
     * an Ark server without channel support: hard, non-recoverable.
     */
    NETWORK_MISMATCH,
}

/**
 * The real core (plan U3): [LarkCore] backed by a barkd REST gateway through [BarkdApi].
 *
 * One poll loop fetches balance/VTXOs/history/tip/connected every [GatewayTuning.pollInterval]
 * and diffs them into the StateFlows; a second loop long-polls `notifications/wait` — movement
 * events trigger an immediate poll, `channel-lagging` a full resync, and its failures NEVER
 * drive OFFLINE (R6). While OFFLINE the poll loop backs off along [GatewayTuning.offlineBackoff]
 * (capped at the last step) with `GET /ping` as the cheap recovery probe. First contact
 * verifies the gateway network against [expectedNetwork]; a mismatch is terminal (R16).
 *
 * The fork surface degrades honestly at the [BarkdCapabilities] seams (plan U3), never by
 * pretending: no notification loop (poll cadence alone), backup words immediately unavailable,
 * the receive code minted client-side from ONE `addresses/next` address per session (charset-
 * checked before it may enter a URI), create speaking the fork request shape with wallet
 * existence learned from create itself (the fork has no `GET /wallet` probe), and the R16
 * identity check additionally requiring ark-info `supports_channels`.
 *
 * Deliberate M1 seams and stand-ins, all pinned by tests:
 * - `backedUp` is a local flag exactly like the fake — barkd has no backed-up concept.
 * - `restoreWallet()` is create-equivalent (create-without-mnemonic, R5).
 * - `backupWords` fetches the mnemonic on first access after the wallet exists; a 404 means
 *   words-unavailable (empty list, never fake words) and the body is never logged (R15).
 * - `fiatRate` is the demo constant (R8); Advanced fields barkd doesn't expose are em-dashes (R9).
 * - History-only changes surface on the next flow emission or property read (R7 staleness).
 *
 * Timing (`delay`/timeouts) resolves against [scope]'s dispatcher, so tests drive everything
 * with kotlinx-coroutines-test virtual time. Mutable internals are only touched from [scope]
 * plus the caller's suspend functions; both are the main/test dispatcher in practice.
 */
class GatewayLarkCore(
    private val api: BarkdApi,
    private val scope: CoroutineScope,
    private val expectedNetwork: String,
    /** User-facing network name (R12), decoupled from [expectedNetwork] on the wire. */
    networkLabel: String,
    private val forkWallet: ForkWalletConfig = ForkWalletConfig(),
    private val tuning: GatewayTuning = GatewayTuning(),
) : LarkCore {

    private val walletExistsFlow = MutableStateFlow(false)
    private val balanceFlow = MutableStateFlow(0L)
    private val healthFlow = MutableStateFlow(HealthState.READY)
    private val backedUpFlow = MutableStateFlow(false)
    private val offlineReasonFlow = MutableStateFlow<GatewayOfflineReason?>(null)
    private val channelsFlow = MutableStateFlow<ChannelsSnapshot?>(null)

    /** Serializes poll cycles (loop, refresh, notification-triggered) against each other. */
    private val pollMutex = Mutex()

    /** Serializes [send]'s check-then-post so racing sends cannot jointly overdraw. */
    private val sendMutex = Mutex()

    /** Conflated "poll now" signal; senders never block, repeats collapse into one cycle. */
    private val pollTrigger = Channel<Unit>(Channel.CONFLATED)

    // Gateway-fed state behind the seam's plain properties; written only by poll cycles.
    private var vtxos: List<WalletVtxoInfo> = emptyList()
    private var activityRows: List<Transaction> = emptyList()
    private var recentRows: List<Contact> = emptyList()
    private var mnemonicWords: List<String> = emptyList()
    private var receiveCodeCache: String? = null
    private var rejectedMintAttempts = 0
    private var depositAddressCache: String? = null
    private var tipHeight = 0L
    private var cachedArkInfo: ArkInfo? = null
    private var networkVerified = false
    private var serverErrorStreak = 0
    private var refreshInFlight = false
    private var mnemonicFetchStarted = false

    /** R16 terminal state: wrong network. Set once, never cleared; both loops halt on it. */
    private var hardOffline = false

    override val walletExists: StateFlow<Boolean> = walletExistsFlow.asStateFlow()
    override val balanceSats: StateFlow<Long> = balanceFlow.asStateFlow()
    override val health: StateFlow<HealthState> = healthFlow.asStateFlow()
    override val backedUp: StateFlow<Boolean> = backedUpFlow.asStateFlow()

    /** Null until the first successful channel fetch; forever null on a channel-less surface (U4). */
    override val channels: StateFlow<ChannelsSnapshot?> = channelsFlow.asStateFlow()

    /** Not part of the seam: the status screen may surface why the gateway is offline. */
    val offlineReason: StateFlow<GatewayOfflineReason?> = offlineReasonFlow.asStateFlow()

    override val fiatRate: FiatRate = FiatRate(satsPerCent = DEMO_SATS_PER_CENT)
    override val activity: List<Transaction> get() = activityRows
    override val recents: List<Contact> get() = recentRows
    override val receiveCode: String get() = receiveCodeCache.orEmpty()
    override val depositAddress: String get() = depositAddressCache.orEmpty()
    override val networkLabel: String = networkLabel

    override val backupWords: List<String>
        get() {
            requestBackupWordsIfNeeded()
            return mnemonicWords
        }

    init {
        scope.launch { pollLoop() }
        // No notifications endpoint means no long-poll loop at all: poll cadence alone (U3).
        if (api.capabilities.hasNotifications) scope.launch { notificationLoop() }
    }

    // --- Wallet lifecycle (R5) ---

    override fun createWallet() {
        scope.launch { createOrAdoptWallet() }
    }

    /** Create-equivalent this milestone (R5): restore with real words needs a word-entry affordance. */
    override fun restoreWallet() {
        scope.launch { createOrAdoptWallet() }
    }

    /** Local acknowledgement flag, like the fake — barkd has no backed-up concept. */
    override fun markBackedUp() {
        backedUpFlow.value = true
    }

    private suspend fun createOrAdoptWallet() {
        if (hardOffline || walletExistsFlow.value) return
        if (api.capabilities.usesForkCreateRequest) {
            createOrAdoptForkWallet()
            return
        }
        if (probeFindsWallet()) {
            adoptWallet() // barkd is single-wallet: an existing wallet is ours to adopt, not an error
        } else {
            when (api.createWallet(CreateWalletRequest(network = expectedNetwork))) {
                is BarkdResult.Ok -> adoptWallet()
                // A raced create's wallet-already-exists error is absorbed as adopt-existing.
                is BarkdResult.HttpError -> if (probeFindsWallet()) adoptWallet()
                is BarkdResult.Unreachable -> Unit // the poll loop classifies reachability
            }
        }
    }

    /**
     * The fork has no wallet-existence probe, so create doubles as the probe on its
     * single-wallet daemon: a rejected create while a wallet-scoped read answers means the
     * wallet already exists and is ours to adopt — the same classification the stock path
     * reaches by re-probing `GET /wallet` after a raced create.
     */
    private suspend fun createOrAdoptForkWallet() {
        when (api.createWallet(forkCreateRequest())) {
            is BarkdResult.Ok -> adoptWallet()
            is BarkdResult.HttpError -> if (forkWalletAnswers()) adoptWallet()
            is BarkdResult.Unreachable -> Unit // the poll loop classifies reachability
        }
    }

    private fun forkCreateRequest() = ForkCreateWalletRequest(
        network = expectedNetwork,
        arkServer = forkWallet.arkServerUrl,
        chainSource = ChainSourceConfig(esplora = EsploraChainSource(url = forkWallet.esploraUrl)),
    )

    /** The fork's existence probe stand-in: a wallet-scoped read only answers once a wallet exists. */
    private suspend fun forkWalletAnswers(): Boolean = api.balance() is BarkdResult.Ok

    private suspend fun probeFindsWallet(): Boolean {
        val probe = api.walletExists()
        return probe is BarkdResult.Ok && probe.value.fingerprint != null
    }

    private fun adoptWallet() {
        walletExistsFlow.value = true
        triggerPoll()
    }

    // --- Backup words (R5/R15) ---

    private fun requestBackupWordsIfNeeded() {
        // No mnemonic endpoint = immediately words-unavailable: same empty state as a 404 (U3).
        if (!api.capabilities.hasMnemonic) return
        if (mnemonicFetchStarted || hardOffline || !walletExistsFlow.value) return
        mnemonicFetchStarted = true
        scope.launch {
            when (val result = api.mnemonic()) {
                is BarkdResult.Ok -> mnemonicWords = splitMnemonicWords(result.value.mnemonic)
                // 404 = --expose-mnemonic off: words-unavailable, stays empty — never fake words.
                // Other statuses may be transient, so a later access retries. Body never logged (R15).
                is BarkdResult.HttpError -> if (result.status != HTTP_NOT_FOUND) mnemonicFetchStarted = false
                is BarkdResult.Unreachable -> mnemonicFetchStarted = false
            }
        }
    }

    // --- Send ---

    override suspend fun send(recipient: String, sats: Long): SendResult = sendMutex.withLock {
        val destination = resolveSendDestination(recipient)
        val payable = !hardOffline &&
            healthFlow.value != HealthState.OFFLINE &&
            sats > 0 &&
            sats <= balanceFlow.value
        if (destination == null || !payable) SendResult.Failure else dispatchSend(destination, sats)
    }

    private suspend fun dispatchSend(destination: String, sats: Long): SendResult =
        when (api.send(SendRequest(destination = destination, amountSat = sats))) {
            is BarkdResult.Ok -> {
                triggerPoll() // the debit arrives via the poll; the balance is never mutated locally
                SendResult.Success
            }
            else -> SendResult.Failure
        }

    // --- Refresh ---

    override suspend fun refresh() {
        if (hardOffline) return
        refreshInFlight = true
        if (healthFlow.value != HealthState.OFFLINE) healthFlow.value = HealthState.TIDYING
        // Round completion isn't otherwise observable in the 0.4.0 surface; TIDYING presents
        // as Ready anyway (design rule), so it holds for the call and the follow-up cycle settles it.
        try {
            // BarkdApi rethrows CancellationException, so a cancelled refresh() must still
            // reset the flag or health wedges at TIDYING (markReachable recomputes from it).
            api.refreshAll()
        } finally {
            refreshInFlight = false
        }
        runPollCycle() // skipped when cancelled above: the cancellation propagates out of the finally
    }

    // --- Advanced stats (R9) ---

    override fun advancedStats(): AdvancedStats = AdvancedStats(
        funds = FundsStats(
            vtxoCount = vtxos.size,
            vtxoTotalSats = vtxos.sumOf { it.amountSat },
            soonestExpiry = soonestExpiryLabel(vtxos, tipHeight),
            lastRefresh = PLACEHOLDER, // barkd 0.4.0 exposes no last-refresh timestamp
            onChainReserveSats = 0L, // Long-typed by the seam; not exposed — zero, never a fake number
            depositAddress = depositAddressCache?.takeIf { it.isNotEmpty() } ?: PLACEHOLDER,
        ),
        network = NetworkStats(
            arkServerStatus = healthFlow.value.display.aspStatus,
            nextRound = PLACEHOLDER, // not directly exposed by barkd 0.4.0
            lightningBridge = PLACEHOLDER, // not exposed
            chainTip = tipHeight,
        ),
    )

    // --- Poll loop (R6/R7) ---

    private suspend fun pollLoop() {
        var backoffIndex = 0
        while (!hardOffline) {
            val healthy = runPollCycle()
            if (healthy) {
                backoffIndex = 0
                awaitNextCycle()
            } else if (!hardOffline) {
                backoffIndex = backOffUntilPingAnswers(backoffIndex)
            }
        }
    }

    /** Sleeps the poll interval or wakes early on a [triggerPoll] signal. */
    private suspend fun awaitNextCycle() {
        withTimeoutOrNull(tuning.pollInterval) { pollTrigger.receive() }
    }

    private fun triggerPoll() {
        pollTrigger.trySend(Unit)
    }

    /**
     * OFFLINE recovery: sleeps the growing backoff (capped at the schedule's last step) and
     * probes the unauthenticated `GET /ping` until the gateway answers; the caller then
     * re-runs a full cycle, whose success is what actually restores health (R6).
     */
    private suspend fun backOffUntilPingAnswers(startIndex: Int): Int {
        val schedule = tuning.offlineBackoff
        var index = startIndex
        while (true) {
            delay(schedule[index.coerceAtMost(schedule.lastIndex)])
            index++
            if (api.ping() is BarkdResult.Ok) return index
        }
    }

    /** One full state fetch; returns false when the cycle classified the gateway OFFLINE. */
    private suspend fun runPollCycle(): Boolean = pollMutex.withLock {
        val cycle = CycleOutcome()
        if (!walletExistsFlow.value) probeWallet(cycle)
        if (!hardOffline && walletExistsFlow.value && !networkVerified) verifyNetwork(cycle)
        if (!hardOffline && walletExistsFlow.value && networkVerified) fetchWalletState(cycle)
        if (hardOffline) false else applyCycleHealth(cycle)
    }

    private suspend fun probeWallet(cycle: CycleOutcome) {
        // The fork surface has no `GET /wallet`: existence is only ever learned from create, so
        // the walletless cycle pings instead — a down gateway still classifies into health
        // rather than idling at READY while onboarding.
        if (api.capabilities.usesForkCreateRequest) {
            cycle.note(api.ping())
            return
        }
        cycle.note(api.walletExists())?.let { walletExistsFlow.value = it.fingerprint != null }
    }

    /** R16: first successful contact must confirm the gateway identity; a mismatch is terminal. */
    private suspend fun verifyNetwork(cycle: CycleOutcome) {
        val info = cycle.note(api.arkInfo()) ?: return
        if (info.network == expectedNetwork && channelSupportMatches(info)) {
            cachedArkInfo = info
            networkVerified = true
        } else {
            hardOffline = true
            goOffline(GatewayOfflineReason.NETWORK_MISMATCH)
        }
    }

    /** The channel surface must land on an Ark server that opens channels (R16 identity, U3). */
    private fun channelSupportMatches(info: ArkInfo): Boolean =
        !api.capabilities.hasChannels || info.supportsChannels == true

    private suspend fun fetchWalletState(cycle: CycleOutcome) {
        cycle.note(api.balance())?.let { balanceFlow.value = it.spendableSat } // the one-balance rule
        cycle.note(api.vtxos())?.let { vtxos = it }
        cycle.note(api.history())?.let { applyHistory(it) }
        cycle.note(api.tip())?.let { tipHeight = it.tipHeight }
        cycle.note(api.connected())?.let { cycle.disconnected = !it.connected }
        fetchReceiveTargetsIfNeeded(cycle)
        fetchChannelsState() // after the tip fetch: expiry labels count down against this cycle's tip
    }

    /**
     * Channel data is auxiliary, not liveness (plan U4): fetch failures deliberately bypass
     * [CycleOutcome.note] and leave the snapshot at its previous value — null while never
     * fetched, the last good snapshot afterwards. A successful fetch of zero channels emits
     * a non-null empty snapshot: polled-and-zero, distinct from never-fetched.
     */
    private suspend fun fetchChannelsState() {
        if (!api.capabilities.hasChannels) return
        val channelList = (api.channels() as? BarkdResult.Ok)?.value ?: return
        channelsFlow.value = channelsSnapshot(channelList, tipHeight)
    }

    private fun applyHistory(movements: List<Movement>) {
        val ordered = movementsNewestFirst(movements)
        activityRows = activityFromMovements(ordered, tuning.now())
        recentRows = recentsFromMovements(ordered)
    }

    /** The receive code and deposit address are stable per wallet: fetched once per session. */
    private suspend fun fetchReceiveTargetsIfNeeded(cycle: CycleOutcome) {
        if (!api.capabilities.hasBip321) {
            // No bip321 endpoint also means no onchain URI source: the deposit address
            // honestly stays absent (em-dash on Advanced), never a fabricated string.
            mintReceiveAddressIfNeeded(cycle)
            return
        }
        if (receiveCodeCache == null) {
            cycle.note(api.bip321(Bip321UriRequest()))?.let { receiveCodeCache = it.bip321 }
        }
        if (depositAddressCache == null) {
            cycle.note(api.bip321(Bip321UriRequest(onchain = true)))?.let {
                depositAddressCache = it.onchain.orEmpty()
            }
        }
    }

    /**
     * Fork receive (U3): mints ONE address per session via `addresses/next` and builds the
     * `bitcoin:?ark=` URI client-side ([arkReceiveUri]). An address failing the charset check
     * is never embedded — a fresh address is minted on a later cycle instead (each call
     * derives anew, so a transiently bogus response must not wedge receiving), but only
     * [MAX_REJECTED_MINT_ATTEMPTS] times before the empty no-code state caches for the
     * session: a persistently bogus daemon must not spin address derivation forever. Fetch
     * failures leave the cache null and retry next cycle, exactly like the bip321 path.
     */
    private suspend fun mintReceiveAddressIfNeeded(cycle: CycleOutcome) {
        if (receiveCodeCache != null) return
        cycle.note(api.nextAddress())?.let { minted ->
            val uri = arkReceiveUri(minted.address)
            receiveCodeCache = when {
                uri != null -> uri
                ++rejectedMintAttempts >= MAX_REJECTED_MINT_ATTEMPTS -> ""
                else -> null
            }
        }
    }

    // --- Health classification (R6, plan health-mapping table) ---

    private fun applyCycleHealth(cycle: CycleOutcome): Boolean {
        serverErrorStreak = if (cycle.serverError) serverErrorStreak + 1 else 0
        return when {
            cycle.unreachable -> goOffline(GatewayOfflineReason.UNREACHABLE)
            cycle.authRequired -> goOffline(GatewayOfflineReason.AUTH_REQUIRED)
            cycle.disconnected -> goOffline(GatewayOfflineReason.ARK_DISCONNECTED)
            serverErrorStreak >= SERVER_ERROR_STREAK_LIMIT -> goOffline(GatewayOfflineReason.SERVER_ERROR)
            else -> {
                markReachable() // a sub-streak 5xx is tolerated as transient: health stays computed
                true
            }
        }
    }

    private fun goOffline(reason: GatewayOfflineReason): Boolean {
        offlineReasonFlow.value = reason
        healthFlow.value = HealthState.OFFLINE
        return false
    }

    private fun markReachable() {
        offlineReasonFlow.value = null
        healthFlow.value = when {
            refreshInFlight -> HealthState.TIDYING
            vtxosNearExpiry() -> HealthState.STALE
            else -> HealthState.READY
        }
    }

    private fun vtxosNearExpiry(): Boolean {
        val expiryDelta = cachedArkInfo?.vtxoExpiryDelta ?: return false
        val minExpiry = vtxos.minOfOrNull { it.expiryHeight }
        return minExpiry != null &&
            tipHeight > 0 &&
            minExpiry - tipHeight <= expiryDelta / STALE_THRESHOLD_DIVISOR
    }

    // --- Long-poll notifications (R6/R7) ---

    private suspend fun notificationLoop() {
        var since: String? = null
        while (!hardOffline) {
            since = when (val result = api.waitNotifications(since)) {
                is BarkdResult.Ok -> processNotifications(result.value, since)
                else -> {
                    // Timeout/failure here NEVER drives OFFLINE (R6): just re-issue the wait.
                    delay(tuning.longPollRetryDelay)
                    since
                }
            }
        }
    }

    /**
     * Movement events trigger an immediate poll; `channel-lagging` additionally resets `since`
     * so the next wait re-reads the whole buffer — the poll cycle itself is already a full
     * resync (every fetch is a complete snapshot). Returns the next `since` watermark.
     */
    private suspend fun processNotifications(response: WaitNotificationResponse, previous: String?): String? {
        if (response.notifications.isEmpty()) {
            delay(tuning.longPollRetryDelay) // idle answer; pace the re-issue so a fast server can't hot-loop us
        } else {
            triggerPoll()
        }
        val lagging = response.notifications.any { it is WalletNotification.ChannelLagging }
        return if (lagging) null else response.lastPushedAt ?: previous
    }

    /** Failure classification for one poll cycle's fetches (plan health-mapping table). */
    private class CycleOutcome {
        var unreachable = false
        var authRequired = false
        var serverError = false
        var disconnected = false

        /** Records a failure for health classification; returns the value when Ok. */
        fun <T> note(result: BarkdResult<T>): T? = when (result) {
            is BarkdResult.Ok -> result.value
            is BarkdResult.HttpError -> {
                if (result.isAuthRequired) {
                    authRequired = true
                } else if (result.status >= HTTP_SERVER_ERROR_MIN) {
                    serverError = true
                }
                null
            }
            is BarkdResult.Unreachable -> {
                unreachable = true
                null
            }
        }
    }
}
