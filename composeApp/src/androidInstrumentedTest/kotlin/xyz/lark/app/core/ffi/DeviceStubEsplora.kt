package xyz.lark.app.core.ffi

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * The device-side twin of the JVM lane's `StubEsplora`: the three esplora endpoints an in-process
 * wallet needs, and nothing else.
 *
 * A separate implementation rather than a shared one because Android has no
 * `com.sun.net.httpserver` — that package is a JDK extra, absent from the Android runtime. This
 * speaks the same three responses over a raw socket instead.
 *
 * Answering locally is what makes the threading question answerable: if `openWallet` does not
 * complete, [pathsRequested] shows whether the wallet's HTTP call was made and served, which
 * separates "the request never went out" from "the request was answered and the future still
 * never resolved".
 */
internal class DeviceStubEsplora private constructor(
    private val server: ServerSocket,
    private val requested: ConcurrentLinkedQueue<String>,
) : AutoCloseable {

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    /** Paths this stub was asked for, in order, deduplicated. */
    val pathsRequested: List<String> get() = requested.toList().distinct()

    override fun close() = server.close()

    companion object {
        /** Signet's genesis block hash, which mutinynet shares; the crate validates it. */
        private const val SIGNET_GENESIS_HASH =
            "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"

        private const val TIP_HEIGHT = "2100000"
        private const val FEE_ESTIMATES = """{"1":2.0,"2":2.0,"3":1.5,"6":1.0,"144":1.0,"1008":1.0}"""

        fun start(): DeviceStubEsplora {
            val server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
            val requested = ConcurrentLinkedQueue<String>()
            thread(isDaemon = true, name = "stub-esplora") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                    thread(isDaemon = true) { serve(socket, requested) }
                }
            }
            return DeviceStubEsplora(server, requested)
        }

        private fun serve(socket: java.net.Socket, requested: ConcurrentLinkedQueue<String>) {
            socket.use {
                val path = readRequestPath(socket) ?: return
                requested += path
                respond(socket, bodyFor(path))
            }
        }

        /** The requested path, with the headers drained so the client's write completes. */
        private fun readRequestPath(socket: java.net.Socket): String? {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return null
            while (reader.readLine()?.isNotEmpty() == true) Unit
            return requestLine.split(' ').getOrElse(1) { "" }
        }

        /** null for anything outside the three-endpoint surface, which is answered 404. */
        private fun bodyFor(path: String): String? = when (path) {
            "/block-height/0" -> SIGNET_GENESIS_HASH
            "/blocks/tip/height" -> TIP_HEIGHT
            "/fee-estimates" -> FEE_ESTIMATES
            else -> null
        }

        private fun respond(socket: java.net.Socket, body: String?) {
            val bytes = (body ?: "").toByteArray()
            val status = if (body == null) "404 Not Found" else "200 OK"
            // Connection: close keeps this stub honest about framing — the client opens a fresh
            // connection per request rather than relying on keep-alive we do not model.
            val head = "HTTP/1.1 $status\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
        }
    }
}
