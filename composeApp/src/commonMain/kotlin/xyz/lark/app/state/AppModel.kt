package xyz.lark.app.state

import xyz.lark.app.core.model.AdvancedStats
import xyz.lark.app.core.model.BannerCopy
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.HealthState

/** Which unit leads everywhere money is shown (KTD-6). */
enum class Denomination { BTC, FIAT }

/** What the amount keypad is for; changes header, primary label, and validation. */
enum class KeypadMode { SEND, RECEIVE }

/**
 * Home balance block. When [visible] is false, [primary] and [secondary] are already masked
 * (`••••`) — screens render the strings as-is.
 */
data class BalanceModel(
    val visible: Boolean,
    val hideLabel: String,
    val primary: String,
    val secondary: String,
    val unitLabel: String,
    /** Money on its way, or null when nothing is. See [ArrivingModel]. */
    val arriving: ArrivingModel?,
)

/**
 * Money that has arrived but cannot be spent yet.
 *
 * Kept separate from the balance rather than folded into it: a headline figure that included
 * unspendable funds would leave Pay live over money that cannot pay, which is a worse lie than a
 * lower number. Shown on Home so the wait survives the user closing the app, and reused verbatim
 * on the deposit screen so the two surfaces cannot drift into telling different stories.
 *
 * [note] says what the user can and cannot do, never where the money is or what is moving —
 * the whole point is that the user never learns there are two places money can live.
 */
data class ArrivingModel(
    val amount: String,
    val note: String,
)

/**
 * Health indicator + banner + status-screen copy for the current health state.
 * Per AE5 the indicator [word] is suppressed ([wordVisible] = false) whenever a [banner]
 * is present — the banner carries the message.
 */
data class HealthModel(
    val word: String,
    val wordVisible: Boolean,
    val dotColorHex: String,
    val banner: BannerCopy?,
    val offline: Boolean,
    val statusTitle: String,
    val statusBody: String,
    val actionLabel: String?,
    val aspStatus: String,
)

/** Everything the amount keypad (and the review screen's amount) renders. */
data class KeypadModel(
    val digits: String,
    val mode: KeypadMode,
    val amountDisplay: String,
    val amountSecondary: String,
    val header: String,
    val availability: String,
    val overBalance: Boolean,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
)

/** The send flow's recipient context. */
data class SendModel(
    val recipientName: String,
    val recipientHandle: String,
    val inputDisplay: String,
    /** True only when the input is recognized as something payable — drives Continue. */
    val inputResolved: Boolean,
    /** The line under the input card: what was recognized, or that nothing was. */
    val inputSummary: String = "",
    /** The destination carries its own amount, so the keypad is skipped and cannot override it. */
    val fixedAmount: Boolean = false,
)

/** One pre-formatted activity row; [amount] is signed in the current denomination. */
data class ActivityRowModel(
    val who: String,
    val whenLabel: String,
    val initial: String,
    val amount: String,
    val incoming: Boolean,
    /** Accepted but not yet complete; the row says so rather than reading as settled. */
    val pending: Boolean,
)

/** The selected transaction, shaped for the payment-detail screen. */
data class TxDetailModel(
    val verb: String,
    val amount: String,
    val secondaryAmount: String,
    val partyLabel: String,
    val party: String,
    val whenLabel: String,
    val fee: String,
)

/** Backup screen + the Settings row subtitle ([statusLabel]). */
data class BackupModel(
    val words: List<String>,
    val revealed: Boolean,
    val countdown: Int,
    val backedUp: Boolean,
    val statusLabel: String,
)

/** Get-paid screen: the one code plus the copy-flip state. */
data class ReceiveModel(
    val code: String,
    val copied: Boolean,
    val copyLabel: String,
    /**
     * The amount this code is asking for, already formatted; null when Get paid is asking for
     * any amount. Shown so the QR's meaning is visible — a code that requests a specific sum
     * looks identical to one that does not.
     */
    val requestedAmount: String? = null,
)

/**
 * One read-only Lightning channel row on the Advanced screen (plan U5, R8: no actions).
 * [value] is `₿<local> of ₿<capacity> · <state>`, already masked in hidden-balance mode.
 */
data class ChannelRowModel(
    val shortId: String,
    val value: String,
    val expiryLabel: String,
)

/**
 * The Advanced screen's Lightning bridge row plus its channel rows. [bridgeValue] is the
 * core's own placeholder string while the snapshot is null (never fetched — demo and stock
 * gateway stay there forever), `0 channels` after a polled-and-empty snapshot, and
 * `<n> channel[s] · ₿<total>` otherwise.
 */
data class ChannelsModel(
    val bridgeValue: String,
    val rows: List<ChannelRowModel>,
)

/** One row of the Advanced screen's DEMO health rail (present only with DemoControls). */
data class DemoHealthOption(
    val state: HealthState,
    val label: String,
    val note: String,
    val dotColorHex: String,
    val selected: Boolean,
)

/**
 * The single immutable UI model the whole app renders. Screens are thin: every money string
 * is pre-formatted (MoneyFormat + the core's fiat rate), every piece of health copy comes from
 * the core's HealthState display data.
 *
 * [sentAmount] is the amount snapshotted when the send was confirmed — the sent screen renders
 * it (not the live keypad) so keypad edits after confirming can't alter the message.
 *
 * [exitAmount] is the full balance in the primary denomination, never masked: the exit screen
 * states what will move on-chain even while the home balance is hidden.
 */
data class AppModel(
    val route: Route,
    val canGoBack: Boolean,
    val screenLabel: String,
    val denomination: Denomination,
    val balance: BalanceModel,
    val exitAmount: String,
    val health: HealthModel,
    val keypad: KeypadModel,
    val send: SendModel,
    val sentAmount: String,
    val txDetail: TxDetailModel,
    val activity: List<ActivityRowModel>,
    val recents: List<Contact>,
    val backup: BackupModel,
    val receive: ReceiveModel,
    val advanced: AdvancedStats,
    val channels: ChannelsModel,
    val demoHealth: List<DemoHealthOption>?,
    val networkLabel: String,
    /** Restore-from-words progress, for the restore screen. The phrase itself is never in here. */
    val restore: RestoreModel,
    /** On-chain deposit state; null when the active core cannot board (demo, gateway). */
    val deposit: DepositModel?,
)

/**
 * The deposit step: where to send money, and what is happening to what has arrived.
 *
 * No action state left — there is nothing to press. The screen shows an address and, once money
 * shows up, the same [ArrivingModel] Home is showing, so a user who leaves mid-wait sees one story
 * rather than two.
 */
data class DepositModel(
    val address: String,
    /** "Copy" / "Copied", sharing the receive screen's 1.6s flip. */
    val copyLabel: String,
    val minLabel: String,
    /** Money on its way, or null when nothing has arrived yet. */
    val arriving: ArrivingModel?,
)

/**
 * What the restore screen shows about its own attempt.
 *
 * Notably absent: the words. They stay in the screen's local state so the app-wide model never holds
 * a recovery phrase for the lifetime of the process.
 */
data class RestoreModel(
    val busy: Boolean = false,
    val failed: Boolean = false,
)
