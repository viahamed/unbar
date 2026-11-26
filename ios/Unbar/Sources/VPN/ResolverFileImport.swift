// ResolverFileImport.swift — (1.0.2) resolver list import.
//
// Port of Android data/datastore/ResolverFileImport.kt. Cleans a .txt
// file of resolver addresses into the same canonical entries the
// Custom field uses ("ip:port" or "network/prefix"), and reports what
// it fixed.
//
// Accepted in the file: single IPv4 addresses (with or without a
// port), CIDR ranges, dash ranges ("a.b.c.d-a.b.c.e"), separated by
// new lines, commas, semicolons, spaces or tabs (Persian separators
// and digits too). Text around an address ("DNS: 1.1.1.1") is
// ignored; "#" and "//" start a comment. Anything without a valid
// IPv4 address (hostnames, IPv6, "300.1.2.3") is reported, not kept.
//
// Beyond the Custom field's per-entry cleaning, a file is also
// de-duplicated, single :53 addresses already inside an imported
// range are dropped, ranges inside a larger range are dropped, and
// the whole list is sorted by address.

import Foundation

public struct ResolverImportResult {
    public var entries: [String]
    public var totalAddresses: Int
    public var cleaned: Int
    public var duplicates: Int
    public var insideRange: Int
    public var rangesCorrected: Int
    public var dashConverted: Int
    public var ignored: [String]
    public var oversize: [String]

    public var fixedCount: Int {
        return cleaned + duplicates + insideRange + rangesCorrected + dashConverted
    }
}

public enum ResolverFileImport {
    public static let maxBytes = 1_048_576
    public static let scanCap = 5000
    /// /20 = 4096 addresses, same as the core.
    static let maxRangeHostBits = 12

    // ASCII-only classes (Android's Java regex \d and \s are ASCII;
    // ICU's are Unicode-wide).
    private static let ws = "[ \\t\\n\\x0B\\f\\r]"
    private static let addressInTextPattern =
        "(?<![0-9])[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:" + ws + "*[-\\u2013\\u2014]" + ws
        + "*[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|" + ws + "*/" + ws + "*[0-9]{1,2}|:[0-9]{1,5})?(?![0-9])"
    private static let addressInText = try? NSRegularExpression(pattern: addressInTextPattern, options: [])

