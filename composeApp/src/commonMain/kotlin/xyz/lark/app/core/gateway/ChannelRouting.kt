package xyz.lark.app.core.gateway

/**
 * Why a send is taking the Ark path rather than a channel (plan R3). Every value here is a
 * silent, honest fallback to the behavior the app already had — never a user-visible failure,
 * and never rendered as copy. These exist so each fallback is separately testable and so a
 * diagnostic can say which precondition was the one that missed.
 */
internal enum class ArkRouteReason {
    /** Stock barkd has no channel endpoints at all. */
    STOCK_SURFACE,

    /** The fork advertises channels but has no live LDK node ([GatewayLarkCore]'s runtime flag). */
    LDK_UNAVAILABLE,

    /** An Ark address, LNURL, lightning address, or anything else `ldk-pay` cannot take. */
    NOT_AN_INVOICE,

    /** `ldk-pay` has no amount field, so an invoice that names none cannot be paid through it. */
    AMOUNTLESS_INVOICE,

    /** The invoice is for another chain; also covers an invoice whose network we could not match. */
    NETWORK_MISMATCH,

    /** The wallet's own configured network is not a name we can map to an invoice prefix. */
    WALLET_NETWORK_UNKNOWN,

    /** The invoice's amount is not the amount the app is sending — see [resolveSendRoute]. */
    AMOUNT_MISMATCH,

    /** No channel is currently able to route (none open, or all opening/offline). */
    NO_USABLE_CHANNEL,

    /** Usable channels exist but our side of them cannot cover the amount. */
    INSUFFICIENT_OUTBOUND,
}

/** Which way a send leaves the wallet. */
internal sealed interface SendRoute {

    /** Pay [bolt11] over one of the wallet's own LDK channels via `ldk-pay`. */
    data class OverChannel(val bolt11: String) : SendRoute

    /** Pay through `POST /wallet/send` exactly as the app always has, because of [reason]. */
    data class OverArk(val reason: ArkRouteReason) : SendRoute
}

/**
 * Sats our side of the usable channels can pay out. Only [LightningChannelInfo.isUsable]
 * channels count: an opening or peer-offline channel's capacity is real but unspendable.
 * Millisats truncate toward zero so the wallet never claims a satoshi it cannot actually send.
 */
internal fun outboundLiquiditySat(channels: List<LightningChannelInfo>): Long =
    channels.filter { it.isUsable }.sumOf { it.localBalanceMsat / MSAT_PER_SAT }

/**
 * Sats the usable channels could receive — the counterparty's side. No endpoint reports this,
 * so it is derived: capacity minus our own balance.
 *
 * This is **zero on a channel the wallet funded itself**, which is the expected live state:
 * such a channel can pay but cannot be paid until value has moved out of it. The receive path
 * degrades on exactly this figure rather than minting an invoice nobody can pay (plan R8).
 */
internal fun inboundLiquiditySat(channels: List<LightningChannelInfo>): Long =
    channels.filter { it.isUsable }
        .sumOf { (it.capacitySat - it.localBalanceMsat / MSAT_PER_SAT).coerceAtLeast(0L) }

/**
 * What the wallet can currently do about channel sends: the surface's capabilities, whether the
 * daemon actually has a live LDK node, the raw wire channels, and the network the wallet is on.
 *
 * Grouped as one value because these four always travel together and are meaningless apart —
 * and because the [channels] here are deliberately the raw wire shapes, not the display
 * snapshot: display models round and format for presentation and must never gate money.
 */
internal data class ChannelSendContext(
    val capabilities: BarkdCapabilities,
    val ldkAvailable: Boolean,
    val channels: List<LightningChannelInfo>,
    val expectedNetwork: String,
)

/**
 * Decides whether [destination] for [sats] leaves over a channel or over Ark, in the order the
 * plan's routing flowchart defines. Pure by design: this is the branch that decides where money
 * goes, so it is tested in isolation rather than inferred from a core's behavior.
 *
 * The amount check is the subtle one. `ldk-pay` accepts an invoice and no amount, so the
 * invoice's own figure is what gets paid; if that disagrees with the [sats] the app is sending
 * (and showed on the review screen), the channel path is refused rather than paying a different
 * number than the user approved.
 */
