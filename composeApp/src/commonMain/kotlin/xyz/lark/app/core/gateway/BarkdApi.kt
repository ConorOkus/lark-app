package xyz.lark.app.core.gateway

import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Typed client for the barkd REST API, pinned to the vendored 0.4.0 contract
 * (docs/gateway/barkd-openapi-0.4.0.json; summary in docs/gateway/barkd-api.md).
 *
 * Every call decorates the request through [auth] (exactly once, in [call]), and returns a
 * [BarkdResult] — no Ktor or serialization exception escapes this class. Unknown JSON keys
 * are ignored so additive server changes do not break the app.
 *
 * Deliberately NO logging plugin is installed: responses carry the mnemonic and requests
 * will carry the Authorization header, and neither may ever reach a log.
 *
 * @param baseUrl scheme://host:port of the gateway, without a trailing slash.
 */
@Suppress("TooManyFunctions") // one typed function per barkd endpoint in the U2 subset, by design
class BarkdApi(
    engine: HttpClientEngine,
    private val baseUrl: String,
    private val auth: AuthDecorator = NoAuth,
) {

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(BarkdJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
    }

    /** Unauthenticated reachability probe; the only endpoint not under `/api/v1`. */
    suspend fun ping(): BarkdResult<Unit> =
        call(HttpMethod.Get, "/ping")

    suspend fun walletExists(): BarkdResult<WalletExistsResponse> =
        call(HttpMethod.Get, "/api/v1/wallet")

    suspend fun balance(): BarkdResult<Balance> =
        call(HttpMethod.Get, "/api/v1/wallet/balance")

    /** Non-spent VTXOs (the spec's default when the `all` query flag is omitted). */
    suspend fun vtxos(): BarkdResult<List<WalletVtxoInfo>> =
        call(HttpMethod.Get, "/api/v1/wallet/vtxos")

    suspend fun history(): BarkdResult<List<Movement>> =
        call(HttpMethod.Get, "/api/v1/history")

    suspend fun bip321(request: Bip321UriRequest): BarkdResult<Bip321UriResponse> =
        call(HttpMethod.Post, "/api/v1/wallet/bip321") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun send(request: SendRequest): BarkdResult<SendResponse> =
        call(HttpMethod.Post, "/api/v1/wallet/send") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun refreshAll(): BarkdResult<PendingRoundInfo> =
        call(HttpMethod.Post, "/api/v1/wallet/refresh/all")

    suspend fun createWallet(request: CreateWalletRequest): BarkdResult<CreateWalletResponse> =
        call(HttpMethod.Post, "/api/v1/wallet/create") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    /** Response carries the backup phrase; see the class KDoc — never log this body. */
    suspend fun mnemonic(): BarkdResult<MnemonicResponse> =
        call(HttpMethod.Get, "/api/v1/wallet/mnemonic")

    /**
     * Long-polls for wallet notifications created after [since] (RFC 3339; null returns the
     * whole buffer). Uses a longer request timeout than the other calls to cover the
     * server-side poll window.
     */
    suspend fun waitNotifications(since: String? = null): BarkdResult<WaitNotificationResponse> =
        call(HttpMethod.Get, "/api/v1/notifications/wait") {
            parameter("since", since)
            timeout { requestTimeoutMillis = LONG_POLL_TIMEOUT_MILLIS }
        }

    suspend fun connected(): BarkdResult<ConnectedResponse> =
        call(HttpMethod.Get, "/api/v1/wallet/connected")

    suspend fun tip(): BarkdResult<TipResponse> =
        call(HttpMethod.Get, "/api/v1/bitcoin/tip")

    suspend fun arkInfo(): BarkdResult<ArkInfo> =
        call(HttpMethod.Get, "/api/v1/wallet/ark-info")

    /**
     * The single request path: applies [auth], sends, and maps the outcome to [BarkdResult].
     * Cancellation is rethrown; anything else the engine throws means barkd was never
     * usefully reached, so it maps to [BarkdResult.Unreachable].
     */
    @Suppress("TooGenericExceptionCaught") // boundary contract: no engine exception may escape BarkdApi
    private suspend inline fun <reified T> call(
        method: HttpMethod,
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): BarkdResult<T> = try {
        val response = client.request(baseUrl + path) {
            this.method = method
            configure()
            auth.decorate(this)
        }
        if (response.status.isSuccess()) {
            decode(response)
        } else {
            BarkdResult.HttpError(response.status.value, response.bodyAsText())
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (cause: Exception) {
        BarkdResult.Unreachable(cause.message ?: cause::class.simpleName ?: "barkd unreachable")
    }

    /** A 2xx body that fails to decode is a contract violation, reported as [BarkdResult.HttpError]. */
    private suspend inline fun <reified T> decode(response: HttpResponse): BarkdResult<T> = try {
        BarkdResult.Ok(response.body<T>())
    } catch (cause: ContentConvertException) {
        contractError(response, cause)
    } catch (cause: SerializationException) {
        contractError(response, cause)
    } catch (cause: NoTransformationFoundException) {
        contractError(response, cause)
    }

    private fun contractError(response: HttpResponse, cause: Exception): BarkdResult.HttpError =
        BarkdResult.HttpError(
            status = response.status.value,
            body = "contract error: 2xx body failed to decode against barkd 0.4.0: ${cause.message}",
        )

    private companion object {
        val BarkdJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        // Mobile-tuned: fail fast on dead networks, allow slow rounds to answer.
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val SOCKET_TIMEOUT_MILLIS = 35_000L

        // notifications/wait is a long poll; give the server window generous headroom.
        const val LONG_POLL_TIMEOUT_MILLIS = 120_000L
    }
}
