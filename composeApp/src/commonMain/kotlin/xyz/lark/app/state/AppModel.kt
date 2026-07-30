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
    val inputResolved: Boolean,
)

/** One pre-formatted activity row; [amount] is signed in the current denomination. */
data class ActivityRowModel(
    val who: String,
    val whenLabel: String,
    val initial: String,
    val amount: String,
    val incoming: Boolean,
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
)
