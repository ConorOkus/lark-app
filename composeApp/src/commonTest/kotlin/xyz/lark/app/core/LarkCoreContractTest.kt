package xyz.lark.app.core

import xyz.lark.app.core.model.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full [LarkCore] seam contract (plan U4, R13): every behavior in
 * [LarkCoreLifecycleContractTest] plus the money-bearing half — a wallet that actually holds a
 * spendable balance and can spend it.
 *
 * This is the suite every core with a fundable backend runs: the fake (demo constants), the
 * gateway (scripted barkd), and the FFI core on its live-captaind lane. The FFI core's per-PR
 * lane runs [LarkCoreLifecycleContractTest] instead, because funding an in-process wallet needs
 * real musig cosigning from a real Ark server.
 */
abstract class LarkCoreContractTest : LarkCoreLifecycleContractTest() {

    /** A settled core with a wallet **and a spendable balance** — the baseline for send tests. */
    override fun CoreFixture.settledWithWallet(): LarkCore {
        core.createWallet()
        settle()
        assertTrue(core.balanceSats.value > 0, "fixture contract: a created wallet has spendable balance")
        return core
    }

    // --- Send success ---

    @Test
    fun successfulSendSettlesIntoADebitedBalance() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        val before = core.balanceSats.value
        fixture.acknowledgeDebit(before - CONTRACT_SEND_SATS)
        assertEquals(SendResult.Success, core.send(CONTRACT_RECIPIENT, CONTRACT_SEND_SATS))
        fixture.settle()
        assertEquals(before - CONTRACT_SEND_SATS, core.balanceSats.value)
    }

    @Test
    fun sendAllowsExactlyTheFullBalance() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        fixture.acknowledgeDebit(0)
        assertEquals(SendResult.Success, core.send(CONTRACT_RECIPIENT, core.balanceSats.value))
        fixture.settle()
        assertEquals(0L, core.balanceSats.value)
    }

    // --- Receive code (seam contract for the amount-specific request) ---

    /**
     * `requestReceiveCode` degrades, it never fails: whatever a core can or cannot mint for a
     * requested amount, it must still hand back something payable. A core that answered with an
     * empty string would leave Get paid with a blank QR, which is worse than offering the
     * amountless code — so no core may do it.
     */
    @Test
    fun requestingACodeForAnAmountNeverLosesTheReceiveCode() = runContractTest {
        val fixture = fixture()
        val core = fixture.settledWithWallet()
        val amountless = core.receiveCode
        assertTrue(amountless.isNotEmpty(), "fixture contract: a settled wallet has a receive code")

        val requested = core.requestReceiveCode(CONTRACT_SEND_SATS)
        assertTrue(
            requested.isNotEmpty(),
            "a core that cannot mint for an amount must fall back to its amountless code",
        )
        assertTrue(
            requested.startsWith(amountless),
            "the amount-specific code extends the amountless one rather than replacing it: $requested",
        )
    }

    /** An amountless request is exactly today's code on every core. */
    @Test
    fun requestingACodeForNoAmountIsTheAmountlessCode() = runContractTest {
        val core = fixture().settledWithWallet()
        assertEquals(core.receiveCode, core.requestReceiveCode(0L))
    }
}
