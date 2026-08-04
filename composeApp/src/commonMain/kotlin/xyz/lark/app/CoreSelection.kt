package xyz.lark.app

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import xyz.lark.app.core.CoreConfig
import xyz.lark.app.core.CoreMode
import xyz.lark.app.core.DemoControls
import xyz.lark.app.core.FakeLarkCore
import xyz.lark.app.core.LarkCore
import xyz.lark.app.core.OnchainFunding
import xyz.lark.app.core.ffi.FfiCoreProvider
import xyz.lark.app.core.gateway.BarkdApi
import xyz.lark.app.core.gateway.BarkdApiVariant
import xyz.lark.app.core.gateway.ForkWalletConfig
import xyz.lark.app.core.gateway.GatewayLarkCore
import xyz.lark.app.core.gateway.httpClientEngine
import kotlin.time.ExperimentalTime

/**
 * The composition root's core choice (R10): the seam, plus the optional capabilities a given engine
 * happens to have — demo controls, and on-chain funding for a core that holds keys.
 */
internal data class CoreSelection(
    val core: LarkCore,
    val demo: DemoControls?,
    val funding: OnchainFunding? = null,
)

/**
 * Builds the configured core (plan U6, R10). DEMO wires the fake as both core and demo
 * controls, exactly as before (AE4). GATEWAY wires [GatewayLarkCore] over the platform HTTP
 * [engine], speaking [CoreConfig.apiVariant]'s barkd surface — labelled [CoreConfig.networkLabel]
 * in the UI and carrying the fork's create pointers — with `demo = null`, so the Advanced
 * DEMO rail's existing null-gating hides it.
 * Non-default parameters exist for tests; production callers pass only mode and scope.
 */
@OptIn(ExperimentalTime::class) // GatewayLarkCore's injectable clock (kotlin.time, stable enough for M1)
// LongParameterList: every parameter past the first two is an injection seam that exists so a test
// can pin what the shipped CoreConfig would otherwise decide. Production calls pass mode and scope.
@Suppress("LongParameterList")
internal fun buildCore(
    mode: CoreMode,
    scope: CoroutineScope,
    gatewayBaseUrl: String = CoreConfig.gatewayBaseUrl,
    expectedNetwork: String = CoreConfig.expectedNetwork,
    // Injected like the two above, and for the same reason: which barkd surface the gateway speaks
    // changes its endpoints and its capabilities, so a test that does not fix it is really testing
    // whatever the shipped build happens to point at.
    apiVariant: BarkdApiVariant = CoreConfig.apiVariant,
    engine: () -> HttpClientEngine = ::httpClientEngine,
): CoreSelection = when (mode) {
    CoreMode.DEMO -> FakeLarkCore().let { fake -> CoreSelection(core = fake, demo = fake) }
    // The platform builds this one (it owns the Rust bindings and secure storage); see
    // FfiCoreProvider. Absent a factory there is nothing to fall back to that would not be a lie
    // about whose money is on screen, so this fails loudly.
    CoreMode.FFI -> requireNotNull(FfiCoreProvider.factory) {
        "CoreMode.FFI has no core on this platform. iOS registers one from MainViewController; " +
            "Android cannot yet (its FfiLarkCore is blocked — see docs/ffi/kotlin-bindings-status.md), " +
            "so run the Android app with CoreConfig.mode = DEMO or GATEWAY."
    }(scope).let { core ->
        // Boarding is a capability, not a mode: asking the core itself keeps this branch honest if a
        // future in-process core arrives without an on-chain wallet.
        CoreSelection(core = core, demo = null, funding = core as? OnchainFunding)
    }
    CoreMode.GATEWAY -> {
        // The fork constants move together (see CoreConfig's fork-mode block): fail fast on a
        // partial edit instead of dying later as a silent, terminal NETWORK_MISMATCH.
        if (apiVariant == BarkdApiVariant.FORK_BETA6) {
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
                api = BarkdApi(engine(), gatewayBaseUrl, variant = apiVariant),
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
