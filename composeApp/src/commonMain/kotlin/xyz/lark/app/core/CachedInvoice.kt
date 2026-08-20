package xyz.lark.app.core

/**
 * An invoice already minted for one requested amount, and when it was minted.
 *
 * Lives here rather than beside the core that holds it so [reusableInvoice] can be tested: the
 * in-process core is `iosMain`, which no test lane in this project can reach.
 */
internal data class CachedInvoice(
    val sats: Long,
    val bolt11: String,
    val mintedAtEpochSeconds: Long,
)
