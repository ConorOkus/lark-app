package xyz.lark.app.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import xyz.lark.app.core.model.HealthState
import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val CONTRACT_RECIPIENT = "ark1qf7demo"
internal const val CONTRACT_SEND_SATS = 520L
internal const val CONTRACT_MIN_MNEMONIC_WORDS = 12

/**
 * The half of the [LarkCore] seam contract a wallet with **no funds** can satisfy: lifecycle,
 * backup acknowledgement, backup words, the send guards, and flow emission.
 *
 * This split exists because the in-process Rust core cannot be funded without a real Ark server
 * (bark needs real musig cosigning, which no in-process fixture supplies), so a fresh FFI wallet
 * has a zero balance. Everything here holds at zero — note that the over-balance send guard is
 * *more* strictly exercised at zero, not less, since every positive amount exceeds the balance.
 * Money-bearing behavior lives in [LarkCoreContractTest], which extends this class.
 *
 * Which side does a NEW test belong on? If it needs a positive spendable balance to mean
 * anything, it goes in [LarkCoreContractTest]. Otherwise it goes here, so every core — funded or
 * not — is held to it.
 *
 * Fixture contract: the core starts healthy with NO wallet. Impl-specific settling is allowed —
 * the fake debits immediately, the gateway on its next poll, the FFI core on real Rust threads —
 * so tests always call [CoreFixture.settle] before asserting on asynchronous state.
 *
 * Deliberate divergences stay OUT of this suite: the fake's demo constants and `forceHealth`
 * (pinned in `FakeLarkCoreTest`), and the gateway's polling/health/offline machinery
 * (pinned in the `GatewayLarkCore*Test` files).
 */
abstract class LarkCoreLifecycleContractTest {

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

    /**
     * How each test in this suite runs its coroutine body. Plain [runTest] for the in-memory
     * cores, overridable for a lane whose work lands on real threads outside the test scheduler
     * (the FFI core): there, a slow first wallet open can outrun `runTest`'s default timeout and
     * surface as an opaque test-timeout instead of the fixture's own "still pending" message.
     * Raising the timeout is the right lever; shortening the fixture's settle budget is not.
     */
    protected open fun runContractTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest { body() }

    /**
     * A settled core with a wallet — the baseline for every test here.
     *
     * [LarkCoreContractTest] tightens this to additionally require a positive spendable balance,
     * which is exactly the difference between the two lanes.
     */
    protected open fun CoreFixture.settledWithWallet(): LarkCore {
        core.createWallet()
        settle()
        return core
    }

    // --- Wallet lifecycle ---

    @Test
    fun walletExistsFlipsFalseToTrueThroughCreateWallet() = runContractTest {
        val fixture = fixture()
        assertFalse(fixture.core.walletExists.value)
        fixture.core.createWallet()
        fixture.settle()
        assertTrue(fixture.core.walletExists.value)
    }

    @Test
    fun restoreWalletAlsoYieldsAnExistingWallet() = runContractTest {
        val fixture = fixture()
        assertFalse(fixture.core.walletExists.value)
        fixture.core.restoreWallet()
        fixture.settle()
        assertTrue(fixture.core.walletExists.value)
    }

    // --- Backup ---

    @Test
    fun markBackedUpFlipsTheBackedUpFlow() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        assertFalse(core.backedUp.value)
        core.markBackedUp()
        fixture.settle()
        assertTrue(core.backedUp.value)
    }

    @Test
    fun backupWordsAreNeverFake() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        core.backupWords // a first access may only schedule an on-demand fetch
        fixture.settle()
        val words = core.backupWords
        assertTrue(
            words.isEmpty() || words.size >= CONTRACT_MIN_MNEMONIC_WORDS,
            "either no words (words-unavailable) or a real mnemonic, never a stub: $words",
        )
    }

    // --- Send guards ---

    @Test
    fun sendRejectsZeroSatsAndLeavesTheBalanceUnchanged() = runContractTest {
        val core = fixture().settledWithWallet()
        val before = core.balanceSats.value
        assertEquals(SendResult.Failure, core.send(CONTRACT_RECIPIENT, 0))
        assertEquals(before, core.balanceSats.value)
    }

    @Test
    fun sendRejectsNegativeSatsAndLeavesTheBalanceUnchanged() = runContractTest {
        val core = fixture().settledWithWallet()
        val before = core.balanceSats.value
        assertEquals(SendResult.Failure, core.send(CONTRACT_RECIPIENT, -CONTRACT_SEND_SATS))
        assertEquals(before, core.balanceSats.value)
    }

    @Test
    fun sendRejectsMoreThanTheBalanceAndLeavesItUnchanged() = runContractTest {
        val core = fixture().settledWithWallet()
        val before = core.balanceSats.value
        assertEquals(SendResult.Failure, core.send(CONTRACT_RECIPIENT, before + 1))
        assertEquals(before, core.balanceSats.value)
    }

    // --- StateFlow emission ---

    @Test
    fun balanceFlowEmitsItsCurrentValueOnCollection() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        assertEquals(core.balanceSats.value, core.balanceSats.first())
    }

    @Test
    fun healthFlowEmitsReadyOnAHealthyCore() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        assertEquals(HealthState.READY, core.health.first())
        assertEquals(core.health.value, core.health.first())
    }
}
