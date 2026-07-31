package xyz.lark.app.core.ffi

/**
 * Whether the `lark_ffi` native library is loadable in this process (plan R6).
 *
 * On a device the library is packaged in the APK and this is always true. In JVM unit tests it
 * depends on the cargo host artifact being present at the `jna.library.path` the build sets
 * (see composeApp/build.gradle.kts), which requires a Rust toolchain and the two pinned fork
 * checkouts. Rather than make those a hard prerequisite of running `./gradlew`, the FFI test
 * lanes consult this and skip visibly when the library is absent.
 *
 * The probe is a cheap, side-effect-free crate call. It is deliberately NOT a file-existence
 * check: the question that matters is whether JNA can actually load and call the library, not
 * whether something is sitting on disk.
 */
internal object FfiHostLibrary {

    const val SKIP_MESSAGE: String =
        "lark_ffi host library not loadable — build it with scripts/build-rust.sh " +
            "(needs the rust/fork-pins.toml sibling checkouts). Skipping the FFI lane."

    const val REQUIRED_FAILURE_MESSAGE: String =
        "lark_ffi host library not loadable, but LARK_REQUIRE_FFI=1 — this lane must verify the " +
            "Rust core, not skip it. A green run here would mean the in-process wallet went " +
            "unverified. Check that scripts/build-rust.sh produced the library and that " +
            "jna.library.path points at it."

    /**
     * Whether an absent library must FAIL rather than skip.
     *
     * `scripts/ci.sh` sets this whenever it runs the Rust leg. Without it, a runner where the
     * library builds but cannot be loaded (wrong arch, wrong path, JNA init failure) would skip
     * the whole FFI lane and still report green — the exact silent pass the required lane exists
     * to prevent.
     */
    val required: Boolean get() = System.getenv("LARK_REQUIRE_FFI") == "1"

    val available: Boolean by lazy {
        // UnsatisfiedLinkError / ExceptionInInitializerError on a missing library are Errors, not
        // Exceptions, so runCatching's Throwable catch is the point — a narrower catch would let
        // an absent library fail the run instead of skipping it.
        runCatching { uniffi.lark_ffi.generateMnemonic(PROBE_WORD_COUNT) }.isSuccess
    }

    private const val PROBE_WORD_COUNT: UByte = 12u
}
