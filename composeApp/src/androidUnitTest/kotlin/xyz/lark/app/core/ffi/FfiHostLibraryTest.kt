package xyz.lark.app.core.ffi

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the `lark-ffi` host library actually does from Kotlin over JNA — the foundation every
 * higher-level FFI lane stands on.
 *
 * These are characterization tests: they pin the crate's real behavior as measured, so the next
 * change to the crate or the forks tells us immediately if any of it moved. In particular they pin
 * **which wallet operations need which backend**, which is the fact that decides what a per-PR
 * contract lane can honestly assert.
 *
 * All of it skips (never fails) when the host library is absent, so
 * `./gradlew :composeApp:testDebugUnitTest` stays green on a checkout with no Rust toolchain and
 * no fork clones (plan R6).
 */
class FfiHostLibraryTest {

    @Test
    fun theHostLibraryLoadsAndReachesRust() {
        assumeTrue(FfiHostLibrary.SKIP_MESSAGE, FfiHostLibrary.available)
        val words = uniffi.lark_ffi.generateMnemonic(WORD_COUNT)
        assertEquals(MNEMONIC_WORDS, words.size)
        assertTrue(words.all { it.isNotBlank() }, "a real mnemonic has no blank words")
    }

    @Test
    fun rustErrorsSurfaceAsTypedKotlinExceptions() {
        assumeTrue(FfiHostLibrary.SKIP_MESSAGE, FfiHostLibrary.available)
        // The crate rejects any word count that is not 12 or 24 with LarkError::Invalid; proving it
        // arrives as a typed LarkException (not a crash, not a bare Throwable) is what lets a
        // caller treat FFI failure as a normal, catchable outcome.
        val thrown = runCatching { uniffi.lark_ffi.generateMnemonic(INVALID_WORD_COUNT) }.exceptionOrNull()
        assertTrue(
            thrown is uniffi.lark_ffi.LarkException,
            "expected a typed LarkException from the crate, got: $thrown",
        )
    }

    /**
     * Wallet creation needs a **chain source but no Ark server**.
     *
     * The crate creates with `force = true`, which skips the Ark server probe — but bdk's onchain
     * wallet still fetches the genesis hash to confirm the network, so an unreachable esplora fails
     * creation outright. This is the corrected form of a planning assumption that had creation
     * down as fully server-free; the distinction is what makes a hermetic lane possible at all.
     */
    @Test
    fun walletCreationNeedsAChainSourceButNoArkServer() = runBlocking {
        assumeTrue(FfiHostLibrary.SKIP_MESSAGE, FfiHostLibrary.available)
        StubEsplora.start().use { esplora ->
            withDatadir { datadir ->
                uniffi.lark_ffi.openWallet(
                    datadir = datadir.absolutePath,
                    network = "signet",
                    arkServer = UNREACHABLE_ARK_SERVER,
                    esplora = esplora.baseUrl,
                    words = uniffi.lark_ffi.generateMnemonic(WORD_COUNT),
                ).use { wallet ->
                    assertTrue(wallet.fingerprint().isNotEmpty(), "a created wallet has a seed fingerprint")
                    assertTrue(File(datadir, "wallet.sqlite").exists(), "creation persisted its datadir")
                }
            }
        }
    }

    /**
     * Which seam-bearing operations work without an Ark server, and which do not.
     *
     * Pinned as one test because the *split* is the point: it is what says a per-PR lane can hold
     * balance, activity and the deposit address, while the receive code and any real send are
     * inherently live-lane work.
     */
    @Test
    fun onlyArkDependentOperationsFailWithoutAnArkServer() = runBlocking {
        assumeTrue(FfiHostLibrary.SKIP_MESSAGE, FfiHostLibrary.available)
        StubEsplora.start().use { esplora ->
            withDatadir { datadir ->
                uniffi.lark_ffi.openWallet(
                    datadir = datadir.absolutePath,
                    network = "signet",
                    arkServer = UNREACHABLE_ARK_SERVER,
                    esplora = esplora.baseUrl,
                    words = uniffi.lark_ffi.generateMnemonic(WORD_COUNT),
                ).use { wallet ->
                    // Local/chain-backed: these are the wallet's own state.
                    assertEquals(0uL, wallet.balanceSats(), "a fresh wallet is empty, and says so")
                    assertTrue(wallet.movements().isEmpty(), "a fresh wallet has no movements")
                    assertTrue(
                        wallet.depositAddress().startsWith(SIGNET_TAPROOT_PREFIX),
                        "the onchain deposit address is derived locally by bdk",
                    )

                    // Ark-backed: minting a receive address is a captaind round-trip.
                    assertTrue(
                        runCatching { wallet.mintAddress() }.isFailure,
                        "mint_address must not appear to succeed without an Ark server",
                    )
                }
            }
        }
    }

    /**
     * The chain-source surface is exactly the three endpoints [StubEsplora] answers.
     *
     * [StubEsplora] 404s everything else, so if a crate or fork bump starts needing a fourth
     * endpoint this test fails here — rather than the per-PR lane quietly starting to depend on
     * reaching the real internet.
     */
    @Test
    fun theChainSourceSurfaceIsOnlyTheStubbedEndpoints() = runBlocking {
        assumeTrue(FfiHostLibrary.SKIP_MESSAGE, FfiHostLibrary.available)
        StubEsplora.start().use { esplora ->
            withDatadir { datadir ->
                uniffi.lark_ffi.openWallet(
                    datadir = datadir.absolutePath,
                    network = "signet",
                    arkServer = UNREACHABLE_ARK_SERVER,
                    esplora = esplora.baseUrl,
                    words = uniffi.lark_ffi.generateMnemonic(WORD_COUNT),
                ).use { wallet ->
                    wallet.balanceSats()
                    wallet.depositAddress()
                    wallet.movements()
                }
                assertTrue(
                    esplora.unknownPathsRequested.isEmpty(),
                    "the wallet asked for chain-source endpoints the stub does not cover: " +
                        "${esplora.unknownPathsRequested}",
                )
            }
        }
    }

    /**
     * A fresh temporary datadir per use, removed afterwards.
     *
     * Not tidiness: the crate's open path is create-or-open, so a datadir left behind by an earlier
     * test would be reopened with the next test's mnemonic and fail on the seed mismatch.
     */
    private inline fun <T> withDatadir(block: (File) -> T): T {
        val datadir = Files.createTempDirectory("lark-ffi-host").toFile()
        return try {
            block(datadir)
        } finally {
            datadir.deleteRecursively()
        }
    }

    private companion object {
        const val WORD_COUNT: UByte = 12u
        const val INVALID_WORD_COUNT: UByte = 13u
        const val MNEMONIC_WORDS = 12

        /** Reserved-for-documentation host on a closed port: guaranteed never to answer. */
        const val UNREACHABLE_ARK_SERVER = "http://192.0.2.1:1"

        /** Signet/mutinynet taproot addresses are bech32m under this HRP. */
        const val SIGNET_TAPROOT_PREFIX = "tb1p"
    }
}
