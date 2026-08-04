package xyz.lark.app.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The expiry countdown, pinned per block spacing.
 *
 * This is safety copy, not decoration: with no background refresh, a user's only protection against
 * a swept VTXO is being told the deadline, so a countdown that reads long when it is short is worse
 * than showing nothing. The arithmetic is small enough to be obviously right and easy to get subtly
 * wrong — the hour floor, the day boundary, and above all the block spacing.
 */
class ExpiryTextTest {

    /** mutinynet's 30-second blocks — what the in-process core ships against. */
    private val fastChain = 30

    /** Bitcoin's 10-minute target — what the gateway core and the design copy assume. */
    private val slowChain = 600

    private val tip = 3_317_247L

    @Test
    fun theSameBlockDeltaMeansWildlyDifferentThingsPerChain() {
        // The whole reason this function takes a spacing. 60,480 blocks is captaind's configured
        // vtxo_lifetime: 21 days on mutinynet, and 420 days if you assume 10-minute blocks. A
        // countdown off by 20x on an expiry warning is the difference between a useful warning and
        // a dangerously reassuring one, so both readings are pinned side by side.
        val expiry = tip + 60_480L
        assertEquals("block 3,377,727 · in 21 days", blockExpiryLabel(expiry, tip, fastChain))
        assertEquals("block 3,377,727 · in 420 days", blockExpiryLabel(expiry, tip, slowChain))
    }

    @Test
    fun theDayBoundaryIsExactAndNotOffByOne() {
        // 2,880 blocks * 30s is exactly 24h, which must read as a day rather than as 24 hours.
        assertEquals("block 3,320,127 · in 1 day", blockExpiryLabel(tip + 2_880L, tip, fastChain))
        // One block less is still hours, and truncates rather than rounding up to a day.
        assertEquals("block 3,320,126 · in 23 hours", blockExpiryLabel(tip + 2_879L, tip, fastChain))
    }

    @Test
    fun aCountdownNeverReadsZeroHours() {
        // Under an hour left is "in 1 hour", not "in 0 hours" and not "expired" — the money is
        // still spendable, and rounding down to zero would read as though it were not.
        assertEquals("block 3,317,248 · in 1 hour", blockExpiryLabel(tip + 1L, tip, fastChain))
    }

    @Test
    fun pluralsAgreeWithTheirCounts() {
        assertEquals("block 3,323,007 · in 2 days", blockExpiryLabel(tip + 5_760L, tip, fastChain))
        assertEquals("block 3,317,487 · in 2 hours", blockExpiryLabel(tip + 240L, tip, fastChain))
    }

    @Test
    fun atOrPastTheTipReadsExpired() {
        assertEquals("block 3,317,247 · expired", blockExpiryLabel(tip, tip, fastChain))
        assertEquals("block 3,317,246 · expired", blockExpiryLabel(tip - 1L, tip, fastChain))
    }

    @Test
    fun unknownsRenderAsAPlaceholderRatherThanACountdown() {
        // No VTXOs at all: there is no deadline, which is not the same as an imminent one.
        assertEquals(EXPIRY_PLACEHOLDER, blockExpiryLabel(null, tip, fastChain))
        // No tip read yet: the delta is unknowable, and a tip of zero would imply a 3.3M-block
        // countdown rather than an absent one.
        assertEquals(EXPIRY_PLACEHOLDER, blockExpiryLabel(tip + 2_880L, 0L, fastChain))
        assertEquals(EXPIRY_PLACEHOLDER, blockExpiryLabel(tip + 2_880L, -1L, fastChain))
    }
}
