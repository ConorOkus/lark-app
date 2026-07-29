package xyz.lark.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.lark.app.core.model.AdvancedStats
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.FiatRate
import xyz.lark.app.core.model.FundsStats
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.NetworkStats
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// Prototype constants (docs/design/lark-wallet/LARK Wallet.dc.html, Component script).
private const val WORK_DELAY_MILLIS = 1_500L
private const val DEMO_BALANCE_SATS = 412_350L
private const val DEMO_SATS_PER_CENT = 10L // 1 sat = $0.001
private const val VTXO_COUNT = 4
private const val ON_CHAIN_RESERVE_SATS = 6_200L
private const val CHAIN_TIP = 916_214L
private const val RECEIVE_CODE = "ark1qf7…lark.money"
private const val DEPOSIT_ADDRESS = "bc1qf7k29admq0wrx4lp8vun3zt6y"
private const val NETWORK_LABEL = "mutinynet" // KTD-11: supersedes the design footer's "signet"

private val DEMO_WORDS = listOf(
    "tide", "margin", "ocean", "lens", "quiet", "ember",
    "ladder", "forest", "plum", "signal", "harbor", "wren",
)

private val DEMO_ACTIVITY = listOf(
    Transaction(who = "Maya", whenLabel = "2 hours ago", sats = -14_200L, initial = "M"),
    Transaction(who = "Ferry Building Coffee", whenLabel = "Yesterday", sats = -520L, initial = "F"),
    Transaction(who = "Jack", whenLabel = "Yesterday", sats = 250_000L, initial = "J"),
    Transaction(who = "Added money", whenLabel = "Mon", sats = 180_000L, initial = "A"),
    Transaction(who = "Dani", whenLabel = "Last week", sats = -9_800L, initial = "D"),
)

private val DEMO_RECENTS = listOf(
    Contact(who = "Jack", handle = "jack@lark.money", initial = "J"),
    Contact(who = "Maya", handle = "maya@zaprite.com", initial = "M"),
    Contact(who = "Ferry Building Coffee", handle = "ferry@sq.link", initial = "F"),
)

/**
 * The demo wallet engine: [LarkCore] backed by the design prototype's constants and timing,
 * plus [DemoControls] for forcing health states.
 *
 * [workDelay] is the prototype's 1.5s spinner delay, injected so tests drive it with
 * kotlinx-coroutines-test virtual time (KTD-9) — `delay` resolves against the caller's
 * dispatcher, which under `runTest` is the virtual-time scheduler.
 */
class FakeLarkCore(
    startWithWallet: Boolean = false,
    private val workDelay: Duration = WORK_DELAY_MILLIS.milliseconds,
) : LarkCore, DemoControls {

    private val walletExistsFlow = MutableStateFlow(startWithWallet)
    private val balanceFlow = MutableStateFlow(DEMO_BALANCE_SATS)
    private val healthFlow = MutableStateFlow(HealthState.READY)
    private val backedUpFlow = MutableStateFlow(false)

    /** Serializes [send]'s read-check-debit so racing sends cannot jointly overdraw. */
    private val sendMutex = Mutex()

    override val walletExists: StateFlow<Boolean> = walletExistsFlow.asStateFlow()
    override val balanceSats: StateFlow<Long> = balanceFlow.asStateFlow()
    override val health: StateFlow<HealthState> = healthFlow.asStateFlow()
    override val backedUp: StateFlow<Boolean> = backedUpFlow.asStateFlow()

    override val fiatRate: FiatRate = FiatRate(satsPerCent = DEMO_SATS_PER_CENT)
    override val activity: List<Transaction> = DEMO_ACTIVITY
    override val recents: List<Contact> = DEMO_RECENTS
    override val backupWords: List<String> = DEMO_WORDS
    override val receiveCode: String = RECEIVE_CODE
    override val depositAddress: String = DEPOSIT_ADDRESS
    override val networkLabel: String = NETWORK_LABEL

    override fun createWallet() {
        walletExistsFlow.value = true
    }

    override fun restoreWallet() {
        walletExistsFlow.value = true
    }

    override fun markBackedUp() {
        backedUpFlow.value = true
    }

    override fun advancedStats(): AdvancedStats {
        val stale = healthFlow.value == HealthState.STALE
        val offline = healthFlow.value == HealthState.OFFLINE
        return AdvancedStats(
            funds = FundsStats(
                vtxoCount = VTXO_COUNT,
                vtxoTotalSats = balanceFlow.value,
                soonestExpiry = if (stale) "block 916,980 · in 5 days" else "block 918,402 · in 27 days",
                lastRefresh = if (stale) "38 days ago" else "4 hours ago",
                onChainReserveSats = ON_CHAIN_RESERVE_SATS,
                depositAddress = DEPOSIT_ADDRESS,
            ),
            network = NetworkStats(
                arkServerStatus = healthFlow.value.display.aspStatus,
                nextRound = if (offline) "—" else "in 41 seconds",
                lightningBridge = "Open · 2 peers",
                chainTip = CHAIN_TIP,
            ),
        )
    }

    override suspend fun refresh() {
        delay(workDelay)
        healthFlow.value = HealthState.READY
    }

    override suspend fun send(recipient: String, sats: Long): SendResult {
        delay(workDelay)
        return sendMutex.withLock {
            val payable = healthFlow.value != HealthState.OFFLINE &&
                sats > 0 &&
                sats <= balanceFlow.value
            if (payable) {
                balanceFlow.value -= sats
                SendResult.Success
            } else {
                SendResult.Failure
            }
        }
    }

    override fun forceHealth(health: HealthState) {
        healthFlow.value = health
    }
}
