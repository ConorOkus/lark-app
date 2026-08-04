// TooManyFunctions: this file is deliberately a flat toolbox of small pure mapping functions
// (wire shapes → seam display models); splitting it further would scatter one concern.
@file:Suppress("TooManyFunctions")
@file:OptIn(ExperimentalTime::class) // kotlin.time Instant/Clock: stdlib-experimental, stable enough for M1

package xyz.lark.app.core.gateway

import xyz.lark.app.core.format.MoneyFormat
import xyz.lark.app.core.format.abbreviated
import xyz.lark.app.core.format.blockExpiryLabel
import xyz.lark.app.core.format.counted
import xyz.lark.app.core.format.displayName
import xyz.lark.app.core.format.initialOf
import xyz.lark.app.core.format.relativeTimeLabel
import xyz.lark.app.core.model.ChannelDisplay
import xyz.lark.app.core.model.ChannelState
import xyz.lark.app.core.model.ChannelsSnapshot
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.Transaction
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pure mappers from barkd wire shapes to the seam's display models.
 *
 * Every formatting decision the gateway core presents (relative timestamps, abbreviated
 * destinations, expiry countdowns) lives here so it is testable without a core instance.
 * Fields barkd does not expose render as [PLACEHOLDER] em-dashes, never fake numbers (plan R9).
 */

/** Em-dash placeholder for gateway data barkd 0.4.0 does not expose (plan R9). */
internal const val PLACEHOLDER = "—"

private const val BIP321_SCHEME = "bitcoin:"
private const val ARK_PARAM = "ark"
private const val LIGHTNING_PARAM = "lightning"

/** Movements shown in activity; `failed`/`canceled` attempts stay out of the money timeline. */
private val ACTIVITY_STATUSES = setOf("pending", "successful")
private const val STATUS_SUCCESSFUL = "successful"


/** Mirrors the demo's three recent-payee rows (send screen shows a short list, not a directory). */
private const val RECENTS_LIMIT = 3



// 10-minute blocks: the same back-of-envelope maths the design's expiry copy assumes.
private const val TEN_MINUTE_BLOCK_SECONDS = 600

/** msat → whole sats for channel balances (KTD-6: money is integer sats end-to-end). */
private const val MSATS_PER_SAT = 1_000L

/**
 * Resolves what the caller hands `send` into the destination string `POST /wallet/send` accepts.
 *
 * `bitcoin:` BIP-321 URIs prefer the `ark` param, falling back to the `lightning` (BOLT11)
 * param; a bare onchain-only URI has no offchain destination this milestone → null, which the
 * core maps to a send failure. Anything else (ark address, BOLT11, LNURL, lightning address)
 * passes through untouched — barkd routes raw destination strings itself.
 */
internal fun resolveSendDestination(recipient: String): String? {
    val trimmed = recipient.trim()
    return when {
        trimmed.isEmpty() -> null
        !trimmed.startsWith(BIP321_SCHEME, ignoreCase = true) -> trimmed
        else -> bip321OffchainDestination(trimmed)
    }
}

private fun bip321OffchainDestination(uri: String): String? {
    val params = uri.substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .filter { it.contains('=') }
        .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
    return params[ARK_PARAM] ?: params[LIGHTNING_PARAM]
}

/**
 * The fork receive URI (plan U3): the fork has no bip321 endpoint, so the app embeds the
 * freshly minted `addresses/next` address in a `bitcoin:?ark=` URI itself. Guarded by a
 * pragmatic bech32 shape check — a lowercase alphanumeric human-readable part, the `1`
 * separator, then bech32-charset data — not full bech32m verification: its job is keeping
 * URI-breaking or plainly non-bech32 strings out of the receive code. Anything failing the
 * check returns null, the no-receive-code state.
 */
internal fun arkReceiveUri(address: String): String? =
    if (ARK_ADDRESS_SHAPE.matches(address)) "$BIP321_SCHEME?$ARK_PARAM=$address" else null

/**
 * Whether [value] has the bech32 shape an Ark address (or a BOLT12 offer) uses: a lowercase
 * human-readable part, the `1` separator, then bech32 data. Shape only — not a checksum check.
 */
internal fun isBech32Shaped(value: String): Boolean = ARK_ADDRESS_SHAPE.matches(value.lowercase())

/**
 * Adds a `lightning=` destination to an ark receive URI, so one code serves an Ark wallet and a
 * Lightning-only wallet. `ark` stays first because the app's own send-side parser prefers it.
 *
 * A [bolt11] that could break the URI is dropped rather than embedded: the code stays ark-only,
 * which is degraded but still scannable — the same rule [arkReceiveUri] applies to addresses.
 */
internal fun withLightningInvoice(arkUri: String, bolt11: String): String =
    if (BOLT11_URI_SAFE.matches(bolt11)) "$arkUri&$LIGHTNING_PARAM=$bolt11" else arkUri

/** BOLT11 is bech32 (either case), so anything with URI syntax in it is not an invoice. */
private val BOLT11_URI_SAFE = Regex("[a-zA-Z0-9]+")

/** The 32 characters bech32/bech32m data may use (no `1`, `b`, `i`, `o`). */
private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

private val ARK_ADDRESS_SHAPE = Regex("[a-z][a-z0-9]*1[$BECH32_CHARSET]+")

