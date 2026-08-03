package xyz.lark.app.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The highest-consequence branch in the app (plan KTD-2): whether a payment leaves over one of
 * the wallet's own channels or over the Ark server's bridge. Every `OverArk` reason is a silent
 * fallback to today's behavior, never a user-visible failure, so each one gets its own test —
 * the fallbacks *are* the safety property.
 */
class ChannelRoutingTest {

    private val signet = "signet"

    /** 500u = 50,000 sats on signet. */
    private fun signetInvoice(hrpAmount: String = "500u") =
        "lntbs" + hrpAmount + "1qqqqsyqcyq5rqwzqfqypq"

    private fun channel(
        capacitySat: Long = 1_000_000L,
        localBalanceMsat: Long = 500_000_000L,
        isUsable: Boolean = true,
        isChannelReady: Boolean = true,
    ) = LightningChannelInfo(
        channelId = "741e8bd3",
        counterparty = "024fb4d3",
        capacitySat = capacitySat,
        localBalanceMsat = localBalanceMsat,
        isUsable = isUsable,
        isChannelReady = isChannelReady,
    )

    private val forkCapabilities = BarkdCapabilities.of(BarkdApiVariant.FORK_BETA6)
    private val stockCapabilities = BarkdCapabilities.of(BarkdApiVariant.STOCK_0_4)

    @Suppress("LongParameterList") // test builder: each parameter is one independent routing knob
    private fun route(
        destination: String = signetInvoice(),
        sats: Long = 50_000L,
        channels: List<LightningChannelInfo> = listOf(channel()),
        capabilities: BarkdCapabilities = forkCapabilities,
        ldkAvailable: Boolean = true,
        expectedNetwork: String = signet,
    ) = resolveSendRoute(
        destination = destination,
        sats = sats,
        context = ChannelSendContext(capabilities, ldkAvailable, channels, expectedNetwork),
    )

    private fun assertArk(expected: ArkRouteReason, actual: SendRoute) =
        assertEquals(SendRoute.OverArk(expected), actual)

    // --- The channel path ---

    @Test
    fun anAmountMatchingInvoiceWithinOutboundLiquidityGoesOverTheChannel() {
        assertEquals(SendRoute.OverChannel(signetInvoice()), route())
    }

    @Test
    fun theInvoiceIsPassedThroughTrimmedButOtherwiseUntouched() {
        assertEquals(SendRoute.OverChannel(signetInvoice()), route(destination = "  ${signetInvoice()}  "))
    }

    // --- One fallback reason per precondition ---

    @Test
    fun theStockSurfaceNeverRoutesOverAChannel() {
        assertArk(ArkRouteReason.STOCK_SURFACE, route(capabilities = stockCapabilities))
    }

    @Test
    fun anUninitializedLdkNodeFallsBackToArk() {
        assertArk(ArkRouteReason.LDK_UNAVAILABLE, route(ldkAvailable = false))
    }

    @Test
    fun anArkAddressFallsBackToArk() {
        assertArk(ArkRouteReason.NOT_AN_INVOICE, route(destination = "ark1qf7demoaddressvalue"))
    }

    @Test
    fun anLnurlAndALightningAddressFallBackToArk() {
        assertArk(ArkRouteReason.NOT_AN_INVOICE, route(destination = "LNURL1DP68GURN8GHJ7"))
        assertArk(ArkRouteReason.NOT_AN_INVOICE, route(destination = "jack@lark.money"))
    }

    @Test
    fun anAmountlessInvoiceFallsBackToArkBecauseLdkPayCannotCarryAnAmount() {
        assertArk(ArkRouteReason.AMOUNTLESS_INVOICE, route(destination = signetInvoice(hrpAmount = "")))
    }

    @Test
    fun aMainnetInvoiceFallsBackToArk() {
        assertArk(
            ArkRouteReason.NETWORK_MISMATCH,
            route(destination = "lnbc500u1qqqqsyqcyq5rqwzqfqypq"),
        )
    }

    /** If the app cannot name its own network, the check cannot pass — refuse the channel path. */
    @Test
    fun anUnrecognizableWalletNetworkFallsBackToArk() {
        assertArk(ArkRouteReason.WALLET_NETWORK_UNKNOWN, route(expectedNetwork = "moonnet"))
    }

    /** mutinynet is a signet variant, so a signet invoice is on-network for a mutinynet wallet. */
    @Test
    fun aMutinynetWalletAcceptsSignetInvoices() {
        assertEquals(SendRoute.OverChannel(signetInvoice()), route(expectedNetwork = "mutinynet"))
    }

