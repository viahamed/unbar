// ResolverNormalization.swift — normalizing user-typed resolver lists
// to the standard comma-separated form ("1.1.1.1:53, 8.8.8.8:53"),
// whatever format they were typed in: lines, commas, spaces or
// semicolons as separators; a missing port becomes :53; extra spaces,
// symbols and letters are stripped. Entries that are not a valid
// IPv4 address with an optional port are dropped.
//
// Dot-as-separator: a dot between two complete IPv4 addresses
// separates them — "1.1.1.1.8.8.8.8" becomes two resolvers,
// "1.1.1.1:53.8.8.8.8" too, and a chain splits at every such dot.
// A trailing dot after the last IP is dropped, so "1.1.1.1.
// 8.8.8.8" becomes "1.1.1.1:53, 8.8.8.8:53". The rule applies ONLY
// when both sides of the dot are a complete IPv4; dots inside an
// address are untouched, and an incomplete right-hand side
// ("1.1.1.1.8.8.8") is not split — the whole token is dropped as
// invalid instead.
//
// Only IPv4 is valid here: IPv6 addresses and hostnames are not
// valid resolvers for this app, so they are dropped (see
// droppedResolverTokens, which reports what was dropped).

import Foundation

public enum ResolverNormalization {

    /// Normalized resolvers, in first-seen order, duplicates removed.
    public static func normalizeResolvers(_ raw: String) -> [String] {
        return splitResolverTokens(raw)
            .compactMap { cleanResolverToken($0) }
            .distinctPreservingOrder()
    }

    /// The resolver entries the user typed that normalization DROPS
    /// (IPv6 addresses and hostnames are not valid resolvers for this
    /// app — IPv4 only — so they are ignored). Returns the ORIGINAL
    /// tokens (what the user typed), so the UI can show them as a
    /// warning instead of dropping them silently.
    public static func droppedResolverTokens(_ raw: String) -> [String] {
        return splitResolverTokens(raw)
            .filter { cleanResolverToken($0) == nil }
            .distinctPreservingOrder()
    }

    /// Expanded resolver count (1.0.33): how many addresses the
    /// raw input expands to — the number the scan will actually
    /// probe. Mirrors the Go core's expandResolverEntries: a CIDR
    /// range counts as 2^(32-prefix) addresses, a plain IP as 1;
    /// a range larger than /20 (>4096 addresses) is skipped by
    /// the core and counts as 0 here too. Capped at 5000 (the
    /// core's cap). The Custom field's count used to count TOKENS
    /// (a /24 showed "1 resolvers"), which read as "one IP" —
    /// now it shows the real expanded count.
    public static func expandedResolverCount(_ raw: String) -> Int {
        var total = 0
        for token in normalizeResolvers(raw) {
            guard token.contains("/") else { total += 1; continue }
            guard let prefix = Int(token.split(separator: "/").last ?? "") else { continue }
            let hostBits = 32 - prefix
            guard hostBits <= 12 else { continue } // skipped by the core
            total += 1 << hostBits
            if total >= 5000 { return 5000 }
        }
        return total
    }

    /// Oversize CIDR ranges (1.0.33): the ranges in the raw input
    /// that are too large to scan (>4096 addresses, prefix < 20)
    /// — the core skips them silently, so the UI warns instead of
    /// letting the user wonder why the range never scans. Returns
    /// the normalized tokens (e.g. "2.188.21.0/0").
    public static func oversizeResolverRanges(_ raw: String) -> [String] {
        return splitResolverTokens(raw).compactMap { token -> String? in
            guard token.contains("/") else { return nil }
            guard let cleaned = cleanResolverToken(token) else { return nil }
            guard let prefix = Int(cleaned.split(separator: "/").last ?? "") else { return nil }
            return (32 - prefix > 12) ? cleaned : nil
        }.distinctPreservingOrder()
    }

    // A dot joining two complete IPv4 addresses (the first may carry
    // a port: "1.1.1.1:53.8.8.8.8"). Replaced with a comma,
    // repeatedly, so chains ("1.1.1.1.8.8.8.8.9.9.9.9") split at
    // every dot.
    private static let ipv4DotIpv4Pattern =
        "((?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?)\\.((?:\\d{1,3}\\.){3}\\d{1,3})"

    private static func splitDotSeparatedIps(_ raw: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: ipv4DotIpv4Pattern, options: []) else {
            return raw
        }
        var out = raw
        while true {
            let range = NSRange(out.startIndex..., in: out)
            let replaced = regex.stringByReplacingMatches(in: out, options: [], range: range, withTemplate: "$1,$2")
            if replaced == out { return out }
            out = replaced
        }
    }

    private static func splitResolverTokens(_ raw: String) -> [String] {
        return splitDotSeparatedIps(raw)
            .components(separatedBy: CharacterSet(charactersIn: ",;\n\r\t "))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    // One token -> "ip:port", or a CIDR range "ip/prefix" passed
    // through unchanged, or nil when it is neither a valid IPv4
    // with an optional port nor a valid CIDR range. Plain IPs keep
    // only digits, dots and colons — strips spaces, symbols and
    // stray letters — and drop a stray dot at either end
    // ("1.1.1.1." -> "1.1.1.1"). A CIDR range ("2.188.21.0/24",
    // 1.0.32) is validated (IPv4 + prefix 0-32, no leading zeros,
    // no port) and returned as-is — the Go core expands it to every
    // address in the range (each with :53) when scanning/connecting.
    private static func cleanResolverToken(_ token: String) -> String? {
        let trimmed = token.trimmingCharacters(in: .whitespaces)
        if trimmed.contains("/") {
            let cidrParts = trimmed.split(separator: "/", omittingEmptySubsequences: false)
            guard cidrParts.count == 2 else { return nil }
            let ip = String(cidrParts[0]).filter { $0.isNumber || $0 == "." }
                .trimmingCharacters(in: CharacterSet(charactersIn: "."))
            let prefix = String(cidrParts[1]).trimmingCharacters(in: .whitespaces)
            guard isValidIpv4(ip) else { return nil }
            // Prefix must be a plain 0-32 number: 1-2 digits, no
            // leading zeros, no port.
            guard !prefix.isEmpty, prefix.count <= 2,
                  prefix.allSatisfy({ $0.isNumber }),
                  !(prefix.count > 1 && prefix.hasPrefix("0")),
                  let prefixNum = Int(prefix), (0...32).contains(prefixNum) else {
                return nil
            }
            return "\(ip)/\(prefixNum)"
        }
        let kept = token.filter { $0.isNumber || $0 == "." || $0 == ":" }
        let cleaned = kept.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !cleaned.isEmpty else { return nil }
        let parts = cleaned.split(separator: ":", omittingEmptySubsequences: false)
        let ip = parts.first.map(String.init) ?? ""
        let portText = parts.count > 1 && !parts[1].isEmpty ? String(parts[1]) : "53"
        guard isValidIpv4(ip) else { return nil }
        guard let port = Int(portText), (1...65535).contains(port) else { return nil }
        return "\(ip):\(port)"
    }

    private static func isValidIpv4(_ ip: String) -> Bool {
        let octets = ip.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return false }
        for octet in octets {
            guard !octet.isEmpty, octet.count <= 3,
                  let n = Int(octet), (0...255).contains(n),
                  octet.count == 1 || !octet.hasPrefix("0") else {
                return false
            }
        }
        return true
    }
}

private extension Array where Element: Hashable {
    func distinctPreservingOrder() -> [Element] {
        var seen = Set<Element>()
        var out: [Element] = []
        for item in self where seen.insert(item).inserted {
            out.append(item)
        }
        return out
    }
}
