package xyz.lark.app.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The parser gates money routing (plan R2/R3): the fork's `ldk-pay` carries no amount, so what
 * this reads out of the invoice is what will actually be paid. Every case it cannot read with
 * confidence must land on [Bolt11.Unrecognized] so the send falls back to the Ark path rather
 * than paying an amount the review screen never showed.
 */
class Bolt11Test {

    private fun invoice(hrp: String) = hrp + "1" + "qqqqsyqcyq5rqwzqfqypq"

    // --- Amount multipliers ---

    @Test
    fun milliBitcoinAmountParsesToSats() {
        assertEquals(Bolt11.WithAmount(Bolt11Network.SIGNET, 100_000L), parseBolt11(invoice("lntbs1m")))
    }

    @Test
    fun microBitcoinAmountParsesToSats() {
        assertEquals(Bolt11.WithAmount(Bolt11Network.SIGNET, 50_000L), parseBolt11(invoice("lntbs500u")))
    }

    @Test
    fun nanoBitcoinAmountParsesToSats() {
        assertEquals(Bolt11.WithAmount(Bolt11Network.SIGNET, 1L), parseBolt11(invoice("lntbs10n")))
    }

    @Test
    fun picoBitcoinAmountParsesToSats() {
        assertEquals(Bolt11.WithAmount(Bolt11Network.SIGNET, 1L), parseBolt11(invoice("lntbs10000p")))
    }

    /** No multiplier means the amount is in whole BTC. */
    @Test
    fun bareAmountIsReadAsWholeBitcoin() {
        assertEquals(Bolt11.WithAmount(Bolt11Network.MAINNET, 200_000_000L), parseBolt11(invoice("lnbc2")))
    }

    // --- Amounts this app must refuse rather than round ---

    /** 1n is a tenth of a satoshi: rounding it either way would misstate money. */
    @Test
    fun subSatoshiNanoAmountIsUnrecognizedRatherThanRounded() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lntbs1n")))
    }

    @Test
    fun subSatoshiPicoAmountIsUnrecognizedRatherThanRounded() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lntbs1p")))
    }

    /** A pico amount that is not a whole millisatoshi is not representable on the wire either. */
    @Test
    fun subMillisatoshiPicoAmountIsUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lntbs10001p")))
    }

    @Test
    fun absurdlyLongAmountIsUnrecognizedRatherThanOverflowing() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lntbs99999999999999999999m")))
    }

    // --- Amountless ---

    /** An amountless invoice is not a zero-amount invoice; the distinction decides routing. */
    @Test
    fun amountlessInvoiceReportsAmountlessNotZero() {
        assertEquals(Bolt11.Amountless(Bolt11Network.SIGNET), parseBolt11(invoice("lntbs")))
    }

    @Test
    fun amountlessMainnetInvoiceReportsAmountless() {
        assertEquals(Bolt11.Amountless(Bolt11Network.MAINNET), parseBolt11(invoice("lnbc")))
    }

    // --- Networks ---

    @Test
    fun eachKnownNetworkPrefixIsRecognized() {
        assertEquals(Bolt11.Amountless(Bolt11Network.MAINNET), parseBolt11(invoice("lnbc")))
        assertEquals(Bolt11.Amountless(Bolt11Network.TESTNET), parseBolt11(invoice("lntb")))
        assertEquals(Bolt11.Amountless(Bolt11Network.SIGNET), parseBolt11(invoice("lntbs")))
        assertEquals(Bolt11.Amountless(Bolt11Network.REGTEST), parseBolt11(invoice("lnbcrt")))
    }

    /**
     * `tb` prefixes `tbs` and `bc` prefixes `bcrt`: a shortest-match parser would read every
     * signet invoice as testnet and route a wrong-network payment as if it were payable.
     */
    @Test
    fun longerNetworkPrefixesWinOverTheirShorterPrefixes() {
        assertEquals(Bolt11Network.SIGNET, (parseBolt11(invoice("lntbs500u")) as Bolt11.WithAmount).network)
        assertEquals(Bolt11Network.REGTEST, (parseBolt11(invoice("lnbcrt500u")) as Bolt11.WithAmount).network)
    }

    @Test
    fun mainnetInvoiceReportsMainnetSoRoutingCanRefuseIt() {
        assertEquals(Bolt11.WithAmount(Bolt11Network.MAINNET, 250_000L), parseBolt11(invoice("lnbc2500u")))
    }

    @Test
    fun unknownNetworkPrefixIsUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lnxyz500u")))
    }

    // --- Shape and hygiene ---

    /** BOLT11 permits an all-uppercase encoding (QR alphanumeric mode). */
    @Test
    fun uppercaseInvoiceParses() {
        assertEquals(
            Bolt11.WithAmount(Bolt11Network.SIGNET, 50_000L),
            parseBolt11(invoice("lntbs500u").uppercase()),
        )
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(
            Bolt11.WithAmount(Bolt11Network.SIGNET, 50_000L),
            parseBolt11("  ${invoice("lntbs500u")}  "),
        )
    }

    @Test
    fun missingSeparatorIsUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11("lntbs500u"))
    }

    @Test
    fun emptyStringIsUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(""))
        assertEquals(Bolt11.Unrecognized, parseBolt11("   "))
    }

    @Test
    fun nonInvoiceDestinationsAreUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11("ark1qf7demoaddressvalue"), "ark address")
        assertEquals(Bolt11.Unrecognized, parseBolt11("LNURL1DP68GURN8GHJ7"), "lnurl")
        assertEquals(Bolt11.Unrecognized, parseBolt11("jack@lark.money"), "lightning address")
        assertEquals(Bolt11.Unrecognized, parseBolt11("bitcoin:?ark=ark1qf7demo"), "bip321 uri")
        assertEquals(Bolt11.Unrecognized, parseBolt11("not an invoice at all"), "prose")
    }

    /** A trailing multiplier with no digits is malformed, not an amountless invoice. */
    @Test
    fun multiplierWithoutDigitsIsUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lntbsu")))
    }

    @Test
    fun unknownMultiplierIsUnrecognized() {
        assertEquals(Bolt11.Unrecognized, parseBolt11(invoice("lntbs500x")))
    }

    // --- App network vocabulary -> bolt11 network ---

    @Test
    fun expectedNetworkNamesMapToBolt11Networks() {
        assertEquals(Bolt11Network.SIGNET, bolt11NetworkOf("signet"))
        assertEquals(Bolt11Network.SIGNET, bolt11NetworkOf("mutinynet"), "mutinynet is a signet variant")
        assertEquals(Bolt11Network.MAINNET, bolt11NetworkOf("bitcoin"))
        assertEquals(Bolt11Network.MAINNET, bolt11NetworkOf("mainnet"))
        assertEquals(Bolt11Network.TESTNET, bolt11NetworkOf("testnet"))
        assertEquals(Bolt11Network.REGTEST, bolt11NetworkOf("regtest"))
        assertEquals(Bolt11Network.SIGNET, bolt11NetworkOf("SIGNET"), "case-insensitive")
    }

    @Test
    fun anUnknownExpectedNetworkNameHasNoBolt11Network() {
        assertEquals(null, bolt11NetworkOf("moonnet"))
    }
}
