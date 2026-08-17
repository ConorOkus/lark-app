package xyz.lark.app.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// BIP-173 examples, plus the Ark forms bark encodes (`ark` mainnet, `tark` testnet).
private const val SIGNET_ADDRESS = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
private const val MAINNET_ADDRESS = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
private const val REGTEST_ADDRESS = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
// Data parts reuse the BIP-173 example payload: bech32 excludes b, i, o and 1, so a
// readable-looking fixture like "demoaddress" fails the shape check before classification.
private const val ARK_ADDRESS = "ark1qw508d6qejxtdg4y5r3zarvary0c5xw7k"
private const val ARK_TESTNET_ADDRESS = "tark1qw508d6qejxtdg4y5r3zarvary0c5xw7k"

/**
 * Telling a bitcoin address apart from an Ark one.
 *
 * This closes a defect that shipped: both are bech32, so an on-chain address satisfied the same
 * shape check as an Ark address, was offered as payable, and then failed deep in the Ark send path
 * where the reason was unrecoverable. The two are distinguishable only by their prefix, which is
 * why that is what gets tested.
 */
class OnchainDestinationTest {

    @Test
    fun bitcoin_addresses_are_recognised_as_on_chain() {
        listOf(SIGNET_ADDRESS, MAINNET_ADDRESS, REGTEST_ADDRESS).forEach { address ->
            val input = classifySendInput(address)
            assertTrue(input.isResolved, "$address should be payable")
            assertTrue(input.isOnchain, "$address should route on-chain")
        }
    }

    /**
     * The case a looser prefix test would break: `tb` and `tark` both begin with `t`, so matching
     * without the bech32 separator would send every Ark testnet payment down the on-chain path.
     */
    @Test
    fun ark_addresses_are_not_mistaken_for_bitcoin_ones() {
        listOf(ARK_ADDRESS, ARK_TESTNET_ADDRESS).forEach { address ->
            val input = classifySendInput(address)
            assertTrue(input.isResolved, "$address should still be payable")
            assertFalse(input.isOnchain, "$address must not route on-chain")
        }
    }

    /** Covers R24: the off-chain forms classify exactly as they did before on-chain existed. */
    @Test
    fun lightning_forms_are_untouched() {
        val forms = listOf(
            "lnbc1qw508d6qejxtdg4y5r3zarvary0c5xw7k",
            "alice@example.com",
            "LNURL1DP68GURN8GHJ7",
        )
        forms.forEach { form ->
            val input = classifySendInput(form)
            assertTrue(input.isResolved, "$form should be payable")
            assertEquals(SendKind.OFF_CHAIN, input.kind, "$form must stay off-chain")
        }
    }

    @Test
    fun unrecognised_text_is_still_refused_and_carries_no_kind_claim() {
        val input = classifySendInput("hello there")
        assertFalse(input.isResolved)
        assertFalse(input.isOnchain, "nothing unresolved may claim a route")
    }

    @Test
    fun an_empty_input_is_not_on_chain() {
        assertFalse(classifySendInput("").isOnchain)
    }
}
