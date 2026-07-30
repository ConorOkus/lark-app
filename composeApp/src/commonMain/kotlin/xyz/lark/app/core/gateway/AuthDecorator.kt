package xyz.lark.app.core.gateway

import io.ktor.client.request.HttpRequestBuilder

/**
 * The single seam where request authentication is applied; [BarkdApi] calls [decorate]
 * exactly once per outgoing request.
 *
 * The pending auth model drops in here: barkd 0.4.0 secures its API with a bearer token
 * (`Authorization: Bearer <base64url auth token>`), so the real decorator will set that
 * header on the builder. Until then [NoAuth] matches barkd's `--no-auth` mode.
 *
 * Implementations must never log the Authorization header or the token value.
 */
fun interface AuthDecorator {
    fun decorate(builder: HttpRequestBuilder)
}

/** No authentication (barkd running in `--no-auth` mode); decorates nothing. */
object NoAuth : AuthDecorator {
    override fun decorate(builder: HttpRequestBuilder) = Unit
}
