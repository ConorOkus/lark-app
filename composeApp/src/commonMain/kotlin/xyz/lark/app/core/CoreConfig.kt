package xyz.lark.app.core

import xyz.lark.app.core.gateway.BarkdApiVariant

/** Which engine backs the app: the built-in demo, a real Ark gateway, or the on-device core. */
enum class CoreMode {
    /** The in-process demo engine ([FakeLarkCore]); no network, no real funds. */
    DEMO,

    /** A real core talking to an Ark gateway over HTTP. */
    GATEWAY,

    /**
     * The in-process Rust core: keys on this device, no gateway (M2).
     *
     * The platform supplies the core through [xyz.lark.app.core.ffi.FfiCoreProvider]; only iOS
     * does so today. [arkServerUrl] and [chainSource] stop being fork-mode-only in this mode —
     * bark talks to captaind and to esplora itself, with nothing in between.
     */
    FFI,
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
    /**
     * Which core the app composes at startup.
     *
     * [CoreMode.FFI] is **iOS-only** today: the Android adapter is still blocked on an off-thread
     * hang in bark's wallet-open path, so an Android build in this mode fails fast at the
     * composition root with an explanation. Switch to [CoreMode.DEMO] to run the Android app.
     */
    val mode: CoreMode = CoreMode.FFI

    /** Base URL of the Ark gateway; empty by design — set per build, never a production default. */
    const val gatewayBaseUrl: String = ""

    /** Network the gateway must report; anything else is a refusal to operate. */
    const val expectedNetwork: String = "signet"

    /** User-facing network name shown in the UI, decoupled from [expectedNetwork]. */
    const val networkLabel: String = "mutinynet"

    // --- Fork mode (BarkdApiVariant.FORK_BETA6) — these move TOGETHER --------------------
    // Selecting the fork variant requires the whole set: the fork daemon identifies as
    // "signet" on the wire, so expectedNetwork must be "signet" (networkLabel stays
    // "mutinynet"), and the fork's wallet create needs the captaind URL. A partial edit
    // fails fast in buildCore rather than as a silent runtime NETWORK_MISMATCH. The full
    // recipe lives in docs/gateway/local-mutinynet.md.

    /** Which barkd REST surface the gateway speaks; only consulted when [mode] is [CoreMode.GATEWAY]. */
    val apiVariant: BarkdApiVariant = BarkdApiVariant.STOCK_0_4

    /**
     * Ark server (captaind) URL. Required by [CoreMode.FFI] — the on-device wallet talks to
     * captaind directly — and by the fork's gateway wallet create.
     */
    const val arkServerUrl: String = "http://77.83.143.203:3535"

    /**
     * Esplora chain source. Required by [CoreMode.FFI]: bdk fetches the genesis hash to confirm the
     * network at wallet creation, and an on-chain sync reads the tip, fee estimates and recent
     * blocks through it. Also used by the fork's gateway wallet create, where it may stay empty on
     * a stack whose wallet already exists.
     */
    const val chainSource: String = "https://mutinynet.com/api"
}
