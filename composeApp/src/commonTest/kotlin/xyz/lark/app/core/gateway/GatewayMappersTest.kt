package xyz.lark.app.core.gateway

import xyz.lark.app.core.model.ChannelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIP_HEIGHT = 916_214L

/**
 * Pure wire → display mapping for the fork's channel shapes (plan U4): every field of
 * [xyz.lark.app.core.model.ChannelDisplay] and [xyz.lark.app.core.model.ChannelsSnapshot]
 * is pinned here, without a core instance.
 */
class GatewayMappersTest {

    @Suppress("LongParameterList") // fixture builder: each parameter is one independent wire field
    private fun channel(
        channelId: String = "741e8bd3".repeat(8),
        localBalanceMsat: Long = 500_000_000L,
        capacitySat: Long = 1_000_000L,
        isUsable: Boolean = true,
        isChannelReady: Boolean = true,
        expiryHeight: Long? = null,
    ) = LightningChannelInfo(
        channelId = channelId,
        counterparty = "024fb4d3",
        capacitySat = capacitySat,
        localBalanceMsat = localBalanceMsat,
        isUsable = isUsable,
        isChannelReady = isChannelReady,
        expiryHeight = expiryHeight,
        forceCloseSpendDelay = 144,
    )

    // --- Tri-state readiness ---

    @Test
    fun usableChannelMapsToUsable() {
        val display = channelDisplay(channel(isUsable = true, isChannelReady = true), TIP_HEIGHT)
        assertEquals(ChannelState.USABLE, display.state)
    }

    @Test
    fun notReadyChannelMapsToOpening() {
        val display = channelDisplay(channel(isUsable = false, isChannelReady = false), TIP_HEIGHT)
        assertEquals(ChannelState.OPENING, display.state)
    }

    @Test
    fun readyButUnusableChannelMapsToUnusable() {
        val display = channelDisplay(channel(isUsable = false, isChannelReady = true), TIP_HEIGHT)
        assertEquals(ChannelState.UNUSABLE, display.state)
    }

    // --- Amounts (KTD-6: integer sats end-to-end) ---

    @Test
    fun localBalanceMsatConvertsToWholeSats() {
        assertEquals(500_000L, channelDisplay(channel(localBalanceMsat = 500_000_000L), TIP_HEIGHT).localSat)
        assertEquals(1L, channelDisplay(channel(localBalanceMsat = 1_999L), TIP_HEIGHT).localSat)
        assertEquals(1_000_000L, channelDisplay(channel(), TIP_HEIGHT).capacitySat)
    }

    // --- Short id (head…tail, like long destinations in activity) ---

    @Test
    fun longChannelIdAbbreviatesToHeadAndTail() {
        val display = channelDisplay(channel(channelId = "741e8bd3".repeat(8)), TIP_HEIGHT)
        assertEquals("741e8bd3…8bd3", display.shortId)
    }

    @Test
    fun shortChannelIdStaysWhole() {
        assertEquals("741e8bd3", channelDisplay(channel(channelId = "741e8bd3"), TIP_HEIGHT).shortId)
    }

    // --- Expiry label (block-countdown voice, em-dash when unknown) ---

    @Test
    fun nullExpiryHeightRendersThePlaceholder() {
        assertEquals(PLACEHOLDER, channelDisplay(channel(expiryHeight = null), TIP_HEIGHT).expiryLabel)
    }

    @Test
    fun presentExpiryHeightCountsDownInTheBlockVoice() {
        val oneDayOut = channelDisplay(channel(expiryHeight = TIP_HEIGHT + 144), TIP_HEIGHT)
        assertEquals("block 916,358 · in 1 day", oneDayOut.expiryLabel)
        val twoHoursOut = channelDisplay(channel(expiryHeight = TIP_HEIGHT + 12), TIP_HEIGHT)
        assertEquals("block 916,226 · in 2 hours", twoHoursOut.expiryLabel)
    }

    @Test
    fun expiryStaysAPlaceholderUntilTheTipIsKnown() {
        assertEquals(PLACEHOLDER, channelDisplay(channel(expiryHeight = 918_402L), 0L).expiryLabel)
    }

    // --- Snapshot assembly ---

    @Test
    fun snapshotMapsEveryChannelAndTheRowsCarryTheTotal() {
        val snapshot = channelsSnapshot(
            channels = listOf(channel(localBalanceMsat = 300_000_000L), channel(localBalanceMsat = 200_000_000L)),
            tipHeight = TIP_HEIGHT,
        )
        assertEquals(2, snapshot.channels.size)
        assertEquals(500_000L, snapshot.channels.sumOf { it.localSat }, "the rows ARE the bridge total (R7)")
    }

    @Test
    fun snapshotOfZeroChannelsIsEmptyNotNull() {
        val snapshot = channelsSnapshot(channels = emptyList(), tipHeight = TIP_HEIGHT)
        assertTrue(snapshot.channels.isEmpty())
    }
}
