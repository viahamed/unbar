// KeychainHelper.swift — the SOCKS proxy password lives here and nowhere
// else on device. It is stored as a generic-password Keychain item, read
// only when building the tunnel config at connect time, and never written
// to UserDefaults, logs, or exported configs.

import Foundation
import Security

public final class KeychainHelper {
    public static let shared = KeychainHelper()

    private let service = "org.breakthebarriers.unbar.socks"
    private let account = "socks-password"

    private init() {}

    public func socksPassword() -> String {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess,
              let data = result as? Data,
              let password = String(data: data, encoding: .utf8) else {
            return ""
        }
        return password
    }

    public func setSocksPassword(_ password: String) {
        if password.isEmpty {
            deleteSocksPassword()
            return
        }
        guard let data = password.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    public func deleteSocksPassword() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
