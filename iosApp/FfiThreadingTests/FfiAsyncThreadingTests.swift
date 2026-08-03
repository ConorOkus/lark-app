import XCTest

/// The same threading experiment as `FfiAsyncThreadingInstrumentedTest`, run through the *other*
/// foreign binding.
///
/// On Android the crate's async surface completes only when entered on the instrumentation thread;
/// from `scope.launch` or a plain thread it hangs with the chain-source request made and answered
/// (measured on device — see `docs/ffi/kotlin-bindings-status.md`). That points at the crate, but
/// the Kotlin path carries a lot of machinery the Rust future does not: a JVM, JNA callbacks, and
/// UniFFI's Kotlin continuation shim.
///
/// Swift shares none of it. If `openWallet` completes here, the fault is in the Kotlin/JNA
/// continuation path rather than in how the crate drives its futures — which would move the fix
/// from the crate to the bindings, and unblock the iOS half of the seam immediately.
///
/// Deliberately runs against the real mutinynet esplora rather than a stub: this asks one question
/// only — whether the future resolves — and a third stub implementation would add a suspect
/// without adding an answer.
final class FfiAsyncThreadingTests: XCTestCase {

    /// The shape an iOS adapter would actually use: a `Task` started from the caller's context.
    func testOpenWalletCompletesFromATask() throws {
        try assertOpenWalletCompletes(shape: "Task") { datadir, done in
            Task { done(await Self.openAndFingerprint(datadir: datadir)) }
        }
    }

    /// Detached: no inherited context or priority — the closest Swift analogue of the Kotlin shape
    /// that hangs, where the call is driven by a thread unrelated to the caller.
    func testOpenWalletCompletesFromADetachedTask() throws {
        try assertOpenWalletCompletes(shape: "Task.detached") { datadir, done in
            Task.detached { done(await Self.openAndFingerprint(datadir: datadir)) }
        }
    }

    // MARK: - harness

    private func assertOpenWalletCompletes(
        shape: String,
        launch: (String, @escaping @Sendable (Result<Data, Error>) -> Void) -> Void
    ) throws {
        let datadir = try makeDatadir()
        defer { try? FileManager.default.removeItem(atPath: datadir) }

        let finished = expectation(description: "openWallet completed from \(shape)")
        let outcome = ResultBox()
        launch(datadir) { result in
            outcome.value = result
            finished.fulfill()
        }
        // A bounded wait, so the measured-as-infinite hang fails the run instead of stalling it.
        wait(for: [finished], timeout: Self.timeout)

        guard let result = outcome.value else {
            XCTFail("openWallet never completed from \(shape) within \(Self.timeout)s")
            return
        }
        let fingerprint = try result.get()
        XCTAssertFalse(fingerprint.isEmpty, "a created wallet has a seed fingerprint")
    }

    private static func openAndFingerprint(datadir: String) async -> Result<Data, Error> {
        do {
            let wallet = try await openWallet(
                datadir: datadir,
                network: "signet",
                arkServer: unreachableArkServer,
                esplora: esplora,
                words: try generateMnemonic(wordCount: 12)
            )
            return .success(wallet.fingerprint())
        } catch {
            return .failure(error)
        }
    }

    /// A fresh directory per test: the crate's open path is create-or-open, so a reused datadir
    /// would fail on a seed mismatch rather than on the behavior under test. The crate opens its
    /// sqlite file inside the directory but does not create the directory itself.
    private func makeDatadir() throws -> String {
        let path = NSTemporaryDirectory() + "lark-ffi-threading-" + UUID().uuidString
        try FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: true)
        return path
    }

    private static let unreachableArkServer = "http://192.0.2.1:1"
    private static let esplora = "https://mutinynet.com/api"
    private static let timeout: TimeInterval = 90
}

/// Carries the async result back to the synchronous test body.
private final class ResultBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Result<Data, Error>?

    var value: Result<Data, Error>? {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
