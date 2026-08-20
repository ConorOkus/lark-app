package xyz.lark.app.core

/**
 * An invoice already minted for one requested amount: when it was minted, and the balance the
 * wallet held at the time.
 *
 * The balance is what makes staleness a read rather than a write. An invoice that has been paid
 * cannot be paid again, and the wallet cannot ask whether one specific receive settled — but a
 * balance that has moved since the mint is enough to stop reusing it. Recording it here keeps the
 * check inside [reusableInvoice], instead of having the poll loop reach in and clear this field
 * under a different lock than the one the mint holds.
 *
 * Lives in `commonMain` rather than beside the core that holds it so [reusableInvoice] can be
 * tested: the in-process core is `iosMain`, which no test lane in this project can reach.
 */
internal data class CachedInvoice(
    val sats: Long,
    val bolt11: String,
    val mintedAtEpochSeconds: Long,
    val balanceAtMintSats: Long,
)
