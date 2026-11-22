// ServerValidation.swift — server entry validation: the public key
// and the zone are validated at entry (add/edit and config import and
// subscription refresh), and duplicates are rejected — before, any
// non-blank key/zone was accepted and a bad key only failed later at
// connect time with a cryptic error. Error strings are English,
// matching the messages shown in the dialogs.

import Foundation

public enum ServerValidation {

    /// An Ed25519 public key is exactly 32 bytes, base64-encoded.
    public static func validatePublicKeyB64(_ key: String) -> String? {
        let clean = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "Public key is required" }
        guard let bytes = Data(base64Encoded: clean, options: [.ignoreUnknownCharacters]) else {
            return "Public key is not valid base64"
        }
        if bytes.count != 32 {
            return "Public key must decode to 32 bytes (Ed25519), got \(bytes.count)"
        }
        return nil
    }

    /// A zone is a DNS hostname: labels of letters/digits/hyphens, dot-separated.
    public static func validateZone(_ zone: String) -> String? {
        let clean = zone.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "Zone is required" }
        if clean.count > 253 { return "Zone is too long" }
        let label = "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
        let hostname = "^\(label)(\\.\(label))*$"
        let predicate = NSPredicate(format: "SELF MATCHES %@", hostname)
        if !predicate.evaluate(with: clean) {
            return "Zone is not a valid hostname (e.g. r.example.net)"
        }
        return nil
    }

    /// True when another server (not the one being edited) already has this zone + key.
    public static func isDuplicate(servers: [Server], excludeId: UUID?,
                                   zone: String, publicKeyB64: String) -> Bool {
        let cleanZone = zone.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanKey = publicKeyB64.trimmingCharacters(in: .whitespacesAndNewlines)
        return servers.contains {
            $0.id != excludeId
                && $0.zone.trimmingCharacters(in: .whitespacesAndNewlines) == cleanZone
                && $0.serverKeyB64.trimmingCharacters(in: .whitespacesAndNewlines) == cleanKey
        }
    }

    /// Full entry check: zone, then key, then duplicate. Returns the first error, or nil.
    public static func validateEntry(servers: [Server], excludeId: UUID?,
                                     zone: String, publicKeyB64: String) -> String? {
        if let error = validateZone(zone) { return error }
        if let error = validatePublicKeyB64(publicKeyB64) { return error }
        if isDuplicate(servers: servers, excludeId: excludeId, zone: zone, publicKeyB64: publicKeyB64) {
            return "A server with this zone and key already exists"
        }
        return nil
    }
}
