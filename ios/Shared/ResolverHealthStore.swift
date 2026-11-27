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

//
// (1.0.3) Per-network pools: besides the global pool (key
// "healthyResolvers", unchanged and still updated on every merge)
// each network has its own pool under "healthyResolvers@<key>"
// (see NetworkIdentity below). load()/freshAddrs() return the
// current network's pool, falling back to the global pool when it
// is empty or the network has no stable id. At most 8 network
// pools are kept (least recently used dropped).

import Foundation
import Darwin
import CryptoKit
#if canImport(CoreTelephony)
import CoreTelephony
#endif

/// (1.0.3) Per-network identity for the resolver memory, computed
/// WITHOUT the location permission (the SSID is never read and
/// nothing raw is stored — only a hash):
///  - cellular (pdp_ip*): "cell:<MCC-MNC>" from CTCarrier when iOS
///    still reports it; since iOS 16.4 CTCarrier returns
///    placeholders ("65535"/"--"), so the key becomes "cell:any"
///    (one cellular pool, still separate from Wi-Fi).
///  - Wi-Fi / wired (en*): "wifi:<hash>" — SHA-256 (first 16 hex)
///    of the IPv4 subnet (address AND netmask) and the global IPv6
///    /64 prefix(es) of the interface.
///  - nothing usable -> nil -> the global pool.
public enum NetworkIdentity {

    /// The key for the interface named `interfaceName` (from
    /// NWPathMonitor availableInterfaces), or — when nil — for the
    /// interface iOS most likely uses (en0 with an address, else
    /// pdp_ip0). Both the app and the extension compute the same
    /// key for the same network.
    public static func currentKey(interfaceName: String? = nil) -> String? {
        let addrs = interfaceAddresses()
        let name: String?
        if let given = interfaceName, addrs[given] != nil {
            name = given
        } else if addrs["en0"] != nil {
            name = "en0"
        } else {
            name = addrs.keys.filter { $0.hasPrefix("pdp_ip") }.sorted().first
                ?? addrs.keys.filter { $0.hasPrefix("en") }.sorted().first
        }
        guard let ifname = name, let parts = addrs[ifname] else { return nil }
        if ifname.hasPrefix("pdp_ip") {
            return "cell:" + (carrierCode() ?? "any")
        }
        guard !parts.isEmpty else { return nil }
        let joined = parts.sorted().joined(separator: "|")
        let digest = SHA256.hash(data: Data(joined.utf8))
        let hex = digest.prefix(8).map { String(format: "%02x", $0) }.joined()
        return "wifi:" + hex
    }

    /// Interface name -> identity parts (IPv4 subnet, IPv6 /64).
    /// Loopback, utun/ipsec (VPN) and link-local are skipped.
    private static func interfaceAddresses() -> [String: Set<String>] {
        var out: [String: Set<String>] = [:]
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return out }
        defer { freeifaddrs(ifaddr) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ptr = cursor {
            let ifa = ptr.pointee
            cursor = ifa.ifa_next
            let name = String(cString: ifa.ifa_name)
            guard !name.hasPrefix("lo"), !name.hasPrefix("utun"), !name.hasPrefix("ipsec"),
                  let sa = ifa.ifa_addr else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0 else { continue }
            if sa.pointee.sa_family == UInt8(AF_INET), let mask = ifa.ifa_netmask {
                let a = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                let m = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                let net = UInt32(bigEndian: a & m)
                let bits = UInt32(bigEndian: m).nonzeroBitCount
                let dotted = "\(net >> 24).\((net >> 16) & 255).\((net >> 8) & 255).\(net & 255)"
                out[name, default: []].insert("v4=\(dotted)/\(bits)")
            } else if sa.pointee.sa_family == UInt8(AF_INET6) {
                let bytes: [UInt8] = sa.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) {
                    var addr = $0.pointee.sin6_addr
                    return withUnsafeBytes(of: &addr) { Array($0) }
                }
                guard bytes.count == 16 else { continue }
                // Skip link-local fe80::/10.
                if bytes[0] == 0xfe && (bytes[1] & 0xc0) == 0x80 { continue }
                let prefix = bytes.prefix(8).map { String(format: "%02x", $0) }.joined()
                out[name, default: []].insert("p6=" + prefix)
            } else if out[name] == nil {
                out[name] = []
            }
        }
        // An interface with no IPv4/IPv6 address is not in use.
        return out.filter { !$0.value.isEmpty }
    }

    private static func carrierCode() -> String? {
        #if canImport(CoreTelephony)
        let info = CTTelephonyNetworkInfo()
        var carrier: CTCarrier?
        if #available(iOS 13.0, *), let id = info.dataServiceIdentifier {
            carrier = info.serviceSubscriberCellularProviders?[id]
        }
        if carrier == nil {
            carrier = info.serviceSubscriberCellularProviders?.values.first
        }
        guard let mcc = carrier?.mobileCountryCode, let mnc = carrier?.mobileNetworkCode,
              mcc.count == 3, (2...3).contains(mnc.count),
              mcc.allSatisfy({ $0.isNumber }), mnc.allSatisfy({ $0.isNumber }) else {
            return nil
        }
        return mcc + mnc
        #else
        return nil
        #endif
    }
}

