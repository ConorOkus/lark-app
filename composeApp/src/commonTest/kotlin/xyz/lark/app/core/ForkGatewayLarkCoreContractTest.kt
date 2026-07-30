package xyz.lark.app.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import xyz.lark.app.core.gateway.BarkdApiVariant
import xyz.lark.app.core.gateway.BarkdFixtures
import xyz.lark.app.core.gateway.BarkdScript
import xyz.lark.app.core.gateway.Paths
import xyz.lark.app.core.gateway.barkdEngine
import xyz.lark.app.core.gateway.gatewayCore

private const val FIXTURE_BALANCE = "412350"

/**
 * [LarkCoreContractTest] against [xyz.lark.app.core.gateway.GatewayLarkCore] speaking the
 * fork surface ([BarkdApiVariant.FORK_BETA6]) on a happy-path scripted barkd: the fork has
 * no wallet-existence probe, so the daemon simply starts walletless and the contract's
 * `createWallet()` is the first (and only) existence signal.
 */
class ForkGatewayLarkCoreContractTest : LarkCoreContractTest() {

    override fun TestScope.fixture(): CoreFixture {
        val script = BarkdScript(BarkdScript.forkDefaults)
        val gateway = gatewayCore(barkdEngine(script), variant = BarkdApiVariant.FORK_BETA6)
        runCurrent() // initial poll cycle is walletless: it only pings for reachability
        return object : CoreFixture {
            override val core = gateway

            override fun settle() = runCurrent()

            /** Debits arrive via the poll (never local mutation): rescript the balance endpoint. */
            override fun acknowledgeDebit(newBalanceSats: Long) {
                script.sticky(
                    Paths.BALANCE,
                    BarkdScript.Json(
                        BarkdFixtures.FORK_BALANCE.replace(FIXTURE_BALANCE, newBalanceSats.toString()),
                    ),
                )
            }
        }
    }
}
