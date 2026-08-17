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
    /**
     * The on-chain route line for review, or null when this is an ordinary off-chain send.
     *
     * Present only for a bitcoin address, because that is the only case where the holder is about
     * to do something materially different from what Pay usually does: slower, irreversible, and
     * out of a different balance. An ordinary Ark or Lightning send says nothing extra.
     */
    val onchainRoute: OnchainRouteModel? = null,
)

/**
 * What review says about a spend that is leaving on-chain.
 *
 * [fee] is an em-dash until the quote lands, and stays one if it cannot be produced. That is the
 * honest reading: R12 requires the fee be named before the holder confirms, and a figure invented
 * to fill the row would be attached to the one action in this flow that cannot be taken back.
 */
data class OnchainRouteModel(
    val fee: String,
    val total: String,
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
    val exitEstimates: ExitEstimatesModel,
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
    /**
     * The unilateral exit in flight, or null when the wallet is not exiting.
     *
     * Non-null is the whole signal: a wallet that is leaving the Ark cannot send, receive, or
     * board, so every surface that offers those reads this first. It is not a route — the exit
     * outlives any screen, and making it one would let the user navigate away from a state they
     * are still in.
     */
    val exiting: ExitingModel?,
    /**
     * The finished exit's receipt, or null when there is none to show.
     *
     * Separate from [exiting] and never non-null at the same time: one says the wallet is leaving,
     * the other that it has left.
     */
    val exitDone: ExitDoneModel?,
)

/**
 * A finished exit, once.
 *
 * Deliberately reads as a result rather than a warning. The funds are on-chain under the holder's
 * own keys at this point, so there is nothing outstanding and nothing to be careful about — the
 * screen's job is to say the thing LARK claims actually happened, and then get out of the way.
 *
 * [minerFee] is an em-dash for now: the engine's claimed state records a txid and a block and no
 * amount, so what the exit actually cost is not recoverable without reading the chain back.
 */
data class ExitDoneModel(
    val landed: String,
    val minerFee: String,
    val took: String,
)

/**
 * The two figures on the exit screen that the app has to work out rather than simply know.
 *
 * Grouped because they share the property the amount does not: either can honestly be unknown, and
 * both arrive as an em-dash when they are. This is the screen that used to state `~$1.80` and
 * `about 24 hours` as literals, so the type exists partly to make "we might not know this" the
 * shape of the data rather than a convention someone has to remember.
 *
 * [minerFee] is unknown today for everyone: bark keeps its exit-cost estimate crate-private, so
 * nothing above the engine can price an exit. [readyIn] is unknown whenever the Ark server is
 * unreachable, because the exit delta lives there and is not persisted — which is exactly the
 * wallet most likely to be reading this screen.
 */
data class ExitEstimatesModel(
    val minerFee: String,
    val readyIn: String,
)

/**
 * A unilateral exit in progress, as the home screen shows it.
 *
 * [claimedOf] and [inFlight] are separate because they answer different questions — how much of
 * the wallet is out, and how much is still in the air — and a user watching a multi-hour exit
 * wants both.
 *
 * [stalled] does not offer a way out, because there is not one. It says the exit is not advancing
 * and the wallet keeps trying, which is the truth; a control here would imply otherwise.
 */
data class ExitingModel(
    /**
     * The largest thing on the surface: a countdown where one is derivable, the state's own name
     * everywhere else.
     *
     * Only the wait between the exit confirming and its funds becoming claimable can be computed,
     * because only that one is bounded by a known height. Before it, the remaining time depends on
     * how long confirmation takes; after it, on how long claiming takes. Neither is knowable, so
     * neither gets a number — the alternative is a countdown that is simply wrong for hours.
     */
    val headline: String,
    val detail: String,
    val inFlight: String,
    val landed: String,
    val claimedOf: String,
    val stalled: Boolean,
    /**
     * The label for the one action a stalled exit can offer, or null when there is nothing to
     * offer — which is every other stall and every advancing exit.
     *
     * Null is the default and the honest one. A stall the holder cannot clear must not come with a
     * button, because a control that cannot work is worse than none: it converts "wait" into
     * "you did something wrong". Only a funding shortfall gets one, and taking it does not leave
     * exiting mode — there is still no cancel.
     */
    val stallAction: String? = null,
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