    /// Decodes file bytes: UTF-8 (with or without BOM) or UTF-16 with
    /// a BOM. Invalid UTF-8 is decoded leniently (replacement
    /// characters), like Android's String(bytes, UTF_8).
    public static func decode(_ data: Data) -> String {
        let bytes = [UInt8](data)
        if bytes.count >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE {
            return String(data: Data(bytes[2...]), encoding: .utf16LittleEndian) ?? ""
        }
        if bytes.count >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
            return String(data: Data(bytes[2...]), encoding: .utf16BigEndian) ?? ""
        }
        if bytes.count >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF {
            return String(decoding: bytes[3...], as: UTF8.self)
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    private struct Parsed {
        let entry: String
        let start: Int64
        let end: Int64
        let prefix: Int?
        let port: Int
    }

    private static func parseEntry(_ e: String) -> Parsed? {
        if let slash = e.firstIndex(of: "/") {
            let ip = String(e[..<slash])
            guard let prefix = Int(e[e.index(after: slash)...]) else { return nil }
            let start = ResolverNormalization.ipv4ToInt(ip)
            return Parsed(entry: e, start: start, end: start + (Int64(1) << Int64(32 - prefix)) - 1,
                          prefix: prefix, port: 53)
        }
        let ip: String
        let port: Int
        if let colon = e.firstIndex(of: ":") {
            ip = String(e[..<colon])
            port = Int(e[e.index(after: colon)...]) ?? 53
        } else {
            ip = e
            port = 53
        }
        let v = ResolverNormalization.ipv4ToInt(ip)
        return Parsed(entry: e, start: v, end: v, prefix: nil, port: port)
    }

    private static func before(_ s: String, _ c: Character) -> String {
        guard let i = s.firstIndex(of: c) else { return s }
        return String(s[..<i])
    }

    /// Lines as Kotlin's lineSequence(): split at \r\n, \n or \r.
    private static func lines(_ text: String) -> [String] {
        return text.replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .flatMap { $0.split(separator: "\r", omittingEmptySubsequences: false) }
            .map(String.init)
    }

    public static func clean(_ text: String) -> ResolverImportResult {
        var cleaned = 0
        var rangesCorrected = 0
        var dashConverted = 0
        var ignored: [String] = []
        var raw: [String] = []

        func addIgnored(_ s: String) {
            if !ignored.contains(s) { ignored.append(s) }
        }

        for rawLine in lines(ResolverNormalization.asciiResolverText(text)) {
            var line = rawLine
            if let hash = line.firstIndex(of: "#") { line = String(line[..<hash]) }
            if let slashes = line.range(of: "//") { line = String(line[..<slashes.lowerBound]) }
            line = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }

            guard let regex = addressInText else { continue }
            let ns = line as NSString
            let full = NSRange(location: 0, length: ns.length)
            let matches = regex.matches(in: line, options: [], range: full)
            if matches.isEmpty {
                addIgnored(String(line.prefix(40)))
                continue
            }
            // Leftover words around the addresses count as a cleaned line.
            let leftover = regex.stringByReplacingMatches(in: line, options: [], range: full, withTemplate: " ")
                .filter { $0 != "," && $0 != ";" && !$0.isWhitespace }
            if !leftover.isEmpty { cleaned += 1 }

            for m in matches {
                let value = ns.substring(with: m.range)
                let token = value.replacingOccurrences(of: " ", with: "")
                    .replacingOccurrences(of: "\t", with: "")
                if token.contains("-") || token.contains("\u{2013}") || token.contains("\u{2014}") {
                    let expanded = ResolverNormalization.normalizeResolvers(
                        ResolverNormalization.expandDashRanges(token))
                    if expanded.isEmpty { addIgnored(String(value.prefix(40))); continue }
                    dashConverted += 1
                    raw.append(contentsOf: expanded)
                    continue
                }
                let out = ResolverNormalization.normalizeResolvers(token)
                guard let entry = out.first else { addIgnored(String(value.prefix(40))); continue }
                if token.contains("/") {
                    if before(entry, "/") != before(token, "/") {
                        let ipPart = ResolverNormalization.canonicalIpv4(before(token, "/"))
                        if let ipPart = ipPart, ipPart != before(entry, "/") {
                            rangesCorrected += 1
                        } else {
                            cleaned += 1
                        }
                    }
                } else if entry != token && entry != token + ":53" {
                    cleaned += 1
                }
                raw.append(entry)
            }
        }

        let distinct = raw.distinctPreservingOrder()
        let duplicates = raw.count - distinct.count

        let parsed = distinct.compactMap { parseEntry($0) }
        // Ranges, widest first: keep a range unless a kept range covers it.
        var keptRanges: [Parsed] = []
        let ranges = parsed.filter { $0.prefix != nil }.sorted {
            ($0.prefix ?? 0, $0.start) < ($1.prefix ?? 0, $1.start)
        }
        for r in ranges where !keptRanges.contains(where: { $0.start <= r.start && r.end <= $0.end }) {
            keptRanges.append(r)
        }
        let scannable = keptRanges.filter { 32 - ($0.prefix ?? 32) <= maxRangeHostBits }
        let singles = parsed.filter { $0.prefix == nil }
        // A single :53 address inside a scannable range is scanned anyway.
        let keptSingles = singles.filter { s in
            !(s.port == 53 && scannable.contains(where: { $0.start <= s.start && s.start <= $0.end }))
        }
        let insideRange = (ranges.count - keptRanges.count) + (singles.count - keptSingles.count)

        let result = (keptRanges + keptSingles).sorted {
            ($0.start, $0.prefix ?? 33, $0.port) < ($1.start, $1.prefix ?? 33, $1.port)
        }

        var total: Int64 = 0
        for p in result {
            if let prefix = p.prefix {
                if 32 - prefix <= maxRangeHostBits { total += Int64(1) << Int64(32 - prefix) }
            } else {
                total += 1
            }
        }
        let oversize = result.filter { p in
            guard let prefix = p.prefix else { return false }
            return 32 - prefix > maxRangeHostBits
        }.map { $0.entry }

        return ResolverImportResult(
            entries: result.map { $0.entry },
            totalAddresses: Int(min(total, Int64(Int32.max))),
            cleaned: cleaned,
            duplicates: duplicates,
            insideRange: insideRange,
            rangesCorrected: rangesCorrected,
            dashConverted: dashConverted,
            ignored: ignored,
            oversize: oversize
        )
    }

    /// Real address count of an already-clean list (no 5000 cap).
    public static func addressCount(_ entries: [String]) -> Int {
        var total: Int64 = 0
        for e in entries {
            guard let slash = e.firstIndex(of: "/") else { total += 1; continue }
            guard let prefix = Int(e[e.index(after: slash)...]) else { continue }
            if 32 - prefix <= maxRangeHostBits { total += Int64(1) << Int64(32 - prefix) }
        }
        return Int(min(total, Int64(Int32.max)))
    }

    public enum ReadError: Error {
        case tooLarge
        case unreadable
    }

    /// Reads a picked file (security-scoped URL from .fileImporter),
    /// refusing anything over 1 MB, and cleans it. Call off the main
    /// thread.
    public static func read(url: URL) -> Result<(fileName: String, result: ResolverImportResult), ReadError> {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var name = url.lastPathComponent
        if name.isEmpty { name = "resolvers.txt" }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .failure(.unreadable) }
        defer { handle.closeFile() }
        // Read at most maxBytes + 1: one byte over means too large.
        let data = handle.readData(ofLength: maxBytes + 1)
        if data.count > maxBytes { return .failure(.tooLarge) }
        return .success((fileName: name, result: clean(decode(data))))
    }
}
