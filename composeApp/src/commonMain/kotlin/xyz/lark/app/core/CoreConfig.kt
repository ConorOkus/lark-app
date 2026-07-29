package xyz.lark.app.core

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
}
