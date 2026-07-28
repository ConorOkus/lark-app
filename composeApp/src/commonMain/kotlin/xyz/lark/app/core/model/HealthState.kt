package xyz.lark.app.core.model

/** Home indicator: the word and dot next to the LARK wordmark (plus the pill variant's label). */
data class HealthIndicator(
    val word: String,
    val pill: String,
    val dotColorHex: String,
)

/** Wallet-status screen copy; [actionLabel] is null when the state needs nothing from the user. */
data class HealthStatusCopy(
    val title: String,
    val body: String,
    val actionLabel: String?,
)

/** Home attention banner copy (stale/offline only). */
data class BannerCopy(
    val title: String,
    val body: String,
)

/** Everything a health state displays, verbatim from the design prototype's HEALTH map. */
data class HealthDisplay(
    val indicator: HealthIndicator,
    val status: HealthStatusCopy,
    val banner: BannerCopy?,
    val aspStatus: String,
)

/**
 * The four wallet health states of the prototype (ready / tidying / stale / offline).
 * Tidying deliberately presents as "Ready" — refresh is silent; the wallet only speaks when stale.
 */
enum class HealthState(val display: HealthDisplay) {
    READY(
        HealthDisplay(
            indicator = HealthIndicator(word = "Ready", pill = "READY", dotColorHex = "#6FE3A8"),
            status = HealthStatusCopy(
                title = "Everything’s ready.",
                body = "Your money is spendable right now, and LARK keeps it that way on its own. " +
                    "Nothing for you to do.",
                actionLabel = null,
            ),
            banner = null,
            aspStatus = "Connected",
        ),
    ),
    TIDYING(
        HealthDisplay(
            indicator = HealthIndicator(word = "Ready", pill = "READY", dotColorHex = "#6FE3A8"),
            status = HealthStatusCopy(
                title = "Everything’s ready.",
                body = "Your money is spendable right now. LARK is quietly reorganising it in the " +
                    "background — you’ll never notice, and you can spend the whole time.",
                actionLabel = null,
            ),
            banner = null,
            aspStatus = "Connected",
        ),
    ),
    STALE(
        HealthDisplay(
            indicator = HealthIndicator(word = "Needs a moment", pill = "NEEDS YOU", dotColorHex = "#FF7A4D"),
            status = HealthStatusCopy(
                title = "Open me more often.",
                body = "LARK has been closed a long time. One tap puts your money back on solid " +
                    "ground — takes about 30 seconds.",
                actionLabel = "Get it done",
            ),
            banner = BannerCopy(
                title = "Your wallet needs a moment",
                body = "One tap keeps everything spendable.",
            ),
            aspStatus = "Connected",
        ),
    ),
    OFFLINE(
        HealthDisplay(
            indicator = HealthIndicator(word = "Offline", pill = "OFFLINE", dotColorHex = "#FF7A4D"),
            status = HealthStatusCopy(
                title = "Can’t reach the network.",
                body = "Your money is safe and still yours — LARK just can’t send or receive " +
                    "until the connection is back. It keeps trying.",
                actionLabel = "Try again now",
            ),
            banner = BannerCopy(
                title = "Can’t reach the network",
                body = "Your money is safe. Payments resume when it’s back.",
            ),
            aspStatus = "Unreachable",
        ),
    ),
}
