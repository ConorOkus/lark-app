package xyz.lark.app.core.gateway

/**
 * The outcome of one barkd call. Every [BarkdApi] function returns one of these three
 * cases — no Ktor or serialization exception escapes the client.
 */
sealed interface BarkdResult<out T> {

    /** barkd answered 2xx and the body decoded against the pinned contract. */
    data class Ok<out T>(val value: T) : BarkdResult<T>

    /**
     * barkd answered, but not usefully: either a non-2xx [status] with the raw error [body],
     * or a 2xx whose body failed to decode against the pinned 0.4.0 contract — in that case
     * [status] is the actual HTTP status and [body] carries a `contract error: …` description.
     */
    data class HttpError(val status: Int, val body: String) : BarkdResult<Nothing> {

        /** True when barkd demands authentication (401) or rejected our token (403). */
        val isAuthRequired: Boolean
            get() = status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN

        private companion object {
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_FORBIDDEN = 403
        }
    }

    /** The gateway could not be reached at all (connect failure, timeout, DNS, TLS). */
    data class Unreachable(val message: String) : BarkdResult<Nothing>
}
