import Foundation
import ComposeApp

/// The crate's global `generateMnemonic`, reached from file scope — same shadowing problem as
/// `openRustWallet` below, since the protocol member has the same name.
private func generateRustMnemonic() throws -> [String] {
    try generateMnemonic(wordCount: 12)
}

/// The crate's global `openWallet`, reached from file scope.
///
/// Inside the delegate class, the name resolves to the protocol method being implemented rather
/// than the generated global function, and Swift has no way to spell "the module-level one" from
/// there. A file-scope wrapper has no instance context, so it sees the global.
private func openRustWallet(
    datadir: String,
    network: String,
    arkServer: String,
    esplora: String,
    words: [String]
) async throws -> LarkWallet {
    try await openWallet(
        datadir: datadir,
        network: network,
        arkServer: arkServer,
        esplora: esplora,
        words: words
    )
}

/// The Rust core, behind the shared `LarkCoreDelegate` protocol (M2 U3).
///
/// Swift owns the `LarkWallet` handle so no UniFFI type ever crosses into Kotlin. Every call is
/// dispatched on a detached `Task`, which is the shape measured to complete: `iosApp/FfiThreadingTests`
/// proves `openWallet` finishes from both `Task` and `Task.detached` on iOS, while the equivalent
/// off-thread call hangs from Kotlin/JNA on Android. That measurement is why the iOS core exists
/// while the Android one is still blocked.
///
/// Each method reports exactly once. The Kotlin adapter suspends until the callback fires, so a
/// dropped callback would hang the caller forever — hence the `report` helper rather than ad-hoc
/// calls on every path.
final class FfiLarkCoreDelegate: LarkCoreDelegate {

    /// Set once `openWallet` succeeds. Read and written only inside `walletQueue`.
    private var wallet: LarkWallet?

    /// Serialises access to `wallet` across the detached tasks. An actor would be the modern
    /// choice, but the protocol methods are synchronous and non-throwing by construction
    /// (Kotlin/Native cannot express `suspend` here), so there is no `await` available to reach one.
    private let walletQueue = DispatchQueue(label: "xyz.lark.app.ffi.wallet")

    private var currentWallet: LarkWallet? {
        walletQueue.sync { wallet }
    }

    // MARK: - Lifecycle

    func generateMnemonic(onResult: @escaping ([String]?, String?) -> Void) {
        Task.detached {
            do {
                onResult(try generateRustMnemonic(), nil)
            } catch {
                onResult(nil, "\(error)")
            }
        }
    }

    func openWallet(
        config: FfiWalletConfig,
        words: [String],
        onDone: @escaping (String?) -> Void
    ) {
        Task.detached { [weak self] in
            // Timed because this is the one call whose duration is a product decision: it gates the
            // first screen a new user sees, and it differs by more than an order of magnitude
            // between a debug and a release build of the crate. Worth having in a TestFlight log.
            let started = Date()
            do {
                let opened = try await openRustWallet(
                    datadir: config.datadir,
                    network: config.network,
                    arkServer: config.arkServer,
                    esplora: config.esplora,
                    words: words
                )
                self?.walletQueue.sync { self?.wallet = opened }
                NSLog("lark: wallet open took %.1fs", Date().timeIntervalSince(started))
                onDone(nil)
            } catch {
                NSLog("lark: wallet open failed after %.1fs: %@", Date().timeIntervalSince(started), "\(error)")
                onDone("\(error)")
            }
        }
    }

    // MARK: - Reads

    func balanceSats(onResult: @escaping (KotlinLong?, String?) -> Void) {
        perform(onResult) { wallet in KotlinLong(value: Int64(try await wallet.balanceSats())) }
    }

    func movements(onResult: @escaping ([FfiMovement]?, String?) -> Void) {
        perform(onResult) { wallet in
            try await wallet.movements().map { movement in
                FfiMovement(
                    id: Int32(movement.id),
                    status: Self.state(of: movement.status),
                    effectiveBalanceSat: movement.effectiveBalanceSat,
                    intendedBalanceSat: movement.intendedBalanceSat,
                    offchainFeeSat: Int64(movement.offchainFeeSat),
                    sentTo: movement.sentTo,
                    receivedOn: movement.receivedOn,
                    createdAtEpochSeconds: movement.createdAtEpochSeconds
                )
            }
        }
    }

    func depositAddress(onResult: @escaping (String?, String?) -> Void) {
        perform(onResult) { wallet in try await wallet.depositAddress() }
    }

    func mintAddress(onResult: @escaping (String?, String?) -> Void) {
        perform(onResult) { wallet in try await wallet.mintAddress() }
    }

    func onchainBalance(onResult: @escaping (FfiOnchainBalance?, String?) -> Void) {
        perform(onResult) { wallet in
            let balance = try await wallet.onchainBalance()
            return FfiOnchainBalance(
                confirmedSat: Int64(balance.confirmedSat),
                pendingSat: Int64(balance.pendingSat),
                totalSat: Int64(balance.totalSat)
            )
        }
    }

    // MARK: - Writes

    func refresh(onDone: @escaping (String?) -> Void) {
        performVoid(onDone) { wallet in try await wallet.refresh() }
    }

    func onchainSync(onDone: @escaping (String?) -> Void) {
        performVoid(onDone) { wallet in try await wallet.onchainSync() }
    }

    func sendBolt11(invoice: String, sats: Int64, onResult: @escaping (String?, String?) -> Void) {
        perform(onResult) { wallet in try await wallet.sendBolt11(invoice: invoice, sats: UInt64(sats)) }
    }

    func sendArk(address: String, sats: Int64, onResult: @escaping (String?, String?) -> Void) {
        perform(onResult) { wallet in try await wallet.sendArk(address: address, sats: UInt64(sats)) }
    }

    func boardAll(onResult: @escaping (String?, String?) -> Void) {
        perform(onResult) { wallet in try await wallet.boardAll() }
    }

    // MARK: - Plumbing

    /// Runs `body` against the open wallet on a detached task and reports its result exactly once.
    /// Calling before the wallet is open is reported as an error, never a crash — the adapter polls,
    /// and a poll that lands during the (slow) open must not take the app down.
    private func perform<T>(
        _ onResult: @escaping (T?, String?) -> Void,
        name: String = #function,
        _ body: @escaping (LarkWallet) async throws -> T
    ) {
        guard let wallet = currentWallet else {
            onResult(nil, Self.notOpenMessage)
            return
        }
        Task.detached {
            do {
                onResult(try await body(wallet), nil)
            } catch {
                // The adapter above turns every failure into the same coarse seam outcome, so this
                // is the only place a cause survives. A wallet that says "that didn't go through"
                // with the reason nowhere on record is undebuggable in the field as well as here.
                NSLog("lark: %@ failed: %@", name, "\(error)")
                onResult(nil, "\(error)")
            }
        }
    }

    private func performVoid(
        _ onDone: @escaping (String?) -> Void,
        name: String = #function,
        _ body: @escaping (LarkWallet) async throws -> Void
    ) {
        guard let wallet = currentWallet else {
            onDone(Self.notOpenMessage)
            return
        }
        Task.detached {
            do {
                try await body(wallet)
                onDone(nil)
            } catch {
                NSLog("lark: %@ failed: %@", name, "\(error)")
                onDone("\(error)")
            }
        }
    }

    private static let notOpenMessage = "wallet is not open yet"

    private static func state(of status: MovementState) -> FfiMovementState {
        switch status {
        case .pending: return .pending
        case .successful: return .successful
        case .failed: return .failed
        case .canceled: return .canceled
        }
    }
}
