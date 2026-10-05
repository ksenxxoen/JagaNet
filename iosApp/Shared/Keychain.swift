import Foundation
import Security

/// Generic-password items in an access group shared by the app and the Packet Tunnel extension.
enum Keychain {
    static let service = "dev.jaganet"
    static var accessGroup: String? { Bundle.main.object(forInfoDictionaryKey: "JagaNetAccessGroup") as? String }

    private static func query(_ key: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if let g = accessGroup { q[kSecAttrAccessGroup as String] = g }
        return q
    }

    static func get(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ key: String, _ value: String) {
        let data = Data(value.utf8)
        let status = SecItemUpdate(query(key) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query(key)
            q[kSecValueData as String] = data
            // Readable by the extension after first unlock, so on-demand reconnects work.
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(q as CFDictionary, nil)
        }
    }

    static func remove(_ key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
}

/// What the app hands the extension for the next start: kept in the shared Keychain, never in providerConfiguration.
struct ActiveTunnel: Codable {
    var configJson: String // dev.jaganet.api.TunnelConfig
    var optionsJson: String // dev.jaganet.app.tunnel.TunnelOptions
    var privateKey: String?

    static let key = "tunnel.active"
    static func load() -> ActiveTunnel? {
        Keychain.get(key).flatMap { try? JSONDecoder().decode(ActiveTunnel.self, from: Data($0.utf8)) }
    }
    func save() {
        if let d = try? JSONEncoder().encode(self) { Keychain.set(Self.key, String(decoding: d, as: UTF8.self)) }
    }
}