@Suppress("ReturnCount") // one early return per precondition: the gate reads as its flowchart
internal fun resolveSendRoute(destination: String, sats: Long, context: ChannelSendContext): SendRoute {
    if (!context.capabilities.hasChannels) return arkRoute(ArkRouteReason.STOCK_SURFACE)
    if (!context.ldkAvailable) return arkRoute(ArkRouteReason.LDK_UNAVAILABLE)

    val walletNetwork = bolt11NetworkOf(context.expectedNetwork)
        ?: return arkRoute(ArkRouteReason.WALLET_NETWORK_UNKNOWN)

    val invoice = parseBolt11(destination)
    val invoiceNetwork = when (invoice) {
        is Bolt11.WithAmount -> invoice.network
        is Bolt11.Amountless -> invoice.network
        Bolt11.Unrecognized -> return arkRoute(ArkRouteReason.NOT_AN_INVOICE)
    }
    if (invoiceNetwork != walletNetwork) return arkRoute(ArkRouteReason.NETWORK_MISMATCH)
    if (invoice !is Bolt11.WithAmount) return arkRoute(ArkRouteReason.AMOUNTLESS_INVOICE)
    if (invoice.amountSat != sats) return arkRoute(ArkRouteReason.AMOUNT_MISMATCH)

    val channels = context.channels
    if (channels.none { it.isUsable }) return arkRoute(ArkRouteReason.NO_USABLE_CHANNEL)
    if (outboundLiquiditySat(channels) < sats) return arkRoute(ArkRouteReason.INSUFFICIENT_OUTBOUND)

    return SendRoute.OverChannel(destination.trim())
}

private fun arkRoute(reason: ArkRouteReason): SendRoute = SendRoute.OverArk(reason)

/** How far along an LDK payment is, collapsed to what the seam can say about it. */
internal enum class LdkSettlement {
    /** Terminal success: the money left and the recipient has it. */
    SETTLED,

    /** Terminal failure: the payment did not happen. */
    FAILED,

    /** Not terminal yet — keep waiting; the money may still be moving. */
    IN_FLIGHT,
}

/**
 * Maps a wire status onto [LdkSettlement]. The mapping is total on purpose: an unrecognized
 * status is treated as non-terminal rather than guessed at, so a fork that adds a state can
 * only ever make the app wait longer — never make it claim a settlement it did not observe.
 */
internal fun ldkSettlementOf(wireStatus: String): LdkSettlement =
    when (wireStatus.trim().lowercase()) {
        "sent", "claimed" -> LdkSettlement.SETTLED
        "failed" -> LdkSettlement.FAILED
        else -> LdkSettlement.IN_FLIGHT // pending, claimable, and anything new
    }

/**
 * Whether this error is the fork's "there is no LDK node here" signal — the deployed stack's
 * actual answer on every LDK route (probed 2026-08-03) despite ark-info advertising
 * `supports_channels: true`.
 *
 * The send path keys a real decision on this: only a not-initialized failure proves the daemon
 * attempted nothing, so only it may fall back to the Ark path in place. The match is
 * deliberately narrow — if the fork ever rewords the message, this reads false, the failure
 * classifies as generic, and the send resolves to a plain failure instead of a second payment
 * attempt. It fails toward refusing, never toward paying twice.
 */
internal fun BarkdResult.HttpError.isLdkNotInitialized(): Boolean =
    status >= HTTP_SERVER_ERROR && body.contains(LDK_NOT_INITIALIZED_MARKER, ignoreCase = true)

/**
 * Whether [paymentHash] is the hex-encoded 32-byte hash the fork's contract promises.
 *
 * Worth checking because the hash comes back from the daemon and is then interpolated into a
 * request path ([BarkdApi.ldkPayment]): a value carrying `/`, `..`, or `?` would redirect the
 * settlement poll at a different endpoint. The same validate-before-embedding rule the receive
 * path already applies to addresses and invoices ([arkReceiveUri], [withLightningInvoice]).
 */
internal fun isLdkPaymentHash(paymentHash: String): Boolean =
    paymentHash.length == LDK_PAYMENT_HASH_LENGTH && paymentHash.all { it in LDK_HEX_CHARS }

private const val HTTP_SERVER_ERROR = 500
private const val LDK_NOT_INITIALIZED_MARKER = "LDK node not initialized"

/** 32 bytes, hex-encoded, lower or upper case. */
private const val LDK_PAYMENT_HASH_LENGTH = 64
private const val LDK_HEX_CHARS = "0123456789abcdefABCDEF"
