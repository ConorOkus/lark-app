package xyz.lark.app.core.gateway

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

// DTOs mirror the vendored barkd 0.4.0 contract (docs/gateway/barkd-openapi-0.4.0.json)
// field-for-field via @SerialName. Nested shapes are modelled only as deep as the core
// needs; everything else is dropped by ignoreUnknownKeys (see BarkdApi's Json config).
// All amounts are integer sats (KTD-6); heights use Long because the spec types them u32.

/** `GET /api/v1/wallet/balance` — the wallet's balances, broken down by state. */
@Serializable
data class Balance(
    @SerialName("spendable_sat") val spendableSat: Long,
    @SerialName("pending_lightning_send_sat") val pendingLightningSendSat: Long,
    @SerialName("claimable_lightning_receive_sat") val claimableLightningReceiveSat: Long,
    @SerialName("pending_in_round_sat") val pendingInRoundSat: Long,
    @SerialName("pending_board_sat") val pendingBoardSat: Long,
    /** Null when barkd's exit subsystem is unavailable. */
    @SerialName("pending_exit_sat") val pendingExitSat: Long? = null,
)

/**
 * One attempted movement of offchain funds; the unit of `GET /api/v1/history`.
 *
 * [status] is one of `pending`, `successful`, `failed`, `canceled` (kept as a string so an
 * unknown future status degrades gracefully instead of failing the whole history decode).
 */
@Serializable
data class Movement(
    val id: Int,
    val status: String,
    val subsystem: MovementSubsystem,
    @SerialName("intended_balance_sat") val intendedBalanceSat: Long,
    @SerialName("effective_balance_sat") val effectiveBalanceSat: Long,
    @SerialName("offchain_fee_sat") val offchainFeeSat: Long,
    @SerialName("sent_to") val sentTo: List<MovementDestination>,
    @SerialName("received_on") val receivedOn: List<MovementDestination>,
    @SerialName("input_vtxos") val inputVtxos: List<String>,
    @SerialName("output_vtxos") val outputVtxos: List<String>,
    @SerialName("exited_vtxos") val exitedVtxos: List<String>,
    val time: MovementTimestamp,
    /** Schemaless per contract: arbitrary JSON defined by the creating subsystem. */
    val metadata: JsonObject? = null,
)

/** The subsystem that created a [Movement] and the action that registered it. */
@Serializable
data class MovementSubsystem(
    val name: String,
    val kind: String,
)

/** A sender or recipient of a [Movement], with the sats that side saw. */
@Serializable
data class MovementDestination(
    val destination: PaymentMethod,
    @SerialName("amount_sat") val amountSat: Long,
)

/**
 * A payment method: [type] is one of `ark`, `bitcoin`, `output-script`, `invoice`,
 * `offer`, `lightning-address`, `lnurl`, `custom`; [value] is the address/invoice itself.
 */
@Serializable
data class PaymentMethod(
    val type: String,
    val value: String,
)

