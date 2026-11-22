// Server.swift — a user-added Soar server.
//
// Unbar ships with NO pre-configured server. The user adds their own
// (name, zone, server public key). The key is a public key, not a secret,
// but it is still only stored on device and in the VPN configuration.

import Foundation

public struct Server: Identifiable, Codable, Equatable {
    public var id: UUID
    public var name: String
    /// Delegated zone, e.g. "tunnel.example.com".
    public var zone: String
    /// Base64 of the 32-byte Ed25519 server public key.
    public var serverKeyB64: String
    /// Last measured round-trip time in milliseconds, if tested.
    public var lastLatencyMs: Int?
    /// Last test result: nil = never tested, "" = ok, otherwise an error.
    public var lastTestError: String?
    /// The subscription this server came from, if any (nil = added
    /// manually). The UI shows a "Subscription" badge for these.
    public var subscriptionId: UUID?

    public init(id: UUID = UUID(), name: String, zone: String,
                serverKeyB64: String, lastLatencyMs: Int? = nil,
                lastTestError: String? = nil, subscriptionId: UUID? = nil) {
        self.id = id
        self.name = name
        self.zone = zone
        self.serverKeyB64 = serverKeyB64
        self.lastLatencyMs = lastLatencyMs
        self.lastTestError = lastTestError
        self.subscriptionId = subscriptionId
    }

    /// True when the zone looks plausible and the key decodes to 32 bytes.
    public var isValid: Bool {
        let zoneOk = !zone.trimmingCharacters(in: .whitespaces).isEmpty
            && zone.contains(".")
        guard let data = Data(base64Encoded: serverKeyB64.trimmingCharacters(in: .whitespaces)) else {
            return false
        }
        return zoneOk && data.count == 32
    }
}
