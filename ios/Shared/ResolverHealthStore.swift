// ResolverHealthStore.swift — the remembered healthy resolvers
// (1.0.53, item 40): the single Soar-verified pool (~600
// entries), persisted in the App Group UserDefaults so both the
// app and the Network Extension read and write the same list.
//
// Only Soar-verified resolvers are ever stored — a plain-DNS
// "responding" resolver never enters this list. Entries older than
// 7 days are stale and must be re-tested before use, so load()
// never returns them. Sync MERGES, never replaces: a new verified
// set joins the stored pool, so a resolver that drops out of the
// live set is not forgotten — it stays until the 30s probe loop
// evicts it (3 missed probes) or it goes stale (7 days).
//
// Fast reconnect: when starting a tunnel in Built-in mode the top
// 16 (the seed) are handed to the core as the explicit resolvers
// and the rest of the pool goes as Discovery candidates, so
// connect starts from paths known healthy instead of re-scanning
// the whole bundled list. The background probe never blocks
// connect.

import Foundation

/// One remembered healthy resolver: an address ("IP:53") that the
/// tunnel core reported as a Soar-verified path, with the Soar
/// handshake RTT and the time it was verified (Unix milliseconds).
public struct RememberedResolver: Codable, Equatable {
    public var addr: String
    public var rttMs: Int
    public var checkedAtMs: Int64

    public init(addr: String, rttMs: Int, checkedAtMs: Int64) {
        self.addr = addr
        self.rttMs = rttMs
        self.checkedAtMs = checkedAtMs
    }
}

public final class ResolverHealthStore {
    public static let shared = ResolverHealthStore()

    /// Fast-start seed (1.0.53, item 40): the top 16 healthiest
    /// (lowest ping), handed to the Soar client as explicit
    /// Resolvers at connect; the rest of the pool goes as Discovery
    /// candidates. The store itself holds the whole pool.
    public static let seedCount = 16

    /// Entries older than this are stale and re-tested before use.
    public static let staleAfterMs: Int64 = 7 * 24 * 60 * 60 * 1000

    private let healthKey = "healthyResolvers"
    private let lastTestKey = "healthyResolversLastTestMs"
    private let lastBackgroundScanKey = "healthyResolversLastBackgroundScanMs"

    public init() {}

    /// The remembered list, fresh entries only, lowest ping first.
    public func load() -> [RememberedResolver] {
        guard let defaults = AppGroup.defaults,
              let data = defaults.data(forKey: healthKey),
              let entries = try? JSONDecoder().decode([RememberedResolver].self, from: data) else {
            return []
        }
        let nowMs = currentMs()
        return entries.filter { isFresh($0, nowMs: nowMs) }.sorted { $0.rttMs < $1.rttMs }
    }

    /// The fresh remembered addresses, lowest ping first — the list
    /// handed to the core for a fast reconnect in Built-in mode.
    public func freshAddrs() -> [String] {
        return load().map { $0.addr }
    }

    /// When the remembered list was last refreshed (0 = never).
    public func lastTestMs() -> Int64 {
        return AppGroup.defaults?.object(forKey: lastTestKey) as? Int64 ?? 0
    }

    /// When the last silent background re-scan started (0 = never).
    public func lastBackgroundScanMs() -> Int64 {
        return AppGroup.defaults?.object(forKey: lastBackgroundScanKey) as? Int64 ?? 0
    }

    /// Stamp the start of a silent background re-scan.
    public func markBackgroundScan(nowMs: Int64? = nil) {
        AppGroup.defaults?.set(nowMs ?? currentMs(), forKey: lastBackgroundScanKey)
    }

    /// Replace the remembered list with `entries` (sorted by ping,
    /// uncapped — the whole verified pool, 1.0.53) and stamp
    /// lastTestMs. Callers pass only Soar-verified entries — this
    /// store never vets anything itself.
    public func save(_ entries: [RememberedResolver]) {
        guard let defaults = AppGroup.defaults else { return }
        let nowMs = currentMs()
        let trimmed = entries
            .sorted { $0.rttMs < $1.rttMs }
            .map { $0.checkedAtMs > 0 ? $0 : RememberedResolver(addr: $0.addr, rttMs: $0.rttMs, checkedAtMs: nowMs) }
        guard let data = try? JSONEncoder().encode(Array(trimmed)) else { return }
        defaults.set(data, forKey: healthKey)
        defaults.set(nowMs, forKey: lastTestKey)
    }

    /// Merge `entries` into the stored pool (1.0.53, item 40):
    /// a verified entry joins (or refreshes) the pool, and
    /// `evicted` addresses leave it — the 30s probe loop's
    /// 3-miss verdict is the pool's death path. With no evicted
    /// set (a scan's own vetted list) nothing is dropped: a
    /// resolver that drops out of a scan is not forgotten, it
    /// stays until the loop evicts it or it goes stale.
    public func merge(_ entries: [RememberedResolver], evicting evicted: Set<String> = []) {
        var byAddr = load().reduce(into: [String: RememberedResolver]()) { $0[$1.addr] = $1 }
        for addr in evicted {
            byAddr.removeValue(forKey: addr)
        }
        let nowMs = currentMs()
        for entry in entries {
            byAddr[entry.addr] = entry.checkedAtMs > 0
                ? entry
                : RememberedResolver(addr: entry.addr, rttMs: entry.rttMs, checkedAtMs: nowMs)
        }
        save(Array(byAddr.values))
    }

    public func isFresh(_ entry: RememberedResolver, nowMs: Int64? = nil) -> Bool {
        let now = nowMs ?? currentMs()
        return entry.checkedAtMs > 0 && now - entry.checkedAtMs < ResolverHealthStore.staleAfterMs
    }

    private func currentMs() -> Int64 {
        return Int64(Date().timeIntervalSince1970 * 1000)
    }
}
