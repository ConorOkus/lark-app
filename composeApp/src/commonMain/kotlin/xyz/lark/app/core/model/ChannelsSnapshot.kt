package xyz.lark.app.core.model

/** How usable one Lightning channel currently is, derived from the fork's readiness flags. */
enum class ChannelState {
    /** Ready and able to route payments. */
    USABLE,

    /** Funding not confirmed yet: the channel is still opening. */
    OPENING,

    /** Confirmed but not currently usable (peer offline, …). */
    UNUSABLE,
}

/** One Lightning channel as the Advanced screen presents it (amounts are whole sats, KTD-6). */
data class ChannelDisplay(
    /** Abbreviated channel id, head…tail like long destinations in activity. */
    val shortId: String,
    val localSat: Long,
    val capacitySat: Long,
    val state: ChannelState,
    /** Block-countdown expiry line in the Soonest-expiry voice; em-dash while the height is unknown. */
    val expiryLabel: String,
)

/**
 * The wallet's Lightning channels as one displayable snapshot. A null snapshot upstream
 * (`LarkCore.channels`) means never fetched; an empty [channels] list means polled-and-zero.
 */
data class ChannelsSnapshot(
    val channels: List<ChannelDisplay>,
)
