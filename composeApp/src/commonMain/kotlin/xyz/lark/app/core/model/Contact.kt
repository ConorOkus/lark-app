package xyz.lark.app.core.model

/** A recent payee: display name plus the handle a payment resolves to. */
data class Contact(
    val who: String,
    val handle: String,
    val initial: String,
)
