package xyz.lark.app.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import xyz.lark.app.core.gateway.BarkdFixtures
import xyz.lark.app.core.gateway.BarkdScript
import xyz.lark.app.core.gateway.HealthyFixtures
import xyz.lark.app.core.gateway.Paths
import xyz.lark.app.core.gateway.barkdEngine
import xyz.lark.app.core.gateway.gatewayCore

private const val FIXTURE_BALANCE = "412350"

/**
 * [LarkCoreContractTest] against [xyz.lark.app.core.gateway.GatewayLarkCore] on a happy-path
 * scripted barkd: reachable mutinynet gateway that starts with no wallet, then serves the
 * healthy default fixtures once `createWallet()` has run.
 */
class GatewayLarkCoreContractTest : LarkCoreContractTest() {

    override fun TestScope.fixture(): CoreFixture {
        val script = BarkdScript()
        // No wallet until the contract's createWallet(); create itself probes this then POSTs.
        script.sticky(Paths.WALLET, BarkdScript.Json(HealthyFixtures.NO_WALLET))
        val gateway = gatewayCore(barkdEngine(script))
        runCurrent() // initial poll probe sees no wallet and stops there
        return object : CoreFixture {
            override val core = gateway

            override fun settle() = runCurrent()

            /** Debits arrive via the poll (never local mutation): rescript the balance endpoint. */
            override fun acknowledgeDebit(newBalanceSats: Long) {
                script.sticky(
                    Paths.BALANCE,
                    BarkdScript.Json(BarkdFixtures.BALANCE.replace(FIXTURE_BALANCE, newBalanceSats.toString())),
                )
            }
        }
    }
}
