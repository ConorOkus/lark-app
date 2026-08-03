package xyz.lark.app.core.ffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Does the crate's async surface complete off the main thread **on a real Android runtime**?
 *
 * This is the one question the JVM host lane cannot answer, and the one that blocks `FfiLarkCore`
 * (M2 unit U2). On the JVM host, `openWallet` completes only when entered through `runBlocking` on
 * the JUnit thread; from `scope.launch` or a plain thread it hangs forever, with the esplora
 * request made and answered and the continuation callback never invoked. Full measurement table:
 * `docs/ffi/kotlin-bindings-status.md`.
 *
 * That matters because the seam's `createWallet()`/`restoreWallet()` are synchronous while every
 * crate operation is `suspend`, so the adapter must launch into a scope and poll — precisely the
 * shape that hangs on the host. `runBlocking` on the caller's thread is not an option: it would
 * block the UI thread on a network round-trip.
 *
 * The three tests are one experiment. [openWalletCompletesUnderRunBlocking] is the control: if it
 * fails, something unrelated to threading is wrong (library load, stub, datadir) and the other two
 * results mean nothing. The other two are the shapes measured as hanging on the host.
 */
@RunWith(AndroidJUnit4::class)
class FfiAsyncThreadingInstrumentedTest {

    /**
     * Control: the one shape known to work on the JVM host.
     *
     * Establishes that on this device the library loads, the stub answers, and a wallet can be
     * created at all — so a failure in the other two tests is about *where* the call was made from.
     */
    @Test
    fun openWalletCompletesUnderRunBlocking() {
        withStubAndDatadir { esplora, datadir ->
            val fingerprint = runBlocking { openWallet(datadir, esplora).use { it.fingerprint() } }
            assertTrue(fingerprint.isNotEmpty(), "a created wallet has a seed fingerprint")
        }
    }

    /**
     * The blocker's exact shape: launched into a `CoroutineScope`, which is what `FfiLarkCore` does.
     *
     * Hangs forever on the JVM host. A pass here means the blocker is a host artifact and the
     * adapter's design is sound on device.
     */
    @Test
    fun openWalletCompletesFromALaunchedCoroutine() {
        withStubAndDatadir { esplora, datadir ->
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val outcome = AtomicReference<Result<ByteArray>>()
            val done = CountDownLatch(1)
            try {
                scope.launch {
                    outcome.set(runCatching { openWallet(datadir, esplora).use { it.fingerprint() } })
                    done.countDown()
                }
                awaitOrFail(done, esplora, "scope.launch on Dispatchers.IO")
                assertTrue(
                    outcome.get().getOrThrow().isNotEmpty(),
                    "a created wallet has a seed fingerprint",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * The second host-hanging shape: `runBlocking` entered on a plain thread rather than JUnit's.
     *
     * Separates "off the main thread" from "outside a coroutine dispatcher" — if this passes and
     * the launched variant does not, the fault is in the dispatcher integration, not the thread.
     */
    @Test
    fun openWalletCompletesFromRunBlockingOnAPlainThread() {
        withStubAndDatadir { esplora, datadir ->
            val executor = Executors.newSingleThreadExecutor()
            val outcome = AtomicReference<Result<ByteArray>>()
            val done = CountDownLatch(1)
            try {
                executor.execute {
                    outcome.set(
                        runCatching { runBlocking { openWallet(datadir, esplora).use { it.fingerprint() } } },
                    )
                    done.countDown()
                }
                awaitOrFail(done, esplora, "runBlocking on a plain executor thread")
                assertTrue(
                    outcome.get().getOrThrow().isNotEmpty(),
                    "a created wallet has a seed fingerprint",
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    /**
     * Fails with the diagnostic that matters instead of hanging the whole run.
     *
     * The esplora paths are the discriminator: if the wallet's chain-source request was made and
     * answered and the call still never returned, the Rust future is not completing after its HTTP
     * response — which is the host-side signature, reproduced.
     */
    private fun awaitOrFail(done: CountDownLatch, esplora: DeviceStubEsplora, shape: String) {
        if (done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return
        fail(
            "openWallet never completed from $shape within ${TIMEOUT_SECONDS}s. " +
                "Esplora paths served meanwhile: ${esplora.pathsRequested}. " +
                "Paths served but no completion = the host-side hang, reproduced on device.",
        )
    }

    private suspend fun openWallet(datadir: File, esplora: DeviceStubEsplora) =
        uniffi.lark_ffi.openWallet(
            datadir = datadir.absolutePath,
            network = "signet",
            arkServer = UNREACHABLE_ARK_SERVER,
            esplora = esplora.baseUrl,
            words = uniffi.lark_ffi.generateMnemonic(WORD_COUNT),
        )

    /**
     * A fresh stub and a fresh datadir per test.
     *
     * The datadir must be new: the crate's open path is create-or-open, so a leftover datadir would
     * be reopened with the next test's mnemonic and fail on the seed mismatch rather than on the
     * threading behavior under test.
     */
    private fun withStubAndDatadir(block: (DeviceStubEsplora, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The crate opens its sqlite file inside the datadir but does not create the directory —
        // on the JVM lane `createTempDirectory` did that; here it has to be explicit, or every
        // call fails at "unable to open database file" before reaching the behavior under test.
        val datadir = File(context.filesDir, "lark-ffi-threading-${System.nanoTime()}").apply { mkdirs() }
        DeviceStubEsplora.start().use { esplora ->
            try {
                block(esplora, datadir)
            } finally {
                datadir.deleteRecursively()
            }
        }
    }

    private companion object {
        const val WORD_COUNT: UByte = 12u

        /** Reserved-for-documentation host on a closed port: guaranteed never to answer. */
        const val UNREACHABLE_ARK_SERVER = "http://192.0.2.1:1"

        /**
         * Long enough that a slow emulator creating a wallet is not mistaken for a hang, short
         * enough that the measured-as-infinite hang fails the run rather than stalling it.
         */
        const val TIMEOUT_SECONDS = 90L
    }
}
