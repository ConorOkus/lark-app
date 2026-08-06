package xyz.lark.app.core.model

/**
 * One activity row. [sats] is signed: negative = sent, positive = received.
 * [whenLabel] is the display timestamp ("2 hours ago", "Yesterday", …).
 *
 * [pending] means accepted but not yet complete. Carrying it matters because the activity list has
 * always included in-flight movements — it just had nowhere to say so, and rendered them as though
 * they had landed. An incoming deposit is the case where that misleads most: the row claims money
 * the user cannot yet spend.
 */
data class Transaction(
    val who: String,
    val whenLabel: String,
    val sats: Long,
    val initial: String,
    val pending: Boolean = false,
) {
    val isSent: Boolean get() = sats < 0
}
