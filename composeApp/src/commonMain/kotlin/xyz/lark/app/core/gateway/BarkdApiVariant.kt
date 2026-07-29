package xyz.lark.app.core.gateway

/**
 * Which barkd REST surface [BarkdApi] speaks. Selected at compile time (KTD-10) via
 * `CoreConfig.apiVariant`; the two surfaces differ in routes and request shapes, and
 * [BarkdCapabilities] captures those differences so call sites never switch on the enum.
 */
enum class BarkdApiVariant {
    /** Stock barkd 0.4.0 (docs/gateway/barkd-openapi-0.4.0.json) — the Second signet surface. */
    STOCK_0_4,

    /** Greg's fork 0.1.0-beta.6 (docs/gateway/barkd-fork-openapi-0.1.0-beta.6.json) — mutinynet channels. */
    FORK_BETA6,
}

/**
 * What a [BarkdApiVariant]'s surface offers, derived once in [of]: the route the surface
 * serves history on, which stock-only endpoints exist, and whether the surface speaks the
 * fork's channel endpoints and create-wallet shape ([ForkCreateWalletRequest] vs
 * [CreateWalletRequest]).
 */
data class BarkdCapabilities(
    /** Route serving the movement history (`/api/v1/history` stock, `/api/v1/wallet/history` fork). */
    val historyPath: String,
    /** `POST /api/v1/wallet/bip321` exists (stock only). */
    val hasBip321: Boolean,
    /** `GET /api/v1/wallet/mnemonic` exists (stock only). */
    val hasMnemonic: Boolean,
    /** `GET /api/v1/notifications/wait` exists (stock only). */
    val hasNotifications: Boolean,
    /** The `/api/v1/lightning/channels*` and `/api/v1/wallet/addresses/next` endpoints exist (fork only). */
    val hasChannels: Boolean,
    /** `POST /api/v1/wallet/create` takes the fork body (`ark_server` + `chain_source` required). */
    val usesForkCreateRequest: Boolean,
) {
    companion object {
        fun of(variant: BarkdApiVariant): BarkdCapabilities = when (variant) {
            BarkdApiVariant.STOCK_0_4 -> BarkdCapabilities(
                historyPath = "/api/v1/history",
                hasBip321 = true,
                hasMnemonic = true,
                hasNotifications = true,
                hasChannels = false,
                usesForkCreateRequest = false,
            )
            BarkdApiVariant.FORK_BETA6 -> BarkdCapabilities(
                historyPath = "/api/v1/wallet/history",
                hasBip321 = false,
                hasMnemonic = false,
                hasNotifications = false,
                hasChannels = true,
                usesForkCreateRequest = true,
            )
        }
    }
}
