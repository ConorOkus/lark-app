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
 * Decides whether [destination] for [sats] leaves over a channel or over Ark, in the order the
 * plan's routing flowchart defines. Pure by design: this is the branch that decides where money
 * goes, so it is tested in isolation rather than inferred from a core's behavior.
 *
 * It reads the raw wire [channels] rather than the display snapshot on purpose — display models
 * round and format for presentation and must never gate money.
 *
 * The amount check is the subtle one. `ldk-pay` accepts an invoice and no amount, so the
 * invoice's own figure is what gets paid; if that disagrees with the [sats] the app is sending
 * (and showed on the review screen), the channel path is refused rather than paying a different
 * number than the user approved.
 */
@Suppress("ReturnCount") // one early return per precondition is the clearest shape for this gate
internal fun resolveSendRoute(
    destination: String,
    sats: Long,
    channels: List<LightningChannelInfo>,
    capabilities: BarkdCapabilities,
    ldkAvailable: Boolean,
    expectedNetwork: String,
): SendRoute {
    if (!capabilities.hasChannels) return arkRoute(ArkRouteReason.STOCK_SURFACE)
    if (!ldkAvailable) return arkRoute(ArkRouteReason.LDK_UNAVAILABLE)

    val walletNetwork = bolt11NetworkOf(expectedNetwork)
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

    if (channels.none { it.isUsable }) return arkRoute(ArkRouteReason.NO_USABLE_CHANNEL)
    if (outboundLiquiditySat(channels) < sats) return arkRoute(ArkRouteReason.INSUFFICIENT_OUTBOUND)

    return SendRoute.OverChannel(destination.trim())
}

private fun arkRoute(reason: ArkRouteReason): SendRoute = SendRoute.OverArk(reason)

private const val MSAT_PER_SAT = 1_000L
