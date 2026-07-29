package xyz.lark.app.core.model

/** Advanced screen, FUNDS group. Expiry/refresh labels are display strings straight from the prototype. */
data class FundsStats(
    val vtxoCount: Int,
    val vtxoTotalSats: Long,
    val soonestExpiry: String,
    val lastRefresh: String,
    val onChainReserveSats: Long,
    val depositAddress: String,
)

/** Advanced screen, NETWORK group. */
data class NetworkStats(
    val arkServerStatus: String,
    val nextRound: String,
    val lightningBridge: String,
    val chainTip: Long,
)

/** Everything the Advanced screen shows, varying with the current health state. */
data class AdvancedStats(
    val funds: FundsStats,
    val network: NetworkStats,
)
