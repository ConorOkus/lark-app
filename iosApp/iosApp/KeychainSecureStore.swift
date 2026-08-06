import Foundation
import Security
import ComposeApp

/// The device's copy of the wallet: the mnemonic in the Keychain, the datadir in Application
/// Support, and two marker files — the "I wrote it down" flag and the user's standing request for
/// money to arrive (M2 U4 / KTD-11).
///
/// Three deliberate choices:
///
/// - **Application Support, not Documents.** Documents is user-visible over iTunes/Finder file
///   sharing and is offered up to iCloud Drive; the wallet database is app-private state, not a
///   user document. Application Support is also excluded from backup below.
/// - **`ThisDeviceOnly` accessibility.** The mnemonic must not ride an encrypted iCloud Keychain
///   backup onto another device: two devices running the same seed against Ark is exactly the
///   double-spend-your-own-VTXOs situation the liveness envelope warns about.
/// - **A marker file for the backup flag, not UserDefaults.** It keeps every piece of wallet state
///   inside the datadir, and avoids a required-reason API declaration for no benefit.
final class KeychainSecureStore: LarkSecureStore {

    private let service = "xyz.lark.app.wallet"
    private let account = "mnemonic"
    private let walletFileName = "wallet.sqlite"
    private let backedUpMarkerName = "backed-up"
    private let fundingArmedAtMarkerName = "funding-armed-at"

    /// `lark/` under Application Support, created on first use.
    var datadir: String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var directory = base.appendingPathComponent("lark", isDirectory: true)
        if !FileManager.default.fileExists(atPath: directory.path) {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            // The wallet database is device-local by design: it is re-derivable from the mnemonic,
            // and a restored copy on a second device would be a stale view of the same VTXOs.
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try? directory.setResourceValues(resourceValues)
        }
        return directory.path
    }

    func walletFileExists() -> Bool {
        FileManager.default.fileExists(atPath: datadir + "/" + walletFileName)
    }

    func loadWords() -> [String]? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let phrase = String(data: data, encoding: .utf8)
        else { return nil }
        let words = phrase.split(separator: " ").map(String.init)
        return words.isEmpty ? nil : words
    }

    func storeWords(words: [String]) -> Bool {
        guard let data = words.joined(separator: " ").data(using: .utf8) else { return false }
        // Delete-then-add rather than update: an add over an existing item fails with
        // errSecDuplicateItem, and this way one path covers both first write and overwrite.
        let identity: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(identity as CFDictionary)
        var attributes = identity
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    func isBackedUp() -> Bool {
        FileManager.default.fileExists(atPath: datadir + "/" + backedUpMarkerName)
    }

    func markBackedUp() {
        FileManager.default.createFile(atPath: datadir + "/" + backedUpMarkerName, contents: Data())
    }

    /// A marker file again, for the same reasons as the backup flag — but one that carries a value,
    /// since "when" is the whole point. Absent means the user has never asked for money to arrive;
    /// unparseable is treated as absent rather than as an epoch-zero intent that never expires.
    func loadFundingArmedAt() -> KotlinLong? {
        guard let text = try? String(contentsOfFile: fundingArmedAtPath, encoding: .utf8),
              let millis = Int64(text.trimmingCharacters(in: .whitespacesAndNewlines))
        else { return nil }
        return KotlinLong(longLong: millis)
    }

    func storeFundingArmedAt(millis: KotlinLong?) {
        guard let millis else {
            try? FileManager.default.removeItem(atPath: fundingArmedAtPath)
            return
        }
        try? String(millis.int64Value).write(toFile: fundingArmedAtPath, atomically: true, encoding: .utf8)
    }

    private var fundingArmedAtPath: String {
        datadir + "/" + fundingArmedAtMarkerName
    }
}

private extension FileManager {
    func createFile(atPath path: String, contents: Data) {
        createFile(atPath: path, contents: contents, attributes: nil)
    }
}
