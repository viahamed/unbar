// ConfigCodec.swift — config import/export.
//
// A config is a small JSON object (version, name, zone, server public
// key, resolver mode, resolvers) encoded as URL-safe base64 inside an
// `unbar://add?c=...` link. The link can be copied or shown as a QR
// code. The local SOCKS password is NEVER included in an export, and
// importing never touches it.
//
// Import accepts a pasted link, a pasted raw code, or a scanned QR
// payload (the QR content is the same link).

import Foundation

public struct ExportedConfig: Codable {
    public var v: Int
    public var name: String
    public var zone: String
    public var key: String
    public var resolverMode: String
    public var resolvers: String

    public init(name: String, zone: String, key: String,
                resolverMode: String, resolvers: String) {
        self.v = 1
        self.name = name
        self.zone = zone
        self.key = key
        self.resolverMode = resolverMode
        self.resolvers = resolvers
    }
}

public enum ConfigCodec {
    public static let scheme = "unbar"

    /// Build the shareable link for a server. Never includes the SOCKS
    /// password (it is not part of ExportedConfig at all).
    public static func exportLink(for server: Server, settings: AppSettings) -> String? {
        let exported = ExportedConfig(
            name: server.name,
            zone: server.zone,
            key: server.serverKeyB64,
            resolverMode: settings.resolverMode.rawValue,
            resolvers: settings.effectiveResolvers
        )
        guard let data = try? JSONEncoder().encode(exported) else { return nil }
        let code = base64URL(data)
        return "unbar://add?c=\(code)"
    }

    /// Parse a pasted link, a raw code, or a QR payload into a Server.
    /// Returns nil when the payload is not a valid Unbar config.
    public static func importServer(from text: String) -> Server? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var code: String?
        if trimmed.hasPrefix("unbar://") {
            guard let components = URLComponents(string: trimmed),
                  let item = components.queryItems?.first(where: { $0.name == "c" }),
                  let value = item.value else {
                return nil
            }
            code = value
        } else if trimmed.hasPrefix("http") {
            // A link of the form https://...#c=<code> is also accepted.
            if let fragment = trimmed.split(separator: "#").last,
               fragment.hasPrefix("c=") {
                code = String(fragment.dropFirst(2))
            } else {
                return nil
            }
        } else {
            code = trimmed
        }
        guard let code = code,
              let data = Data(base64URL: code),
              let exported = try? JSONDecoder().decode(ExportedConfig.self, from: data),
              exported.v == 1 else {
            return nil
        }
        let server = Server(name: exported.name, zone: exported.zone,
                            serverKeyB64: exported.key)
        guard server.isValid else { return nil }
        return server
    }

    // MARK: - URL-safe base64 (no padding, no + or /)

    private static func base64URL(_ data: Data) -> String {
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension Data {
    init?(base64URL string: String) {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = base64.count % 4
        if padding > 0 {
            base64 += String(repeating: "=", count: 4 - padding)
        }
        self.init(base64Encoded: base64)
    }
}
