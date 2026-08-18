package xyz.lark.app.core.ffi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * The esplora surface an in-process wallet needs, and nothing else.
 *
 * Opening a wallet is server-free with respect to the *Ark* server (the crate creates with
 * `force = true`), but bdk's onchain wallet still needs a chain source. Discovered empirically,
 * one 404 at a time — this stub is exactly the surface that came back, so the per-PR contract lane
 * runs against real Rust with no network access at all:
 *
 *  - `/block-height/0` — the genesis hash, which the crate checks against the configured network
 *  - `/blocks/tip/height` and `/fee-estimates` — the tip and fee reads maintenance makes
 *  - `/blocks` and `/scripthash/…/txs` — the full scan wallet creation runs, so that a wallet
 *    restored from twelve words rediscovers its own on-chain coins
 *
 * Deliberately narrow: anything not listed answers 404, so a future crate change that needs one
 * more endpoint fails loudly here instead of silently reaching for the internet. It has already
 * earned that once — the full scan above arrived this way, on a green local run that turned out to
 * be loading a stale library.
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

        /**
         * The height this stub reports as the chain tip. Internal because a test asserting that a
         * wallet's reported tip equals *this* value is what proves the tip came from the chain
         * source rather than being fabricated locally.
         */
        internal const val TIP_HEIGHT = 2_100_000L

        /**
         * The recent-blocks page, in esplora's shape, holding only the tip.
         *
         * A full scan needs somewhere to anchor the transactions it finds, and this is where bdk
         * gets its recent headers. One block is enough: the scan finds nothing to anchor on a
         * wallet with no history, which is every wallet this lane creates.
         */
        private const val RECENT_BLOCKS = """[{"id":"$SIGNET_GENESIS_HASH","height":$TIP_HEIGHT,""" +
            """"version":536870912,"timestamp":1700000000,"tx_count":1,"size":343,"weight":1372,""" +
            """"merkle_root":"$SIGNET_GENESIS_HASH","previousblockhash":"$SIGNET_GENESIS_HASH",""" +
            """"mediantime":1700000000,"nonce":0,"bits":503543726,"difficulty":0}]"""

        /** sat/vB by target confirmation count, in esplora's shape. */
        private const val FEE_ESTIMATES = """{"1":2.0,"2":2.0,"3":1.5,"6":1.0,"144":1.0,"1008":1.0}"""

        /** `/scripthash/<hex>/txs`, optionally `/chain/<txid>` for the next page. */
        private val SCRIPTHASH_TXS = Regex("""^/scripthash/[0-9a-f]{64}/txs(/chain/[0-9a-f]{64})?$""")

        fun start(): StubEsplora {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val unknownPaths = java.util.concurrent.ConcurrentLinkedQueue<String>()
            server.createContext("/") { exchange ->
                when (val path = exchange.requestURI.path) {
                    "/block-height/0" -> exchange.respond(SIGNET_GENESIS_HASH)
                    "/blocks/tip/height" -> exchange.respond(TIP_HEIGHT.toString())
                    "/fee-estimates" -> exchange.respond(FEE_ESTIMATES)
                    "/blocks" -> exchange.respond(RECENT_BLOCKS)
                    // A full scan sweeps derived scripts until it has seen `STOP_GAP` empty ones
                    // in a row, so how many of these arrive is bdk's business, not this stub's.
                    // Every one is empty: a wallet created here has no history by construction,
                    // and a stub that invented some would be testing a fiction.
                    else -> if (SCRIPTHASH_TXS.matches(path)) {
                        exchange.respond("[]")
                    } else {
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
