@file:OptIn(ExperimentalTime::class)

package xyz.lark.app.core.gateway

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

internal const val GATEWAY_BASE_URL = "http://barkd.test"

/** Frozen wall clock for deterministic relative-time labels (fixtures are timestamped 10:00Z). */
internal val FIXED_NOW: Instant = Instant.parse("2026-07-28T12:00:00Z")

/** Endpoint paths the gateway core touches, for scripting and request-count assertions. */
internal object Paths {
    const val PING = "/ping"
    const val WALLET = "/api/v1/wallet"
    const val BALANCE = "/api/v1/wallet/balance"
    const val VTXOS = "/api/v1/wallet/vtxos"
    const val HISTORY = "/api/v1/history"
    const val BIP321 = "/api/v1/wallet/bip321"
    const val SEND = "/api/v1/wallet/send"
    const val REFRESH = "/api/v1/wallet/refresh/all"
    const val CREATE = "/api/v1/wallet/create"
    const val MNEMONIC = "/api/v1/wallet/mnemonic"
    const val WAIT = "/api/v1/notifications/wait"
    const val CONNECTED = "/api/v1/wallet/connected"
    const val TIP = "/api/v1/bitcoin/tip"
    const val ARK_INFO = "/api/v1/wallet/ark-info"
    const val NEXT_ADDRESS = "/api/v1/wallet/addresses/next"
    const val CHANNELS = "/api/v1/lightning/channels"
    const val CHANNELS_BALANCE = "/api/v1/lightning/channels/balance"
}

/** Spec fixtures adjusted so the default script describes a healthy mutinynet wallet. */
internal object HealthyFixtures {
    /** Tip 916,214 + expiry delta 12,960: expiry at 929,174 keeps the STALE predicate quiet. */
    val VTXOS_FAR_EXPIRY = BarkdFixtures.VTXOS.replace("918402", "929174")

    /** The vendored fixture reports signet; the core under test expects mutinynet. */
    val ARK_INFO_MUTINYNET = BarkdFixtures.ARK_INFO.replace("signet", "mutinynet")

    const val NO_WALLET = """{"fingerprint": null}"""
}

/**
 * Scriptable barkd responses per path: queued one-shots are served first, then the sticky
 * override, then the surface's default fixture ([stockDefaults] unless the test passes
 * [forkDefaults]). The stock default script is a reachable mutinynet gateway with an
 * existing wallet; `notifications/wait` fails by default so the long-poll loop's cadence
 * stays purely virtual-time-driven.
 */
internal class BarkdScript(
    private val defaults: Map<String, Reply> = stockDefaults,
) {

    sealed interface Reply
    data class Json(val body: String, val status: HttpStatusCode = HttpStatusCode.OK) : Reply
    data class Text(val body: String, val status: HttpStatusCode = HttpStatusCode.OK) : Reply
    data class Broken(val message: String = "connection refused") : Reply
    data class Slow(val delayMillis: Long, val then: Reply) : Reply

    private val queued = mutableMapOf<String, ArrayDeque<Reply>>()
    private val stickyReplies = mutableMapOf<String, Reply>()

    /**
     * Every request the engine saw, recorded at handler entry — MockEngine's own
     * `requestHistory` records only successful exchanges, which would hide [Broken] probes.
     */
    val seen = mutableListOf<HttpRequestData>()

    fun enqueue(path: String, reply: Reply, times: Int = 1) {
        repeat(times) { queued.getOrPut(path) { ArrayDeque() }.add(reply) }
    }

    fun sticky(path: String, reply: Reply) {
        stickyReplies[path] = reply
    }

    fun clearSticky(path: String) {
        stickyReplies.remove(path)
    }

    fun replyFor(path: String): Reply =
        queued[path]?.removeFirstOrNull()
            ?: stickyReplies[path]
            ?: defaults.getValue(path) // unknown path = the test scripted the wrong endpoint

    companion object {
        /** A reachable stock-0.4.0 mutinynet gateway with an existing wallet. */
        val stockDefaults: Map<String, Reply> = buildMap {
            BarkdFixtures.byPath.forEach { (path, body) -> put(path, Json(body)) }
            put(Paths.VTXOS, Json(HealthyFixtures.VTXOS_FAR_EXPIRY))
            put(Paths.ARK_INFO, Json(HealthyFixtures.ARK_INFO_MUTINYNET))
            put(Paths.PING, Text("pong"))
            put(Paths.WAIT, Broken("long-poll idle"))
        }

        /**
         * The fork twin (0.1.0-beta.6): the stock defaults minus the endpoints the fork lacks
         * (wallet probe, bip321, mnemonic, notifications, stock history route) plus the fork
         * fixtures. A request to a removed endpoint finds no default and reads as unreachable;
         * the fork tests additionally assert the request log to prove it never happens.
         */
        val forkDefaults: Map<String, Reply> = buildMap {
            putAll(stockDefaults)
            remove(Paths.WALLET)
            remove(Paths.BIP321)
            remove(Paths.MNEMONIC)
            remove(Paths.WAIT)
            remove(Paths.HISTORY)
            BarkdFixtures.forkByPath.forEach { (path, body) -> put(path, Json(body)) }
        }
    }
}

