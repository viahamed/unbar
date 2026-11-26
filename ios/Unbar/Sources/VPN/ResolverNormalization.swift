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
//
// (1.0.2, mirrors Android SettingsRepository.kt): Persian and
// Arabic-Indic digits and separators become ASCII and zero-width
// marks are removed first; dash ranges ("a.b.c.d-a.b.c.e") become
// the exact CIDR blocks that cover them; leading zeros in an octet
// are dropped instead of the entry being rejected; a CIDR range with
// host bits set is corrected to its network address. Only ASCII
// digits count as digits (Swift's Character.isNumber would also
// accept other scripts).

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

    static func splitResolverTokens(_ raw: String) -> [String] {
        // (1.0.2): Persian/Arabic digits and separators become their
        // ASCII forms first, and dash ranges ("a.b.c.d-a.b.c.e") turn
        // into the exact CIDR blocks that cover them, so typed text
        // and imported files read the same way.
        let prepared = expandDashRanges(asciiResolverText(raw))
        return splitDotSeparatedIps(prepared)
            .components(separatedBy: CharacterSet(charactersIn: ",;\n\r\t "))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    // MARK: - (1.0.2) shared helpers, also used by ResolverFileImport

    /// Persian (U+06F0..06F9) and Arabic-Indic (U+0660..0669) digits
    /// -> ASCII; Persian comma / semicolon / full stop and the
    /// zero-width marks Persian keyboards insert -> ASCII or removed.
    static func asciiResolverText(_ raw: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in raw.unicodeScalars {
            switch scalar.value {
            case 0x06F0...0x06F9:
                out.append(Unicode.Scalar(UInt8(0x30 + scalar.value - 0x06F0)))
            case 0x0660...0x0669:
                out.append(Unicode.Scalar(UInt8(0x30 + scalar.value - 0x0660)))
            case 0x060C:
                out.append(",")
            case 0x061B:
                out.append(";")
            case 0x06D4, 0x066B:
                out.append(".")
            case 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF:
                continue
            default:
                out.append(scalar)
            }
        }
        return String(out)
    }

    // ASCII-only character classes: ICU's \d and \s are Unicode-wide,
    // Java's (Android) are ASCII, so they are spelled out here.
    static let dashRangePattern =
        "(?<![0-9.])([0-9]{1,3}(?:\\.[0-9]{1,3}){3})[ \\t\\n\\x0B\\f\\r]*[-\\u2013\\u2014][ \\t\\n\\x0B\\f\\r]*([0-9]{1,3}(?:\\.[0-9]{1,3}){3})(?![0-9.])"

    private static let dashRangeRegex = try? NSRegularExpression(pattern: dashRangePattern, options: [])

    /// "2.188.21.1-2.188.21.254" -> the minimal CIDR blocks covering
    /// exactly that range ("2.188.21.1/32,2.188.21.2/31,..."). A
    /// reversed range is swapped; an invalid address leaves the text
    /// as-is so it is reported as ignored.
    static func expandDashRanges(_ raw: String) -> String {
        guard raw.contains("-") || raw.contains("\u{2013}") || raw.contains("\u{2014}") else { return raw }
        guard let regex = dashRangeRegex else { return raw }
        let ns = raw as NSString
        let matches = regex.matches(in: raw, options: [], range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return raw }
        let out = NSMutableString(string: raw)
        for m in matches.reversed() {
            let first = ns.substring(with: m.range(at: 1))
            let second = ns.substring(with: m.range(at: 2))
            guard let a = canonicalIpv4(first).map(ipv4ToInt),
                  let b = canonicalIpv4(second).map(ipv4ToInt) else { continue }
            let replacement = rangeToCidrs(min(a, b), max(a, b)).joined(separator: ",")
            out.replaceCharacters(in: m.range, with: replacement)
        }
        return out as String
    }

    /// An IPv4 address in canonical form, or nil. Leading zeros are
    /// dropped ("010.001.2.3" -> "10.1.2.3") instead of the whole
    /// entry being rejected.
    static func canonicalIpv4(_ ip: String) -> String? {
        let octets = ip.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return nil }
        var out: [Int] = []
        for o in octets {
            guard !o.isEmpty, o.count <= 3, o.allSatisfy({ isAsciiDigit($0) }),
                  let n = Int(o), (0...255).contains(n) else { return nil }
            out.append(n)
        }
        return out.map(String.init).joined(separator: ".")
    }

    /// Canonical IPv4 -> 32-bit value (in an Int64).
    static func ipv4ToInt(_ ip: String) -> Int64 {
        let p = ip.split(separator: ".").map { Int64($0) ?? 0 }
        guard p.count == 4 else { return 0 }
        return (p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]
    }

    static func intToIpv4(_ v: Int64) -> String {
        return "\((v >> 24) & 255).\((v >> 16) & 255).\((v >> 8) & 255).\(v & 255)"
    }

    static func rangeToCidrs(_ start: Int64, _ end: Int64) -> [String] {
        var out: [String] = []
        var cur = start
        while cur <= end {
            var size = cur == 0 ? 32 : min(cur.trailingZeroBitCount, 32)
            while size > 0 && cur + (Int64(1) << Int64(size)) - 1 > end { size -= 1 }
            out.append("\(intToIpv4(cur))/\(32 - size)")
            cur += Int64(1) << Int64(size)
            if out.count > 64 { break } // pathological input; never expected
        }
        return out
    }

    static func cidrNetwork(_ ip: String, _ prefix: Int) -> String {
        let mask: Int64 = prefix == 0 ? 0 : (Int64(0xFFFFFFFF) << Int64(32 - prefix)) & 0xFFFFFFFF
        return intToIpv4(ipv4ToInt(ip) & mask)
    }

    static func isAsciiDigit(_ c: Character) -> Bool {
        return c >= "0" && c <= "9"
    }

    // One token -> "ip:port", or a CIDR range "ip/prefix", or nil
    // when it is neither a valid IPv4 with an optional port nor a
    // valid CIDR range. Plain IPs keep only digits, dots and colons
    // — strips spaces, symbols and stray letters — and drop a stray
    // dot at either end ("1.1.1.1." -> "1.1.1.1"). A CIDR range
    // ("2.188.21.0/24", 1.0.32) is validated (IPv4 + prefix 0-32,
    // no port); (1.0.2) leading zeros are dropped and host bits are
    // cleared ("5.200.14.7/24" -> "5.200.14.0/24"). The Go core
    // expands a range to every address in it (each with :53) when
    // scanning/connecting.
    static func cleanResolverToken(_ token: String) -> String? {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.contains("/") {
            let cidrParts = trimmed.split(separator: "/", omittingEmptySubsequences: false)
            guard cidrParts.count == 2 else { return nil }
            let ipRaw = String(cidrParts[0]).filter { isAsciiDigit($0) || $0 == "." }
                .trimmingCharacters(in: CharacterSet(charactersIn: "."))
            guard let ip = canonicalIpv4(ipRaw) else { return nil }
            let prefix = String(cidrParts[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            // Prefix must be a plain 0-32 number, no port.
            guard !prefix.isEmpty, prefix.count <= 2,
                  prefix.allSatisfy({ isAsciiDigit($0) }),
                  let prefixNum = Int(prefix), (0...32).contains(prefixNum) else {
                return nil
            }
            // (1.0.2): host bits set -> the range's network address,
            // the range the user meant.
            return "\(cidrNetwork(ip, prefixNum))/\(prefixNum)"
        }
        let kept = token.filter { isAsciiDigit($0) || $0 == "." || $0 == ":" }
        let cleaned = kept.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !cleaned.isEmpty else { return nil }
        let parts = cleaned.split(separator: ":", omittingEmptySubsequences: false)
        guard let ip = canonicalIpv4(parts.first.map(String.init) ?? "") else { return nil }
        let portText = parts.count > 1 && !parts[1].isEmpty ? String(parts[1]) : "53"
        guard let port = Int(portText), (1...65535).contains(port) else { return nil }
        return "\(ip):\(port)"
    }
}

extension Array where Element: Hashable {
    func distinctPreservingOrder() -> [Element] {
        var seen = Set<Element>()
        var out: [Element] = []
        for item in self where seen.insert(item).inserted {
            out.append(item)
        }
        return out
    }
}
