import Foundation
import Security
import Shared

/// Schlüssel-Wert-Speicher im Keychain: nur auf diesem Gerät, nicht in Backups oder iCloud.
final class KeychainStore: NSObject, KeyValueStore {
    private let service = "com.example.nova"

    func get(key: String) -> String? {
        data(for: key).flatMap { String(data: $0, encoding: .utf8) }
    }

    func set(key: String, value: String?) {
        setData(value?.data(using: .utf8), for: key)
    }

    func data(for key: String) -> Data? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func setData(_ data: Data?, for key: String) {
        SecItemDelete(baseQuery(key) as CFDictionary)
        guard let data else { return }
        var query = baseQuery(key)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }

    private func baseQuery(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }
}