/**
 * MockEngine wired to the [BarkdScript] AND pinned to the test scheduler: Ktor executes
 * requests on the engine's dispatcher, so without this pin every call would hop to a real
 * IO thread and break virtual-time determinism.
 */
internal fun TestScope.barkdEngine(script: BarkdScript = BarkdScript()): MockEngine {
    val config = MockEngineConfig()
    config.dispatcher = StandardTestDispatcher(testScheduler)
    config.addHandler { request ->
        script.seen.add(request)
        perform(script.replyFor(request.url.encodedPath))
    }
    return MockEngine(config)
}

private suspend fun MockRequestHandleScope.perform(reply: BarkdScript.Reply): HttpResponseData = when (reply) {
    is BarkdScript.Json -> respond(
        content = reply.body,
        status = reply.status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
    is BarkdScript.Text -> respond(reply.body, reply.status)
    is BarkdScript.Broken -> throw IOException(reply.message)
    is BarkdScript.Slow -> {
        delay(reply.delayMillis)
        perform(reply.then)
    }
}

/** Fork wallet-create wiring every harness core carries; fork tests pin these in the create body. */
internal val FORK_WALLET = ForkWalletConfig(
    arkServerUrl = "http://captaind.test:3535",
    esploraUrl = "http://esplora.test:3003",
)

@Suppress("LongParameterList") // harness builder: each parameter is one independent test knob
internal fun TestScope.gatewayCore(
    engine: MockEngine,
    variant: BarkdApiVariant = BarkdApiVariant.STOCK_0_4,
    // The fork daemon identifies as signet on the wire while the product is mutinynet (R5) —
    // fork cores default to the real pairing so happy-path tests exercise the live config.
    expectedNetwork: String = if (variant == BarkdApiVariant.FORK_BETA6) "signet" else "mutinynet",
    networkLabel: String = if (variant == BarkdApiVariant.FORK_BETA6) "mutinynet" else expectedNetwork,
    pollInterval: Duration = 15.seconds,
    backoff: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds),
    scope: CoroutineScope = backgroundScope,
): GatewayLarkCore = GatewayLarkCore(
    api = BarkdApi(engine, GATEWAY_BASE_URL, variant = variant),
    scope = scope,
    expectedNetwork = expectedNetwork,
    networkLabel = networkLabel,
    forkWallet = FORK_WALLET,
    tuning = GatewayTuning(
        pollInterval = pollInterval,
        offlineBackoff = backoff,
        longPollRetryDelay = 10.seconds,
        now = { FIXED_NOW },
    ),
)

/** Advances virtual time by [duration] and runs the tasks that land exactly on the new mark. */
internal fun TestScope.advanceThrough(duration: Duration) {
    advanceTimeBy(duration)
    runCurrent()
}

internal fun BarkdScript.countOf(path: String): Int = seen.count { it.url.encodedPath == path }

internal fun BarkdScript.requests(path: String): List<HttpRequestData> = seen.filter { it.url.encodedPath == path }

internal suspend fun BarkdScript.bodyOf(path: String, index: Int = 0): String =
    requests(path)[index].body.toByteArray().decodeToString()

/** A fork-spec-shaped channel JSON for channel scripting; only the fields the mapping consumes vary. */
@Suppress("LongParameterList") // fixture builder: each parameter is one independent wire field
internal fun channelJson(
    channelId: String,
    localMsat: Long,
    capacitySat: Long = 1_000_000,
    isUsable: Boolean = true,
    isChannelReady: Boolean = true,
    expiryHeight: Long? = null,
): String {
    val expiry = if (expiryHeight == null) "" else """,
          "expiry_height": $expiryHeight"""
    return """
        {
          "channel_id": "$channelId",
          "counterparty": "024fb4d3",
          "capacity_sat": $capacitySat,
          "local_balance_msat": $localMsat,
          "is_usable": $isUsable,
          "is_channel_ready": $isChannelReady,
          "force_close_spend_delay": 144$expiry
        }
    """.trimIndent()
}

/** A spec-shaped movement JSON for history scripting; only the fields the mapping consumes vary. */
@Suppress("LongParameterList") // fixture builder: each parameter is one independent wire field
internal fun movementJson(
    id: Int,
    status: String = "successful",
    intendedSat: Long,
    effectiveSat: Long = intendedSat,
    sentToValue: String? = null,
    receivedOnValue: String? = null,
    destinationType: String = "ark",
    createdAt: String = "2026-07-28T10:00:00Z",
): String {
    fun destinations(value: String?, amount: Long) = if (value == null) {
        "[]"
    } else {
        """[{"destination": {"type": "$destinationType", "value": "$value"}, "amount_sat": $amount}]"""
    }
    return """
        {
          "id": $id,
          "status": "$status",
          "subsystem": {"name": "arkoor", "kind": "send"},
          "intended_balance_sat": $intendedSat,
          "effective_balance_sat": $effectiveSat,
          "offchain_fee_sat": 0,
          "sent_to": ${destinations(sentToValue, -intendedSat)},
          "received_on": ${destinations(receivedOnValue, intendedSat)},
          "input_vtxos": [],
          "output_vtxos": [],
          "exited_vtxos": [],
          "time": {"created_at": "$createdAt", "updated_at": "$createdAt", "completed_at": "$createdAt"}
        }
    """.trimIndent()
}