/// One remembered healthy resolver: an address ("IP:53") that the
/// tunnel core reported as a Soar-verified path, with the Soar
/// handshake RTT and the time it was verified (Unix milliseconds).
public struct RememberedResolver: Codable, Equatable {
    public var addr: String
    public var rttMs: Int
    public var checkedAtMs: Int64
    /// (1.0.3): which transports verified this resolver — "udp",
    /// "tcp" or "udp+tcp". Optional, so entries saved before 1.0.3
    /// (no "transport" key) still decode; nil shows no tag.
    public var transport: String?

    public init(addr: String, rttMs: Int, checkedAtMs: Int64, transport: String? = nil) {
        self.addr = addr
        self.rttMs = rttMs
        self.checkedAtMs = checkedAtMs
        self.transport = transport
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

    /// (1.0.3): how many per-network pools are kept (LRU).
    public static let maxNetworkPools = 8

    /// (1.0.3): the extension sets this from its NWPathMonitor
    /// (the active interface name); nil -> NetworkIdentity guesses.
    public var interfaceNameOverride: String?

    private let healthKey = "healthyResolvers"
    private let networkIndexKey = "healthyResolversNetworkIndex"
    private let lastTestKey = "healthyResolversLastTestMs"
    private let lastBackgroundScanKey = "healthyResolversLastBackgroundScanMs"

    public init() {}

    /// (1.0.3): the current network's key (nil = global only).
    public func currentNetworkKey() -> String? {
        return NetworkIdentity.currentKey(interfaceName: interfaceNameOverride)
    }

    private func poolKey(_ networkKey: String) -> String {
        return healthKey + "@" + networkKey
    }

    private func decode(_ key: String) -> [RememberedResolver] {
        guard let defaults = AppGroup.defaults,
              let data = defaults.data(forKey: key),
              let entries = try? JSONDecoder().decode([RememberedResolver].self, from: data) else {
            return []
        }
        let nowMs = currentMs()
        return entries.filter { isFresh($0, nowMs: nowMs) }.sorted { $0.rttMs < $1.rttMs }
    }

    /// The effective remembered list — the current network's own
    /// pool, else the global pool (1.0.3) — fresh entries only,
    /// lowest ping first.
    public func load() -> [RememberedResolver] {
        if let key = currentNetworkKey() {
            let own = decode(poolKey(key))
            if !own.isEmpty { return own }
        }
        return decode(healthKey)
    }

    /// (1.0.3): the global pool only.
    public func loadGlobal() -> [RememberedResolver] {
        return decode(healthKey)
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
            .map { $0.checkedAtMs > 0 ? $0 : RememberedResolver(addr: $0.addr, rttMs: $0.rttMs, checkedAtMs: nowMs, transport: $0.transport) }
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
        let nowMs = currentMs()
        func merged(_ base: [RememberedResolver]) -> [RememberedResolver] {
            var byAddr = base.reduce(into: [String: RememberedResolver]()) { $0[$1.addr] = $1 }
            for addr in evicted {
                byAddr.removeValue(forKey: addr)
            }
            for entry in entries {
                // (1.0.3): a fresh verdict without a transport (older
                // core) keeps the one already stored.
                let transport = (entry.transport?.isEmpty == false) ? entry.transport : byAddr[entry.addr]?.transport
                byAddr[entry.addr] = RememberedResolver(
                    addr: entry.addr,
                    rttMs: entry.rttMs,
                    checkedAtMs: entry.checkedAtMs > 0 ? entry.checkedAtMs : nowMs,
                    transport: transport)
            }
            return Array(byAddr.values)
        }
        // Global pool (unchanged behaviour).
        save(merged(loadGlobal()))
        // (1.0.3): this network's own pool, merged separately so it
        // only ever holds resolvers verified on this network.
        guard let key = currentNetworkKey(), let defaults = AppGroup.defaults else { return }
        let own = merged(decode(poolKey(key))).sorted { $0.rttMs < $1.rttMs }
        if let data = try? JSONEncoder().encode(own) {
            defaults.set(data, forKey: poolKey(key))
        }
        var index = (defaults.dictionary(forKey: networkIndexKey) as? [String: Int64]) ?? [:]
        index[key] = nowMs
        let keep = index.sorted { $0.value > $1.value }.prefix(ResolverHealthStore.maxNetworkPools)
        let keepKeys = Set(keep.map { $0.key })
        for old in index.keys where !keepKeys.contains(old) {
            defaults.removeObject(forKey: poolKey(old))
        }
        defaults.set(Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) }), forKey: networkIndexKey)
    }

    public func isFresh(_ entry: RememberedResolver, nowMs: Int64? = nil) -> Bool {
        let now = nowMs ?? currentMs()
        return entry.checkedAtMs > 0 && now - entry.checkedAtMs < ResolverHealthStore.staleAfterMs
    }

    private func currentMs() -> Int64 {
        return Int64(Date().timeIntervalSince1970 * 1000)
    }
}