    @Test
    fun anInvoiceAmountThatDisagreesWithTheSendAmountFallsBackToArk() {
        assertArk(ArkRouteReason.AMOUNT_MISMATCH, route(sats = 50_001L))
        assertArk(ArkRouteReason.AMOUNT_MISMATCH, route(sats = 49_999L))
    }

    @Test
    fun noUsableChannelFallsBackToArk() {
        assertArk(ArkRouteReason.NO_USABLE_CHANNEL, route(channels = emptyList()))
        assertArk(
            ArkRouteReason.NO_USABLE_CHANNEL,
            route(channels = listOf(channel(isUsable = false, isChannelReady = false))),
        )
    }

    @Test
    fun insufficientOutboundLiquidityFallsBackToArk() {
        assertArk(
            ArkRouteReason.INSUFFICIENT_OUTBOUND,
            route(channels = listOf(channel(localBalanceMsat = 1_000_000L))),
        )
    }

    // --- Liquidity arithmetic ---

    /** An opening channel's capacity is not spendable, however large it is. */
    @Test
    fun anOpeningChannelContributesNoOutboundLiquidity() {
        val opening = channel(capacitySat = 10_000_000L, localBalanceMsat = 9_000_000_000L, isUsable = false)
        assertEquals(0L, outboundLiquiditySat(listOf(opening)))
        assertArk(ArkRouteReason.NO_USABLE_CHANNEL, route(channels = listOf(opening)))
    }

    @Test
    fun outboundLiquiditySumsAcrossUsableChannels() {
        val channels = listOf(
            channel(localBalanceMsat = 30_000_000L),
            channel(localBalanceMsat = 25_000_000L),
            channel(localBalanceMsat = 9_000_000_000L, isUsable = false), // ignored
        )
        assertEquals(55_000L, outboundLiquiditySat(channels))
        assertEquals(SendRoute.OverChannel(signetInvoice()), route(channels = channels))
    }

    /**
     * Millisats truncate toward zero, so the wallet never claims a satoshi it cannot pay: a
     * balance of 49,999,999 msat is 49,999 sats of liquidity, not 50,000.
     */
    @Test
    fun partialSatoshiBalanceTruncatesDownRatherThanUp() {
        val channels = listOf(channel(localBalanceMsat = 49_999_999L))
        assertEquals(49_999L, outboundLiquiditySat(channels))
        assertArk(ArkRouteReason.INSUFFICIENT_OUTBOUND, route(channels = channels))
        // One sat lower is payable, which pins the boundary from both sides.
        assertEquals(
            SendRoute.OverChannel(signetInvoice(hrpAmount = "499990n")),
            route(destination = signetInvoice(hrpAmount = "499990n"), sats = 49_999L, channels = channels),
        )
    }

    @Test
    fun exactlyEnoughOutboundLiquidityIsEnough() {
        assertEquals(
            SendRoute.OverChannel(signetInvoice()),
            route(channels = listOf(channel(localBalanceMsat = 50_000_000L))),
        )
    }

    /** The expected live state: a channel we funded ourselves holds no inbound capacity at all. */
    @Test
    fun aFreshlyFundedChannelHasZeroInboundLiquidity() {
        val funded = channel(capacitySat = 1_000_000L, localBalanceMsat = 1_000_000_000L)
        assertEquals(1_000_000L, outboundLiquiditySat(listOf(funded)))
        assertEquals(0L, inboundLiquiditySat(listOf(funded)))
    }

    @Test
    fun inboundLiquidityIsTheCounterpartySideOfUsableChannels() {
        val channels = listOf(
            channel(capacitySat = 1_000_000L, localBalanceMsat = 400_000_000L), // 600,000 inbound
            channel(capacitySat = 500_000L, localBalanceMsat = 100_000_000L), // 400,000 inbound
            channel(capacitySat = 9_000_000L, localBalanceMsat = 0L, isUsable = false), // ignored
        )
        assertEquals(1_000_000L, inboundLiquiditySat(channels))
    }

    /** Defensive: a local balance above capacity must not produce negative inbound. */
    @Test
    fun inboundLiquidityNeverGoesNegative() {
        val overfull = channel(capacitySat = 1_000L, localBalanceMsat = 5_000_000L)
        assertEquals(0L, inboundLiquiditySat(listOf(overfull)))
    }

    @Test
    fun liquidityOfNoChannelsIsZero() {
        assertEquals(0L, outboundLiquiditySat(emptyList()))
        assertEquals(0L, inboundLiquiditySat(emptyList()))
    }
}