/**
 * Maps gateway history to activity rows, newest first. Amounts are the movement's signed
 * balance delta: the effective (fee-inclusive) figure once successful, the intended figure
 * while still pending — always what the balance actually saw or will see.
 */
internal fun activityFromMovements(orderedMovements: List<Movement>, now: Instant): List<Transaction> =
    orderedMovements.map { transactionOf(it, now) }

/**
 * Shared ordering rule for the history-derived views: displayable statuses, newest first.
 * Compute once per history snapshot and feed both [activityFromMovements] and
 * [recentsFromMovements] — the sort's time-parsing is the expensive part.
 */
internal fun movementsNewestFirst(movements: List<Movement>): List<Movement> =
    movements
        .filter { it.status in ACTIVITY_STATUSES }
        .sortedByDescending { parsedTime(it.time.createdAt) ?: Instant.DISTANT_PAST }

private fun transactionOf(movement: Movement, now: Instant): Transaction {
    val sats = if (movement.status == STATUS_SUCCESSFUL) {
        movement.effectiveBalanceSat
    } else {
        movement.intendedBalanceSat
    }
    val counterparty = if (sats < 0) movement.sentTo.firstOrNull() else movement.receivedOn.firstOrNull()
    val who = counterparty?.destination?.value?.let(::displayName)
        ?: if (sats < 0) "Sent" else "Received"
    return Transaction(
        who = who,
        whenLabel = relativeTimeLabel(movement.time.createdAt, now),
        sats = sats,
        initial = initialOf(who),
    )
}

/**
 * Recent payees derived from history `sent_to` entries: deduplicated by destination,
 * most recent first, capped at the demo's three rows. Empty history → empty list.
 * [Contact.handle] keeps the full destination string — the send flow feeds it back to `send`.
 */
internal fun recentsFromMovements(orderedMovements: List<Movement>): List<Contact> =
    orderedMovements
        .flatMap { it.sentTo }
        .map { it.destination.value }
        .distinct()
        .take(RECENTS_LIMIT)
        .map { destination ->
            val name = displayName(destination)
            Contact(who = name, handle = destination, initial = initialOf(name))
        }

/** Splits the gateway's space-separated BIP-39 phrase into words; whitespace-tolerant. */
internal fun splitMnemonicWords(mnemonic: String): List<String> =
    mnemonic.trim().split(WHITESPACE).filter { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")

/**
 * The Advanced screen's soonest-expiry line, mirroring the demo's format:
 * `block 918,402 · in 27 days` (10-minute-block countdown). [PLACEHOLDER] until both the
 * VTXO list and the chain tip have arrived.
 */
internal fun soonestExpiryLabel(vtxos: List<WalletVtxoInfo>, tipHeight: Long): String =
    blockExpiryLabel(vtxos.minOfOrNull { it.expiryHeight }, tipHeight)

/**
 * One expiry height in the block-countdown voice; [PLACEHOLDER] until height and tip are known.
 *
 * Still assumes 10-minute blocks, which is what the design's expiry copy was written against and
 * what this suite pins. That is wrong on mutinynet — the countdown reads ~20x longer than reality —
 * but it is a pre-existing property of the gateway core, not something to change silently underneath
 * its tests. The in-process core passes the real spacing (see the shared helper).
 */
internal fun blockExpiryLabel(expiryHeight: Long?, tipHeight: Long): String =
    blockExpiryLabel(expiryHeight, tipHeight, secondsPerBlock = TEN_MINUTE_BLOCK_SECONDS)

/**
 * The channels snapshot the seam exposes (plan U4). The bridge total the UI shows is the sum
 * of the rows' local balances (R7: the rows sum to the total), so the snapshot carries only
 * the rows — the daemon's separate usable-only aggregate is deliberately not fetched.
 */
internal fun channelsSnapshot(
    channels: List<LightningChannelInfo>,
    tipHeight: Long,
): ChannelsSnapshot = ChannelsSnapshot(
    channels = channels.map { channelDisplay(it, tipHeight) },
)

/**
 * One channel's display row: usable wins, a not-yet-ready funding reads as opening, and
 * ready-but-unusable is honestly unusable. `force_close_spend_delay` is a static CSV
 * parameter, deliberately not displayed (plan U4 scope boundary).
 */
internal fun channelDisplay(channel: LightningChannelInfo, tipHeight: Long): ChannelDisplay = ChannelDisplay(
    shortId = abbreviated(channel.channelId),
    localSat = channel.localBalanceMsat / MSATS_PER_SAT,
    capacitySat = channel.capacitySat,
    state = channelState(channel),
    expiryLabel = blockExpiryLabel(channel.expiryHeight, tipHeight),
)

private fun channelState(channel: LightningChannelInfo): ChannelState = when {
    channel.isUsable -> ChannelState.USABLE
    !channel.isChannelReady -> ChannelState.OPENING
    else -> ChannelState.UNUSABLE
}

/**
 * Relative display timestamp in the demo's voice ("2 hours ago", "Yesterday", "Last week").
 * Unparseable timestamps degrade to [PLACEHOLDER] rather than crashing an activity row.
 */
internal fun relativeTimeLabel(createdAt: String, now: Instant): String {
    val instant = parsedTime(createdAt) ?: return PLACEHOLDER
    return relativeTimeLabel(instant, now)
}

private fun parsedTime(rfc3339: String): Instant? = runCatching { Instant.parse(rfc3339) }.getOrNull()

