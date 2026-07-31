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

    val available: Boolean by lazy {
        // UnsatisfiedLinkError / ExceptionInInitializerError on a missing library are Errors, not
        // Exceptions, so runCatching's Throwable catch is the point — a narrower catch would let
        // an absent library fail the run instead of skipping it.
        runCatching { uniffi.lark_ffi.generateMnemonic(PROBE_WORD_COUNT) }.isSuccess
    }

    private const val PROBE_WORD_COUNT: UByte = 12u
}
