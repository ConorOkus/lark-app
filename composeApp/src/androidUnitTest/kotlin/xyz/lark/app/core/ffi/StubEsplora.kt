package xyz.lark.app.core.ffi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * The three esplora endpoints an in-process wallet needs, and nothing else.
 *
 * Opening a wallet is server-free with respect to the *Ark* server (the crate creates with
 * `force = true`), but bdk's onchain wallet still needs a chain source: it fetches the genesis
 * hash to confirm the network, and maintenance reads fee estimates and the tip. Discovered
 * empirically — this stub is exactly the surface that came back, so the per-PR contract lane runs
 * against real Rust with no network access at all.
 *
 * Deliberately narrow: anything not listed answers 404, so a future crate change that needs a
 * fourth endpoint fails loudly here instead of silently reaching for the internet.
 */
internal class StubEsplora private constructor(
    private val server: HttpServer,
    private val unknownPaths: MutableCollection<String>,
) : AutoCloseable {

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Paths this stub answered 404 for — a crate that needs a fourth endpoint shows up here. */
    val unknownPathsRequested: List<String> get() = unknownPaths.toList().distinct()

    override fun close() = server.stop(0)

    companion object {
        /**
         * Signet's genesis block hash, which mutinynet shares — a custom signet challenge changes
         * block validity, not the genesis block. The crate compares this against the configured
         * network and rejects a mismatch, so it is a real value, not a filler string.
         */
        const val SIGNET_GENESIS_HASH = "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"

        /** Any plausible height; nothing in the contract lane asserts on it. */
        private const val TIP_HEIGHT = "2100000"

        /** sat/vB by target confirmation count, in esplora's shape. */
        private const val FEE_ESTIMATES = """{"1":2.0,"2":2.0,"3":1.5,"6":1.0,"144":1.0,"1008":1.0}"""

        fun start(): StubEsplora {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val unknownPaths = java.util.concurrent.ConcurrentLinkedQueue<String>()
            server.createContext("/") { exchange ->
                when (val path = exchange.requestURI.path) {
                    "/block-height/0" -> exchange.respond(SIGNET_GENESIS_HASH)
                    "/blocks/tip/height" -> exchange.respond(TIP_HEIGHT)
                    "/fee-estimates" -> exchange.respond(FEE_ESTIMATES)
                    else -> {
                        unknownPaths += path
                        exchange.respond("", status = HTTP_NOT_FOUND)
                    }
                }
            }
            server.start()
            return StubEsplora(server, unknownPaths)
        }

        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404

        private fun HttpExchange.respond(body: String, status: Int = HTTP_OK) {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
