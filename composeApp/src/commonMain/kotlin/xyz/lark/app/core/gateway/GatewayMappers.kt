// TooManyFunctions: this file is deliberately a flat toolbox of small pure mapping functions
// (wire shapes → seam display models); splitting it further would scatter one concern.
@file:Suppress("TooManyFunctions")
@file:OptIn(ExperimentalTime::class) // kotlin.time Instant/Clock: stdlib-experimental, stable enough for M1

package xyz.lark.app.core.gateway

import xyz.lark.app.core.format.MoneyFormat
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

private const val MAX_PLAIN_NAME_LENGTH = 16
private const val NAME_PREFIX_LENGTH = 8
private const val NAME_SUFFIX_LENGTH = 4
private const val ELLIPSIS = "…"

/** Mirrors the demo's three recent-payee rows (send screen shows a short list, not a directory). */
private const val RECENTS_LIMIT = 3

private const val JUST_NOW_MINUTES = 2
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val YESTERDAY_HOURS = 48
private const val DAYS_PER_WEEK = 7
private const val LAST_WEEK_DAYS = 14

private val JUST_NOW_LIMIT = JUST_NOW_MINUTES.minutes
private val MINUTES_LIMIT = MINUTES_PER_HOUR.minutes
private val HOURS_LIMIT = HOURS_PER_DAY.hours
private val YESTERDAY_LIMIT = YESTERDAY_HOURS.hours
private val DAYS_LIMIT = DAYS_PER_WEEK.days
private val LAST_WEEK_LIMIT = LAST_WEEK_DAYS.days

// 10-minute blocks: the same back-of-envelope maths the design's expiry copy assumes.
private const val BLOCKS_PER_DAY = 144L
private const val BLOCKS_PER_HOUR = 6L

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

/** One expiry height in the block-countdown voice; [PLACEHOLDER] until height and tip are known. */
internal fun blockExpiryLabel(expiryHeight: Long?, tipHeight: Long): String =
    if (expiryHeight == null || tipHeight <= 0) {
        PLACEHOLDER
    } else {
        "block ${MoneyFormat.grouped(expiryHeight)} · ${expiryCountdown(expiryHeight - tipHeight)}"
    }

/**
 * The channels snapshot the seam exposes (plan U4): [totalLocalSat] is the gateway's own
 * channels/balance figure, never re-derived client-side.
 */
internal fun channelsSnapshot(
    channels: List<LightningChannelInfo>,
    totalLocalSat: Long,
    tipHeight: Long,
): ChannelsSnapshot = ChannelsSnapshot(
    channels = channels.map { channelDisplay(it, tipHeight) },
    totalLocalSat = totalLocalSat,
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

private fun expiryCountdown(blocks: Long): String = when {
    blocks <= 0 -> "expired"
    blocks < BLOCKS_PER_DAY -> "in " + counted(maxOf(1L, blocks / BLOCKS_PER_HOUR), "hour")
    else -> "in " + counted(blocks / BLOCKS_PER_DAY, "day")
}

/**
 * Relative display timestamp in the demo's voice ("2 hours ago", "Yesterday", "Last week").
 * Unparseable timestamps degrade to [PLACEHOLDER] rather than crashing an activity row.
 */
internal fun relativeTimeLabel(createdAt: String, now: Instant): String {
    val instant = parsedTime(createdAt) ?: return PLACEHOLDER
    val age = now - instant
    return when {
        age < JUST_NOW_LIMIT -> "Just now"
        age < MINUTES_LIMIT -> counted(age.inWholeMinutes, "minute") + " ago"
        age < HOURS_LIMIT -> counted(age.inWholeHours, "hour") + " ago"
        age < YESTERDAY_LIMIT -> "Yesterday"
        age < DAYS_LIMIT -> counted(age.inWholeDays, "day") + " ago"
        age < LAST_WEEK_LIMIT -> "Last week"
        else -> counted(age.inWholeDays / DAYS_PER_WEEK, "week") + " ago"
    }
}

private fun parsedTime(rfc3339: String): Instant? = runCatching { Instant.parse(rfc3339) }.getOrNull()

private fun counted(count: Long, unit: String): String = if (count == 1L) "1 $unit" else "$count ${unit}s"

/** Lightning addresses read as names; long ark/BOLT11 strings abbreviate to head…tail. */
private fun displayName(destination: String): String =
    if (destination.contains('@')) destination else abbreviated(destination)

/** Head…tail abbreviation for long identifiers (destinations, channel ids); short ones stay whole. */
private fun abbreviated(value: String): String = if (value.length <= MAX_PLAIN_NAME_LENGTH) {
    value
} else {
    value.take(NAME_PREFIX_LENGTH) + ELLIPSIS + value.takeLast(NAME_SUFFIX_LENGTH)
}

private fun initialOf(who: String): String = who.firstOrNull()?.uppercase() ?: "?"

