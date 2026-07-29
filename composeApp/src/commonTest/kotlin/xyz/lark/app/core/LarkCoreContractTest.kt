package xyz.lark.app.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RECIPIENT = "ark1qf7demo"
private const val SEND_SATS = 520L
private const val MIN_MNEMONIC_WORDS = 12

/**
 * The shared [LarkCore] seam contract (plan U4, R13): behavior every present and future core
 * must satisfy, executed once per implementation through a thin runner.
 *
 * Fixture contract: the core starts healthy with NO wallet; once [CoreFixture.settled] has run
 * after `createWallet()`, it presents a positive spendable balance. Impl-specific settling is
 * allowed — the fake debits immediately, the gateway on its next poll — so tests always call
 * [CoreFixture.settle] before asserting on asynchronous state.
 *
 * Deliberate divergences stay OUT of this suite: the fake's demo constants and `forceHealth`
 * (pinned in `FakeLarkCoreTest`), and the gateway's polling/health/offline machinery
 * (pinned in the `GatewayLarkCore*Test` files).
 */
abstract class LarkCoreContractTest {

    protected interface CoreFixture {
        val core: LarkCore

        /** Runs the implementation's pending work (poll cycles, launched jobs) to a settled state. */
        fun settle()

        /**
         * Declares the balance the wallet backend reports once the next send lands: the gateway
         * runner rescripts its balance endpoint (debits arrive via poll, never local mutation);
         * the fake debits locally, so its runner ignores this.
         */
        fun acknowledgeDebit(newBalanceSats: Long)
    }

    /** Builds a fresh happy-path fixture; called once at the top of every test. */
    protected abstract fun TestScope.fixture(): CoreFixture

    /** A settled core with a wallet and a spendable balance — the baseline for send tests. */
    private fun CoreFixture.settledWithWallet(): LarkCore {
        core.createWallet()
        settle()
        assertTrue(core.balanceSats.value > 0, "fixture contract: a created wallet has spendable balance")
        return core
    }

    // --- Wallet lifecycle ---

    @Test
    fun walletExistsFlipsFalseToTrueThroughCreateWallet() = runTest {
        val fixture = fixture()
        assertFalse(fixture.core.walletExists.value)
        fixture.core.createWallet()
        fixture.settle()
        assertTrue(fixture.core.walletExists.value)
    }

    @Test
    fun restoreWalletAlsoYieldsAnExistingWallet() = runTest {
        val fixture = fixture()
        assertFalse(fixture.core.walletExists.value)
        fixture.core.restoreWallet()
        fixture.settle()
        assertTrue(fixture.core.walletExists.value)
    }

    // --- Backup ---

    @Test
    fun markBackedUpFlipsTheBackedUpFlow() = runTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        assertFalse(core.backedUp.value)
        core.markBackedUp()
        fixture.settle()
        assertTrue(core.backedUp.value)
    }

    @Test
    fun backupWordsAreNeverFake() = runTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        core.backupWords // a first access may only schedule an on-demand fetch
        fixture.settle()
        val words = core.backupWords
        assertTrue(
            words.isEmpty() || words.size >= MIN_MNEMONIC_WORDS,
            "either no words (words-unavailable) or a real mnemonic, never a stub: $words",
        )
    }

    // --- Send guards ---

    @Test
    fun sendRejectsZeroSatsAndLeavesTheBalanceUnchanged() = runTest {
        val core = fixture().settledWithWallet()
        val before = core.balanceSats.value
        assertEquals(SendResult.Failure, core.send(RECIPIENT, 0))
        assertEquals(before, core.balanceSats.value)
    }

    @Test
    fun sendRejectsNegativeSatsAndLeavesTheBalanceUnchanged() = runTest {
        val core = fixture().settledWithWallet()
        val before = core.balanceSats.value
        assertEquals(SendResult.Failure, core.send(RECIPIENT, -SEND_SATS))
        assertEquals(before, core.balanceSats.value)
    }

    @Test
    fun sendRejectsMoreThanTheBalanceAndLeavesItUnchanged() = runTest {
        val core = fixture().settledWithWallet()
        val before = core.balanceSats.value
        assertEquals(SendResult.Failure, core.send(RECIPIENT, before + 1))
        assertEquals(before, core.balanceSats.value)
    }

    // --- Send success ---

    @Test
    fun successfulSendSettlesIntoADebitedBalance() = runTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        val before = core.balanceSats.value
        fixture.acknowledgeDebit(before - SEND_SATS)
        assertEquals(SendResult.Success, core.send(RECIPIENT, SEND_SATS))
        fixture.settle()
        assertEquals(before - SEND_SATS, core.balanceSats.value)
    }

    @Test
    fun sendAllowsExactlyTheFullBalance() = runTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        fixture.acknowledgeDebit(0)
        assertEquals(SendResult.Success, core.send(RECIPIENT, core.balanceSats.value))
        fixture.settle()
        assertEquals(0L, core.balanceSats.value)
    }

    // --- StateFlow emission ---

    @Test
    fun balanceFlowEmitsItsCurrentValueOnCollection() = runTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        assertEquals(core.balanceSats.value, core.balanceSats.first())
    }

    @Test
    fun healthFlowEmitsReadyOnAHealthyCore() = runTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        assertEquals(HealthState.READY, core.health.first())
        assertEquals(core.health.value, core.health.first())
    }
}
