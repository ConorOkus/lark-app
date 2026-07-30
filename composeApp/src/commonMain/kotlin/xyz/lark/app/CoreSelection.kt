package xyz.lark.app

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import xyz.lark.app.core.CoreConfig
import xyz.lark.app.core.CoreMode
import xyz.lark.app.core.DemoControls
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.gateway.BarkdApi
import xyz.lark.app.core.gateway.BarkdApiVariant
import xyz.lark.app.core.gateway.ForkWalletConfig
import xyz.lark.app.core.gateway.GatewayLarkCore
import xyz.lark.app.core.gateway.httpClientEngine
import kotlin.time.ExperimentalTime

/** The composition root's core choice (R10): the seam plus the demo-only controls when present. */
internal data class CoreSelection(val core: LarkCore, val demo: DemoControls?)

/**
 * Builds the configured core (plan U6, R10). DEMO wires the fake as both core and demo
 * controls, exactly as before (AE4). GATEWAY wires [GatewayLarkCore] over the platform HTTP
 * [engine], speaking [CoreConfig.apiVariant]'s barkd surface — labelled [CoreConfig.networkLabel]
 * in the UI and carrying the fork's create pointers — with `demo = null`, so the Advanced
 * DEMO rail's existing null-gating hides it.
 * Non-default parameters exist for tests; production callers pass only mode and scope.
 */
@OptIn(ExperimentalTime::class) // GatewayLarkCore's injectable clock (kotlin.time, stable enough for M1)
internal fun buildCore(
    mode: CoreMode,
    scope: CoroutineScope,
    gatewayBaseUrl: String = CoreConfig.gatewayBaseUrl,
    expectedNetwork: String = CoreConfig.expectedNetwork,
    engine: () -> HttpClientEngine = ::httpClientEngine,
): CoreSelection = when (mode) {
    CoreMode.DEMO -> FakeLarkCore().let { fake -> CoreSelection(core = fake, demo = fake) }
    CoreMode.GATEWAY -> {
        // The fork constants move together (see CoreConfig's fork-mode block): fail fast on a
        // partial edit instead of dying later as a silent, terminal NETWORK_MISMATCH.
        if (CoreConfig.apiVariant == BarkdApiVariant.FORK_BETA6) {
            require(CoreConfig.arkServerUrl.isNotBlank()) {
                "FORK_BETA6 needs CoreConfig.arkServerUrl (see docs/gateway/local-mutinynet.md)"
            }
            require(CoreConfig.expectedNetwork == "signet") {
                "FORK_BETA6 daemons identify as \"signet\" on the wire: set expectedNetwork = \"signet\"" +
                    " and carry the product name in networkLabel (see docs/gateway/local-mutinynet.md)"
            }
        }
        CoreSelection(
            core = GatewayLarkCore(
                api = BarkdApi(engine(), gatewayBaseUrl, variant = CoreConfig.apiVariant),
                scope = scope,
                expectedNetwork = expectedNetwork,
                networkLabel = CoreConfig.networkLabel,
                forkWallet = ForkWalletConfig(
                    arkServerUrl = CoreConfig.arkServerUrl,
                    esploraUrl = CoreConfig.chainSource,
                ),
            ),
            demo = null,
        )
    }
}
