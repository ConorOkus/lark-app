@file:OptIn(ExperimentalTime::class) // kotlin.time Instant: stdlib-experimental, stable enough for M2

package xyz.lark.app.core.ffi

import xyz.lark.app.core.format.displayName
import xyz.lark.app.core.format.initialOf
import xyz.lark.app.core.format.relativeTimeLabel
import xyz.lark.app.core.model.Contact
import xyz.lark.app.core.model.Transaction
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pure mappers from the Rust core's shapes to the seam's display models.
 *
 * Deliberately the mirror of `GatewayMappers`: same rules, same vocabulary, different wire shape,
 * and testable without a core or a device. The shared text helpers live in `core.format` precisely
 * so "2 hours ago" cannot mean two things depending on which engine is running.
 */

/** A movement that failed or was cancelled is not part of the money timeline. */
private val ACTIVITY_STATUSES = setOf(FfiMovementState.PENDING, FfiMovementState.SUCCESSFUL)

/** Mirrors the demo's three recent-payee rows. */
private const val RECENTS_LIMIT = 3

/** Displayable movements, newest first — the shared ordering both views below expect. */
internal fun ffiMovementsNewestFirst(movements: List<FfiMovement>): List<FfiMovement> =
    movements
        .filter { it.status in ACTIVITY_STATUSES }
        .sortedByDescending { it.createdAtEpochSeconds }

/**
 * Activity rows from wallet history, newest first.
 *
 * The signed amount is the effective (fee-inclusive) balance change once a movement is successful,
 * and the intended change while it is still pending — a pending row has no effective balance yet,
 * so showing it would render every in-flight payment as 0.
 */
internal fun ffiActivity(orderedMovements: List<FfiMovement>, now: Instant): List<Transaction> =
    orderedMovements.map { movement ->
        val sats = if (movement.status == FfiMovementState.SUCCESSFUL) {
            movement.effectiveBalanceSat
        } else {
            movement.intendedBalanceSat
        }
        // Outbound movements name recipients, inbound ones name how the money arrived. A board or
        // a refresh names neither, and falls back to the direction.
        val counterparty = if (sats < 0) movement.sentTo.firstOrNull() else movement.receivedOn.firstOrNull()
        val who = counterparty?.let(::displayName) ?: if (sats < 0) "Sent" else "Received"
        Transaction(
            who = who,
            whenLabel = relativeTimeLabel(Instant.fromEpochSeconds(movement.createdAtEpochSeconds), now),
            sats = sats,
            initial = initialOf(who),
            pending = movement.status == FfiMovementState.PENDING,
        )
    }

/**
 * Recent payees from history, deduplicated, most recent first, capped at three.
 * [Contact.handle] keeps the full destination so the send flow can pay it back.
 */
internal fun ffiRecents(orderedMovements: List<FfiMovement>): List<Contact> =
    orderedMovements
        .flatMap { it.sentTo }
        .distinct()
        .take(RECENTS_LIMIT)
        .map { destination ->
            val name = displayName(destination)
            Contact(who = name, handle = destination, initial = initialOf(name))
        }

/**
 * Whether [recipient] is an Ark address rather than a Lightning invoice, i.e. which send the core
 * should use.
 *
 * The two Rust sends are separate calls, so something has to choose, and the choice is made on the
 * destination's own prefix rather than by trying one and falling back — a failed Lightning attempt
 * is not free.
 */
internal fun isArkDestination(recipient: String): Boolean {
    val trimmed = recipient.trim().lowercase()
    return ARK_ADDRESS_PREFIXES.any { trimmed.startsWith(it) }
}

/** Ark address HRPs: mainnet, then the signet/mutinynet and regtest forms. */
private val ARK_ADDRESS_PREFIXES = listOf("ark1", "tark1")
