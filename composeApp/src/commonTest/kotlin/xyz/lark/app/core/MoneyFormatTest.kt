package xyz.lark.app.core

import xyz.lark.app.core.format.MoneyFormat
import xyz.lark.app.core.model.FiatRate
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {

    /** The prototype's demo rate: 1 sat = $0.001, i.e. 10 sats per cent. */
    private val demoRate = FiatRate(satsPerCent = 10)

    @Test
    fun btcFormatsDemoBalancePerBip177() {
        assertEquals("₿412,350", MoneyFormat.btc(412_350))
    }

    @Test
    fun btcFormatsZero() {
        assertEquals("₿0", MoneyFormat.btc(0))
    }

    @Test
    fun btcGroupsAtThousandBoundaries() {
        assertEquals("₿999", MoneyFormat.btc(999))
        assertEquals("₿1,000", MoneyFormat.btc(1_000))
        assertEquals("₿999,999", MoneyFormat.btc(999_999))
        assertEquals("₿1,000,000", MoneyFormat.btc(1_000_000))
        assertEquals("₿100,000,000", MoneyFormat.btc(100_000_000))
    }

    @Test
    fun fiatConvertsDemoBalanceAtDemoRate() {
        assertEquals("$412.35", MoneyFormat.fiat(412_350, demoRate))
    }

    @Test
    fun fiatRoundsSubCentAmountsHalfUp() {
        // 1 sat = 0.1 cents -> rounds to $0.00 (prototype: Math.round(1 / 10) === 0).
        assertEquals("$0.00", MoneyFormat.fiat(1, demoRate))
        // 5 sats = 0.5 cents -> rounds to $0.01 (Math.round(0.5) === 1).
        assertEquals("$0.01", MoneyFormat.fiat(5, demoRate))
        assertEquals("$0.00", MoneyFormat.fiat(4, demoRate))
    }

    @Test
    fun fiatGroupsDollarsWithSeparators() {
        // 100,000,000 sats -> 10,000,000 cents -> $100,000.00
        assertEquals("$100,000.00", MoneyFormat.fiat(100_000_000, demoRate))
    }

    @Test
    fun signedBtcUsesPlusAndUnicodeMinusLikeTheActivityList() {
        assertEquals("+₿250,000", MoneyFormat.signedBtc(250_000))
        assertEquals("−₿14,200", MoneyFormat.signedBtc(-14_200))
    }

    @Test
    fun signedFiatUsesPlusAndUnicodeMinus() {
        assertEquals("+$250.00", MoneyFormat.signedFiat(250_000, demoRate))
        assertEquals("−$14.20", MoneyFormat.signedFiat(-14_200, demoRate))
        assertEquals("−$0.52", MoneyFormat.signedFiat(-520, demoRate))
    }

    @Test
    fun rateConvertsBothDirections() {
        assertEquals(41_235, demoRate.satsToCents(412_350))
        assertEquals(5_200, demoRate.centsToSats(520))
    }
}
