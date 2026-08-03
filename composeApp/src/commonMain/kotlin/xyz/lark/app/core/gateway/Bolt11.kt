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
    val hrp = humanReadablePart(destination)
    val network = hrp?.let(::networkOf)
    return if (hrp == null || network == null) {
        Bolt11.Unrecognized
    } else {
        readAmount(network, hrp.substring(network.hrpPrefix.length))
    }
}

/**
 * The part before the bech32 separator, minus the `ln` scheme — or null when [destination] is
 * not shaped like an invoice at all. The bech32 data charset excludes `1`, so the LAST `1` is
 * always the separator, whatever the amount contains.
 */
private fun humanReadablePart(destination: String): String? {
    val text = destination.trim().lowercase()
    val separator = text.lastIndexOf(BECH32_SEPARATOR)
    val wellFormed = text.startsWith(INVOICE_SCHEME) &&
        separator > INVOICE_SCHEME.length &&
        separator != text.lastIndex // an empty data part is not an invoice
    return if (wellFormed) text.substring(INVOICE_SCHEME.length, separator) else null
}

/**
 * Longest prefix wins: `tb` prefixes `tbs` and `bc` prefixes `bcrt`, so a shortest match would
 * read every signet invoice as testnet and treat a wrong-network send as payable.
 */
private fun networkOf(hrp: String): Bolt11Network? = Bolt11Network.entries
    .sortedByDescending { it.hrpPrefix.length }
    .firstOrNull { hrp.startsWith(it.hrpPrefix) }

private fun readAmount(network: Bolt11Network, amountPart: String): Bolt11 = when {
    amountPart.isEmpty() -> Bolt11.Amountless(network)
    else -> amountSatOf(amountPart)?.let { Bolt11.WithAmount(network, it) } ?: Bolt11.Unrecognized
}

/**
 * Converts a human-readable amount (`<digits>[multiplier]`) to whole sats, or null when it is
 * malformed, overflows, or is finer than one satoshi. Sub-satoshi amounts are refused rather
 * than rounded: either direction would misstate money the user is about to send.
 */
private fun amountSatOf(amountPart: String): Long? {
    val hasMultiplier = !amountPart.last().isDigit()
    val digits = if (hasMultiplier) amountPart.dropLast(1) else amountPart
    // toLongOrNull also absorbs 20+ digit amounts, which cannot fit a Long at all.
    val amount = digits.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
    val milliSats = amount?.let {
        if (hasMultiplier) multipliedToMilliSats(it, amountPart.last()) else it.timesOrNull(MSAT_PER_BTC)
    }
    return milliSats?.takeIf { it % MSAT_PER_SAT == 0L }?.div(MSAT_PER_SAT)
}

/** BOLT11's four multipliers, as millisats. `p` is finer than a millisat, so it divides. */
private fun multipliedToMilliSats(amount: Long, multiplier: Char): Long? = when (multiplier) {
    'm' -> amount.timesOrNull(MSAT_PER_MILLI_BTC)
    'u' -> amount.timesOrNull(MSAT_PER_MICRO_BTC)
    'n' -> amount.timesOrNull(MSAT_PER_NANO_BTC)
    // 10 pico-BTC is one millisat; anything finer is not expressible on the wire at all.
    'p' -> if (amount % PICO_BTC_PER_MSAT == 0L) amount / PICO_BTC_PER_MSAT else null
    else -> null
}

/** Multiplication that reports overflow as null instead of silently wrapping to a wrong amount. */
private fun Long.timesOrNull(factor: Long): Long? =
    if (this > Long.MAX_VALUE / factor) null else this * factor

private const val INVOICE_SCHEME = "ln"
private const val BECH32_SEPARATOR = '1'
private const val MSAT_PER_SAT = 1_000L

// One BTC is 100,000,000,000 msat; each multiplier is a decimal step down from it.
private const val MSAT_PER_BTC = 100_000_000_000L
private const val MSAT_PER_MILLI_BTC = 100_000_000L
private const val MSAT_PER_MICRO_BTC = 100_000L
private const val MSAT_PER_NANO_BTC = 100L
private const val PICO_BTC_PER_MSAT = 10L
