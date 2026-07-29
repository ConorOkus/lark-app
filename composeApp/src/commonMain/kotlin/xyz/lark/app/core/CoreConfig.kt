package xyz.lark.app.core

import xyz.lark.app.core.gateway.BarkdApiVariant

/** Which engine backs the app: the built-in demo, or a real Ark gateway. */
enum class CoreMode {
    /** The in-process demo engine ([FakeLarkCore]); no network, no real funds. */
    DEMO,

    /** A real core talking to an Ark gateway over HTTP. */
    GATEWAY,
}

/**
 * Compile-time core selection for the composition root (plan KTD-10: constants for now, a real
 * config source can replace this later without touching call sites).
 *
 * [CoreMode.DEMO] is the default. [gatewayBaseUrl] has NO production default and must be an
 * `https://` URL for any non-loopback host (plan R2); it is only consulted when [mode] is
 * [CoreMode.GATEWAY].
 */
object CoreConfig {
    /** Which core the app composes at startup. */
    val mode: CoreMode = CoreMode.DEMO

    /** Base URL of the Ark gateway; empty by design — set per build, never a production default. */
    const val gatewayBaseUrl: String = ""

    /** Network the gateway must report; anything else is a refusal to operate. */
    const val expectedNetwork: String = "mutinynet"

    /** Which barkd REST surface the gateway speaks; only consulted when [mode] is [CoreMode.GATEWAY]. */
    val apiVariant: BarkdApiVariant = BarkdApiVariant.STOCK_0_4

    /**
     * Ark server (captaind) URL the fork's wallet create requires; only consulted when
     * [apiVariant] is [BarkdApiVariant.FORK_BETA6]. Empty by design — set per build, never
     * a production default.
     */
    const val arkServerUrl: String = ""

    /**
     * Esplora URL the fork's wallet create uses as its chain source; only consulted when
     * [apiVariant] is [BarkdApiVariant.FORK_BETA6]. Empty by design — set per build, never
     * a production default.
     */
    const val chainSource: String = ""

    /** User-facing network name shown in the UI, decoupled from [expectedNetwork]. */
    const val networkLabel: String = "mutinynet"
}
