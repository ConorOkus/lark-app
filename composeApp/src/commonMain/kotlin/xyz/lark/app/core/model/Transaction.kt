package xyz.lark.app.core.model

/**
 * One activity row. [sats] is signed: negative = sent, positive = received.
 * [whenLabel] is the display timestamp ("2 hours ago", "Yesterday", …).
 */
data class Transaction(
    val who: String,
    val whenLabel: String,
    val sats: Long,
    val initial: String,
) {
    val isSent: Boolean get() = sats < 0
}
