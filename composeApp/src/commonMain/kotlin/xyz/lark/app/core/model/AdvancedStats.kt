package xyz.lark.app.core.model

/** Advanced screen, FUNDS group. Expiry/refresh labels are display strings straight from the prototype. */
data class FundsStats(
    /** Null when the engine cannot enumerate VTXOs; renders as an em-dash, never as zero. */
    val vtxoCount: Int?,
    val vtxoTotalSats: Long,
    val soonestExpiry: String,
    val lastRefresh: String,
    /** Null when the on-chain balance has not been read; zero here would be indistinguishable. */
    val onChainReserveSats: Long?,
    val depositAddress: String,
)

/** Advanced screen, NETWORK group. */
data class NetworkStats(
    val arkServerStatus: String,
    val nextRound: String,
    val lightningBridge: String,
    /** Null until a tip has actually been read. */
    val chainTip: Long?,
)

/** Everything the Advanced screen shows, varying with the current health state. */
data class AdvancedStats(
    val funds: FundsStats,
    val network: NetworkStats,
)
