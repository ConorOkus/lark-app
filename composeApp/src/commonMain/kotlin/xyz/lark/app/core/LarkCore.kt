package xyz.lark.app.core

import kotlinx.coroutines.flow.StateFlow
import xyz.lark.app.core.model.AdvancedStats
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.FiatRate
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import xyz.lark.app.core.model.Transaction

/**
 * The single seam between the app and the wallet engine (KTD-3).
 *
 * UI and state machines talk only to this interface; [FakeLarkCore] is the sole implementation
 * this milestone, and the hosted-gateway thin client (M1) / in-process Rust core (M2) must be
 * able to slot in without touching anything above it. Demo-only affordances (forcing a health
 * state) are NOT part of this contract — they live on [DemoControls].
 */
interface LarkCore {

    /** Whether a wallet exists on this device; drives welcome-vs-home at launch. */
    val walletExists: StateFlow<Boolean>

    /** Spendable balance in whole sats. Money is integer sats end-to-end (KTD-6). */
    val balanceSats: StateFlow<Long>

    /** The rate every fiat presentation uses; never hardcoded in UI (KTD-6). */
    val fiatRate: FiatRate

    /** Current wallet health; drives the home indicator, banner, and status screen. */
    val health: StateFlow<HealthState>

    /** Whether the user has confirmed writing down the backup words. */
    val backedUp: StateFlow<Boolean>

    /** Payment history, newest first. */
    val activity: List<Transaction>

    /** Recent payees for the send flow. */
    val recents: List<Contact>

    /** The 12 backup words. */
    val backupWords: List<String>

    /** The one "Get paid" code (works from any bitcoin or Lightning wallet). */
    val receiveCode: String

    /** On-chain deposit address; surfaced in Advanced only. */
    val depositAddress: String

    /** User-visible network label (mutinynet, KTD-11). */
    val networkLabel: String

    fun createWallet()

    fun restoreWallet()

    fun markBackedUp()

    /** Snapshot of the Advanced screen's numbers; varies with [health]. */
    fun advancedStats(): AdvancedStats

    /** Puts funds back on solid ground; takes the engine's working delay, then health is ready. */
    suspend fun refresh()

    /**
     * Sends [sats] to [recipient]. The working delay lives here, inside the core —
     * callers show the sending spinner and simply await the result. Fails when offline,
     * when [sats] is not positive, or when [sats] exceeds the balance; the balance is
     * untouched on failure. Concurrent sends are serialized at this boundary.
     */
    suspend fun send(recipient: String, sats: Long): SendResult
}
