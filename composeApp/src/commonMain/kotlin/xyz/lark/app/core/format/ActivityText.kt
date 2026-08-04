@file:OptIn(ExperimentalTime::class) // kotlin.time Instant: stdlib-experimental, stable enough for M1

package xyz.lark.app.core.format

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How an activity row reads: the relative timestamp, the counterparty name, the avatar initial.
 *
 * Lives here rather than beside a single core because every core produces the same rows from a
 * different wire shape — the gateway from barkd movements, the in-process core from bark's own
 * history — and two implementations of "2 hours ago" would drift. The wording is the design
 * prototype's, and the gateway suite's assertions on it are the ones that pin it.
 */

private const val MAX_PLAIN_NAME_LENGTH = 16
private const val NAME_PREFIX_LENGTH = 8
private const val NAME_SUFFIX_LENGTH = 4
private const val ELLIPSIS = "…"

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

/** The prototype's relative-time vocabulary: "Just now", "45 minutes ago", "Yesterday", … */
internal fun relativeTimeLabel(createdAt: Instant, now: Instant): String {
    val age = now - createdAt
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

/** Pluralises a count for display copy ("1 hour", "3 hours"). */
internal fun counted(count: Long, unit: String): String = if (count == 1L) "1 $unit" else "$count ${unit}s"

/** Lightning addresses read as names; long ark/BOLT11 strings abbreviate to head…tail. */
internal fun displayName(destination: String): String =
    if (destination.contains('@')) destination else abbreviated(destination)

/** Head…tail abbreviation for long identifiers (destinations, channel ids); short ones stay whole. */
internal fun abbreviated(value: String): String = if (value.length <= MAX_PLAIN_NAME_LENGTH) {
    value
} else {
    value.take(NAME_PREFIX_LENGTH) + ELLIPSIS + value.takeLast(NAME_SUFFIX_LENGTH)
}

/** The avatar letter for a counterparty name. */
internal fun initialOf(who: String): String = who.firstOrNull()?.uppercase() ?: "?"
