package xyz.lark.app.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MIN_BOARD_SATS = 20_000L

/**
 * What the deposit screen says the money is for.
 *
 * One address, two meanings. Reached from a stalled exit it funds that exit's miner fees and is
 * deliberately never boarded; reached any other way it is money on its way to being spendable.
 * Showing the board minimum in the first case — which is what shipped — names a threshold with
 * nothing to do with what the deposit is for, on the one screen a stuck holder was sent to.
 */
class DepositExplainerTest {

    @Test
    fun an_ordinary_deposit_names_the_board_minimum() {
        val copy = depositExplainer(exiting = false, minBoardSats = MIN_BOARD_SATS)
        assertTrue(copy.contains("20,000"), "got: $copy")
        assertTrue(copy.contains("spend it"), "got: $copy")
    }

    /**
     * No figure at all while exiting. The number that would matter is the exit's own cost, and the
     * engine keeps that crate-private — so the choice is between silence and the wrong number.
     */
    @Test
    fun a_deposit_during_an_exit_names_no_threshold() {
        val copy = depositExplainer(exiting = true, minBoardSats = MIN_BOARD_SATS)
        assertFalse(copy.contains("20,000"), "the board minimum is not the exit's cost: $copy")
        assertFalse(copy.contains("at least"), "got: $copy")
    }

    @Test
    fun a_deposit_during_an_exit_says_what_it_is_for() {
        val copy = depositExplainer(exiting = true, minBoardSats = MIN_BOARD_SATS)
        assertTrue(copy.contains("miner fees"), "got: $copy")
    }

    /**
     * The surprising part, and the reason this is worth saying: the funding intent is held down
     * during an exit, so this money will *not* become spendable. A holder who expected it to and
     * watched it sit there would reasonably think something had broken.
     */
    @Test
    fun a_deposit_during_an_exit_warns_it_will_not_become_spendable() {
        val copy = depositExplainer(exiting = true, minBoardSats = MIN_BOARD_SATS)
        assertTrue(copy.contains("stays on-chain"), "got: $copy")
        assertTrue(copy.contains("not move it into your spendable balance"), "got: $copy")
    }

    @Test
    fun the_two_cases_do_not_share_wording() {
        assertFalse(
            depositExplainer(exiting = true, minBoardSats = MIN_BOARD_SATS) ==
                depositExplainer(exiting = false, minBoardSats = MIN_BOARD_SATS),
        )
    }
}