/** Created/updated/completed times of a [Movement] (RFC 3339 strings). */
@Serializable
data class MovementTimestamp(
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

/** `POST /api/v1/wallet/bip321` request — build a BIP 321 unified payment URI. */
@Serializable
data class Bip321UriRequest(
    @SerialName("amount_sat") val amountSat: Long? = null,
    val label: String? = null,
    val message: String? = null,
    val onchain: Boolean? = null,
)

/** `POST /api/v1/wallet/bip321` response — the combined URI plus each destination. */
@Serializable
data class Bip321UriResponse(
    val bip321: String,
    val ark: String? = null,
    val bolt11: String? = null,
    val onchain: String? = null,
)

/** `POST /api/v1/wallet/send` request; [destination] is an Ark address, BOLT11, LNURL, …. */
@Serializable
data class SendRequest(
    val destination: String,
    @SerialName("amount_sat") val amountSat: Long? = null,
    val comment: String? = null,
)

/** `POST /api/v1/wallet/send` response. */
@Serializable
data class SendResponse(
    val message: String,
)

/**
 * `POST /api/v1/wallet/refresh/all` response, kept shallow: the round [id] and its
 * [status]; participation/funding details are ignored until the core needs them.
 */
@Serializable
data class PendingRoundInfo(
    val id: Int,
    val status: RoundStatus,
)

/**
 * The status object of a pending round; [status] is one of `pending`, `confirmed`,
 * `unconfirmed`, `failed`, `canceled`, `sync-error`. Variant payloads are ignored.
 */
@Serializable
data class RoundStatus(
    val status: String,
)

/** `POST /api/v1/wallet/create` request; [network] is `mainnet`/`signet`/`mutinynet`/`regtest`. */
@Serializable
data class CreateWalletRequest(
    val network: String,
    val mnemonic: String? = null,
    @SerialName("ark_server") val arkServer: String? = null,
    @SerialName("birthday_height") val birthdayHeight: Int? = null,
)

/** `POST /api/v1/wallet/create` response. */
@Serializable
data class CreateWalletResponse(
    val fingerprint: String,
)

/** `GET /api/v1/wallet` — [fingerprint] is null when no wallet exists yet. */
@Serializable
data class WalletExistsResponse(
    val fingerprint: String? = null,
)

/** `GET /api/v1/wallet/mnemonic` — the BIP-39 phrase. Never log this body. */
@Serializable
data class MnemonicResponse(
    val mnemonic: String,
)

/** `GET /api/v1/notifications/wait` — long-poll result, notifications sorted ascending. */
@Serializable
data class WaitNotificationResponse(
    val notifications: List<WalletNotification>,
    @SerialName("last_pushed_at") val lastPushedAt: String? = null,
)

/** A notification of something happening in the wallet, tagged by `type` on the wire. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface WalletNotification {

    /** A new movement was created. */
    @Serializable
    @SerialName("movement-created")
    data class MovementCreated(val movement: Movement) : WalletNotification

    /** An existing movement was updated. */
    @Serializable
    @SerialName("movement-updated")
    data class MovementUpdated(val movement: Movement) : WalletNotification

    /** Some notifications were lost because the client did not consume them fast enough. */
    @Serializable
    @SerialName("channel-lagging")
    data object ChannelLagging : WalletNotification
}

/** `GET /api/v1/wallet/connected` — whether barkd can reach its Ark server. */
@Serializable
data class ConnectedResponse(
    val connected: Boolean,
)

/** `GET /api/v1/bitcoin/tip` — the current chain tip height. */
@Serializable
data class TipResponse(
    @SerialName("tip_height") val tipHeight: Long,
)

/**
 * `GET /api/v1/wallet/ark-info`, kept shallow: only the fields the core needs for health
 * and expiry math; fee schedule, pubkeys and protocol limits are ignored.
 */
@Serializable
data class ArkInfo(
    val network: String,
    @SerialName("round_interval") val roundInterval: String,
    @SerialName("vtxo_expiry_delta") val vtxoExpiryDelta: Int,
    @SerialName("vtxo_exit_delta") val vtxoExitDelta: Int,
)

/**
 * One VTXO from `GET /api/v1/wallet/vtxos`, kept shallow: identity, value, expiry and
 * wallet state; pubkeys and exit details are ignored.
 */
@Serializable
data class WalletVtxoInfo(
    /** Formatted `txid:vout`. */
    val id: String,
    @SerialName("amount_sat") val amountSat: Long,
    @SerialName("expiry_height") val expiryHeight: Long,
    val state: VtxoStateInfo,
)

/**
 * The wallet state of a VTXO; [type] is one of `spendable`, `spent`, `exited`, `locked`.
 * Variant payloads (locking movement/action ids) are ignored.
 */
@Serializable
data class VtxoStateInfo(
    val type: String,
)
