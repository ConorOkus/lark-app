package xyz.lark.app.core.gateway

/**
 * Minimal BOLT11 human-readable-part reader (plan KTD-5).
 *
 * The fork's `ldk-pay` takes an invoice and no amount, so the invoice's own amount is what the
 * channel actually pays. Routing therefore has to know that amount *before* choosing the
 * channel path, or the review screen could promise one figure while a different one leaves
 * (plan R2). barkd exposes no invoice-decoding endpoint, and pulling in a Lightning library to
 * read one prefix is not worth the dependency on every target — so the app reads the
 * human-readable part itself.
 *
 * Scope is deliberately tiny: the network prefix and the amount, up to the bech32 `1`
 * separator. No signature check, no data-part decode, no expiry. Anything this cannot read
 * with confidence — including an amount too small to express in whole satoshis — is
 * [Bolt11.Unrecognized], which routes the send to the Ark path instead of guessing.
 */
internal sealed interface Bolt11 {

    /** A readable invoice that names its own amount, already converted to whole sats. */
    data class WithAmount(val network: Bolt11Network, val amountSat: Long) : Bolt11

    /** A readable invoice that names no amount — the payer chooses, so `ldk-pay` cannot serve it. */
    data class Amountless(val network: Bolt11Network) : Bolt11

    /** Not confidently readable as an invoice, or an amount this app refuses to round. */
    data object Unrecognized : Bolt11
}

/** The chains a BOLT11 human-readable part can name, by its BIP-173 currency prefix. */
internal enum class Bolt11Network(val hrpPrefix: String) {
    MAINNET("bc"),
    TESTNET("tb"),
    SIGNET("tbs"),
    REGTEST("bcrt"),
}

/**
 * Maps the app's own network vocabulary ([xyz.lark.app.core.CoreConfig.expectedNetwork]) onto a
 * [Bolt11Network], or null when the name is not one the app knows. Mutinynet is a signet
 * variant, so it shares signet's invoice prefix.
 */
internal fun bolt11NetworkOf(expectedNetwork: String): Bolt11Network? =
    when (expectedNetwork.trim().lowercase()) {
        "signet", "mutinynet" -> Bolt11Network.SIGNET
        "bitcoin", "mainnet" -> Bolt11Network.MAINNET
        "testnet" -> Bolt11Network.TESTNET
        "regtest" -> Bolt11Network.REGTEST
        else -> null
    }

/** Reads what [destination]'s human-readable part says, or [Bolt11.Unrecognized]. */
internal fun parseBolt11(destination: String): Bolt11 {
    val text = destination.trim().lowercase()
    if (!text.startsWith(INVOICE_SCHEME)) return Bolt11.Unrecognized

    // The bech32 data charset excludes '1', so the last '1' is always the separator.
    val separator = text.lastIndexOf(BECH32_SEPARATOR)
    if (separator <= INVOICE_SCHEME.length || separator == text.lastIndex) return Bolt11.Unrecognized

    val afterScheme = text.substring(INVOICE_SCHEME.length, separator)
    // Longest prefix first: `tb` prefixes `tbs` and `bc` prefixes `bcrt`, so a shortest match
    // would read every signet invoice as testnet and treat a wrong-network send as payable.
    val network = Bolt11Network.entries
        .sortedByDescending { it.hrpPrefix.length }
        .firstOrNull { afterScheme.startsWith(it.hrpPrefix) }
        ?: return Bolt11.Unrecognized

    val amountPart = afterScheme.substring(network.hrpPrefix.length)
    if (amountPart.isEmpty()) return Bolt11.Amountless(network)

    val amountSat = amountSatOf(amountPart) ?: return Bolt11.Unrecognized
    return Bolt11.WithAmount(network, amountSat)
}

/**
 * Converts a human-readable amount (`<digits>[multiplier]`) to whole sats, or null when it is
 * malformed, overflows, or is finer than one satoshi. Sub-satoshi amounts are refused rather
 * than rounded: either direction would misstate money the user is about to send.
 */
private fun amountSatOf(amountPart: String): Long? {
    val hasMultiplier = !amountPart.last().isDigit()
    val digits = if (hasMultiplier) amountPart.dropLast(1) else amountPart
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return null

    val amount = digits.toLongOrNull() ?: return null // 20+ digits overflow to null here
    val milliSats = if (hasMultiplier) {
        multipliedToMilliSats(amount, amountPart.last()) ?: return null
    } else {
        amount.timesOrNull(MSAT_PER_BTC) ?: return null
    }

    return if (milliSats % MSAT_PER_SAT == 0L) milliSats / MSAT_PER_SAT else null
}

/** BOLT11's four multipliers, as millisats. `p` is finer than a millisat, so it divides. */
private fun multipliedToMilliSats(amount: Long, multiplier: Char): Long? = when (multiplier) {
    'm' -> amount.timesOrNull(MSAT_PER_BTC / 1_000L)
    'u' -> amount.timesOrNull(MSAT_PER_BTC / 1_000_000L)
    'n' -> amount.timesOrNull(MSAT_PER_BTC / 1_000_000_000L)
    // 10 pico-BTC is one millisat; anything finer is not expressible on the wire at all.
    'p' -> if (amount % PICO_PER_MSAT == 0L) amount / PICO_PER_MSAT else null
    else -> null
}

/** Multiplication that reports overflow as null instead of silently wrapping to a wrong amount. */
private fun Long.timesOrNull(factor: Long): Long? =
    if (this > Long.MAX_VALUE / factor) null else this * factor

private const val INVOICE_SCHEME = "ln"
private const val BECH32_SEPARATOR = '1'
private const val MSAT_PER_SAT = 1_000L
private const val MSAT_PER_BTC = 100_000_000_000L
private const val PICO_PER_MSAT = 10L
