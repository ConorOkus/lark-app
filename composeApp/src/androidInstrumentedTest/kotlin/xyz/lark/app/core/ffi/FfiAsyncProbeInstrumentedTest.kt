package xyz.lark.app.core.ffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Splits the U2 blocker in half: is the Kotlin async binding broken off-thread *generally*, or
 * only for futures parked on the tokio reactor?
 *
 * [FfiAsyncThreadingInstrumentedTest] shows `openWallet` hanging from any thread but the
 * instrumentation thread, while the same crate completes from a detached Swift task on iOS. That
 * narrows the fault to the Kotlin/JNA side but not to *where* — and the two candidates need
 * different fixes:
 *
 *  - the poll/continuation round-trip itself cannot deliver off the calling thread, or
 *  - it can, and only a wake arriving from a Rust-owned reactor thread is lost.
 *
 * The two probes differ in exactly that one property. `asyncProbeNoRuntime` is `Ready` on first
 * poll with no runtime at all; `asyncProbeTokioTimer` is identical except it parks on the tokio
 * timer, so its wake necessarily comes from a thread the caller never entered. Neither touches
 * the network, sockets, or bark, so a difference between them isolates the runtime.
 *
 * Reading the results:
 *  - both hang off-thread → the binding's continuation delivery is broken; fix is in the bindings
 *  - only the timer one hangs → wakes from a Rust-owned thread are lost; fix is in the callback's
 *    thread attachment (or UniFFI's tokio integration)
 *  - neither hangs → the fault needs something else `openWallet` does, and the probes bound it
 */
@RunWith(AndroidJUnit4::class)
class FfiAsyncProbeInstrumentedTest {

    /** Control: the runtime-free probe on the shape that works today. */
    @Test
    fun noRuntimeProbeCompletesUnderRunBlocking() {
        val result = runBlocking { uniffi.lark_ffi.asyncProbeNoRuntime(7u) }
        assertEquals(7u, result)
    }

    /** Control: the tokio-parked probe on the shape that works today. */
    @Test
    fun tokioTimerProbeCompletesUnderRunBlocking() {
        val result = runBlocking { uniffi.lark_ffi.asyncProbeTokioTimer(50u) }
        assertEquals(50u, result)
    }

    /** Probe 1 off-thread: no reactor involved, so this isolates the continuation round-trip. */
    @Test
    fun noRuntimeProbeCompletesFromALaunchedCoroutine() {
        assertCompletesOffThread("asyncProbeNoRuntime") { uniffi.lark_ffi.asyncProbeNoRuntime(7u) }
    }

    /** Probe 2 off-thread: identical but for the wake arriving from a tokio thread. */
    @Test
    fun tokioTimerProbeCompletesFromALaunchedCoroutine() {
        assertCompletesOffThread("asyncProbeTokioTimer") { uniffi.lark_ffi.asyncProbeTokioTimer(50u) }
    }

    /**
     * Probe 3 off-thread: the tokio **I/O driver** rather than the timer.
     *
     * The timer and the I/O driver are separate wake paths, and the wallet's chain source uses the
     * I/O one. Points at the local stub's port so the connect succeeds without leaving the device.
     */
    @Test
    fun tokioTcpProbeCompletesFromALaunchedCoroutine() {
        DeviceStubEsplora.start().use { esplora ->
            val hostPort = esplora.baseUrl.removePrefix("http://")
            assertCompletesOffThread("asyncProbeTokioTcp") {
                uniffi.lark_ffi.asyncProbeTokioTcp(hostPort)
            }
        }
    }

    /** Control for probe 3 on the shape that works today. */
    @Test
    fun tokioTcpProbeCompletesUnderRunBlocking() {
        DeviceStubEsplora.start().use { esplora ->
            val hostPort = esplora.baseUrl.removePrefix("http://")
            val result = runBlocking { uniffi.lark_ffi.asyncProbeTokioTcp(hostPort) }
            assertEquals(1u, result)
        }
    }

    private fun assertCompletesOffThread(label: String, call: suspend () -> UInt) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val outcome = AtomicReference<Result<UInt>>()
        val done = CountDownLatch(1)
        try {
            scope.launch {
                outcome.set(runCatching { call() })
                done.countDown()
            }
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("$label never completed from scope.launch within ${TIMEOUT_SECONDS}s")
            }
            outcome.get().getOrThrow()
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        /**
         * Short on purpose: both probes are sub-second when they work, so anything slower is the
         * hang, and a tight bound keeps the run quick enough to iterate on.
         */
        const val TIMEOUT_SECONDS = 20L
    }
}
