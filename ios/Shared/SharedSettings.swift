// SharedSettings.swift — code shared by the Unbar app target and the
// UnbarTunnel Network Extension target.
//
// Everything the two processes exchange goes through the App Group
// (group.org.breakthebarriers.unbar): the app writes the tunnel
// configuration, the extension writes live stats and log lines. Nothing
// here is a secret: the SOCKS password is NOT stored here — it lives in
// the iOS Keychain (app side) and is only handed to the extension inside
// the VPN provider configuration when the tunnel starts. Config
// import/export never includes it either.

import Foundation

/// The App Group shared by the app and the Network Extension.
public enum AppGroup {
    public static let identifier = "group.org.breakthebarriers.unbar"

    public static var defaults: UserDefaults? {
        return UserDefaults(suiteName: identifier)
    }
}

/// Keys used in the shared UserDefaults.
public enum SharedKey {
    /// JSON-encoded [Server] — the user's servers (zone + public key).
    public static let servers = "servers"
    /// UUID string of the active server.
    public static let activeServerId = "activeServerId"
    /// JSON-encoded AppSettingsSnapshot.
    public static let settings = "settings"
    /// JSON-encoded [Subscription], written by the app.
    public static let subscriptions = "subscriptions"
    /// JSON-encoded StatsSnapshot, written by the extension ~once a second.
    public static let stats = "stats"
    /// Unix time of the last stats write.
    public static let statsUpdatedAt = "statsUpdatedAt"
    /// [String] log lines, newest last, capped by SharedLog.
    public static let logLines = "logLines"
    /// Last connection error message, for the status card.
    public static let lastError = "lastError"
}

/// Resolver selection: the bundled list, or the user's own resolvers.
/// Built-in uses the bundled Iranian resolvers through discovery; Custom
/// uses only the resolvers typed in the settings field. (1.0.2) Import
/// uses only the list imported from a .txt file: it behaves exactly
/// like Custom (no discovery, only its own list is scanned and used)
/// but keeps its own stored list, so switching between Custom and
/// Import never loses either one. The extension treats every mode
/// other than "builtIn" the same way, so it needs no change for it.
public enum ResolverMode: String, Codable, CaseIterable {
    case builtIn
    case custom
    case imported = "import"

    /// Custom and Import: the user's own list, no discovery.
    public var usesOwnList: Bool {
        return self != .builtIn
    }
}

/// VPN or SOCKS. VPN (Network Extension) is primary on iOS; SOCKS is
/// secondary — iOS backgrounding limits what a loopback SOCKS proxy can
/// do for other apps, so the UI says so.
public enum ConnectionMode: String, Codable, CaseIterable {
    case vpn
    case socks
}

/// Everything the extension needs to start the tunnel, encoded as a
/// property-list dictionary in the NETunnelProviderProtocol
/// providerConfiguration. Keys are plain strings so both sides can read
/// them without sharing a binary format.
public struct TunnelConfig: Codable {
    public var zone: String
    public var serverKeyB64: String
    public var resolverMode: String          // "builtIn" | "custom" | "import"
    public var resolvers: String             // comma-separated "host:port", "" = bundled list via discovery
    public var discoveryEnabled: Bool
    /// (1.0.53, item 40): the rest of the remembered pool (pool
    /// minus the fast-start seed), comma-separated — the extension
    /// hands them to the core as Discovery candidates before
    /// Start. Empty in Custom mode (no pool) and when the pool
    /// holds only the seed.
    public var candidates: String
    /// (1.0.53, item 40): the list the Go core's 30s loop
    /// re-probes — the whole remembered pool in Built-in mode
    /// (it rotates 16 per round), the user's own resolvers in
    /// Custom mode.
    public var probeResolvers: String
    public var country: String               // ISO alpha-2, e.g. "IR"
    public var tcpMode: String               // "auto" | "on"
    public var mtu: Int                      // TUN MTU: 0 = Auto (measure interface MTU at connect), else 1280/1400/1500
    public var maxAnswer: Int                // DNS answer-size cap: 0 = Auto (probe up to 1232), else 512/768/1024/1232
    public var mode: String                  // "vpn" | "socks"
    public var socksPort: Int
    public var socksUsername: String
    public var socksPassword: String         // only present while starting; never exported
    public var bandwidthUp: Int64            // bytes/sec, 0 = unlimited
    public var bandwidthDown: Int64          // bytes/sec, 0 = unlimited
    public var geoBypass: Bool
    public var bypassDomains: String         // comma-separated

    public init(zone: String, serverKeyB64: String, resolverMode: String,
                resolvers: String, discoveryEnabled: Bool, country: String,
                tcpMode: String, mtu: Int, maxAnswer: Int = 0, mode: String, socksPort: Int,
                socksUsername: String, socksPassword: String,
                bandwidthUp: Int64, bandwidthDown: Int64,
                geoBypass: Bool, bypassDomains: String,
                candidates: String = "", probeResolvers: String = "") {
        self.zone = zone
        self.serverKeyB64 = serverKeyB64
        self.resolverMode = resolverMode
        self.resolvers = resolvers
        self.discoveryEnabled = discoveryEnabled
        self.candidates = candidates
        self.probeResolvers = probeResolvers
        self.country = country
        self.tcpMode = tcpMode
        self.mtu = mtu
        self.maxAnswer = maxAnswer
        self.mode = mode
        self.socksPort = socksPort
        self.socksUsername = socksUsername
        self.socksPassword = socksPassword
        self.bandwidthUp = bandwidthUp
        self.bandwidthDown = bandwidthDown
        self.geoBypass = geoBypass
        self.bypassDomains = bypassDomains
    }

    /// Property-list dictionary for NETunnelProviderProtocol.
    public func asDictionary() -> [String: Any] {
        return [
            "zone": zone,
            "serverKeyB64": serverKeyB64,
            "resolverMode": resolverMode,
            "resolvers": resolvers,
            "discoveryEnabled": discoveryEnabled,
            "candidates": candidates,
            "probeResolvers": probeResolvers,
            "country": country,
            "tcpMode": tcpMode,
            "mtu": mtu,
            "maxAnswer": maxAnswer,
            "mode": mode,
            "socksPort": socksPort,
            "socksUsername": socksUsername,
            "socksPassword": socksPassword,
            "bandwidthUp": bandwidthUp,
            "bandwidthDown": bandwidthDown,
            "geoBypass": geoBypass,
            "bypassDomains": bypassDomains
        ]
    }

    public static func fromDictionary(_ dict: [String: Any]) -> TunnelConfig? {
        guard let zone = dict["zone"] as? String,
              let serverKeyB64 = dict["serverKeyB64"] as? String else {
            return nil
        }
        return TunnelConfig(
            zone: zone,
            serverKeyB64: serverKeyB64,
            resolverMode: dict["resolverMode"] as? String ?? "builtIn",
            resolvers: dict["resolvers"] as? String ?? "",
            discoveryEnabled: dict["discoveryEnabled"] as? Bool ?? true,
            country: dict["country"] as? String ?? "IR",
            tcpMode: dict["tcpMode"] as? String ?? "auto",
            mtu: (dict["mtu"] as? NSNumber)?.intValue ?? 0,
            maxAnswer: (dict["maxAnswer"] as? NSNumber)?.intValue ?? 0,
            mode: dict["mode"] as? String ?? "vpn",
            socksPort: dict["socksPort"] as? Int ?? 1080,
            socksUsername: dict["socksUsername"] as? String ?? "",
            socksPassword: dict["socksPassword"] as? String ?? "",
            bandwidthUp: (dict["bandwidthUp"] as? NSNumber)?.int64Value ?? 0,
            bandwidthDown: (dict["bandwidthDown"] as? NSNumber)?.int64Value ?? 0,
            geoBypass: dict["geoBypass"] as? Bool ?? true,
            bypassDomains: dict["bypassDomains"] as? String ?? "",
            candidates: dict["candidates"] as? String ?? "",
            probeResolvers: dict["probeResolvers"] as? String ?? ""
        )
    }
}

/// One Soar-verified resolver path, as reported by the core's
/// Stats() "verifiedResolvers" array: the address ("IP:53") with the
/// tunnel client's own smoothed round-trip time for that path, in ms.
/// Only Soar-verified paths ever enter the remembered healthy list —
/// a plain-DNS reply alone never counts as healthy.
public struct VerifiedResolver: Codable, Equatable {
    public var addr: String
    public var rttMs: Int64

    public init(addr: String, rttMs: Int64) {
        self.addr = addr
        self.rttMs = rttMs
    }
}

/// One failed SOCKS dial from the core (diagnostic): sequence number
/// plus "addr: error", from Stats() "dialErrors".
public struct DialErrorEntry: Codable, Equatable {
    public var seq: Int64
    public var msg: String

    public init(seq: Int64, msg: String) {
        self.seq = seq
        self.msg = msg
    }
}

/// One tunnel resolver path, as reported by the core's Stats()
/// "pathStats" array (diagnostic). `rttMs` is converted from the
/// core's time.Duration (nanoseconds). `lastSuccess` is "" when the
/// path has never carried an authenticated answer.
public struct SoarPathStat: Codable, Equatable {
    public var resolver: String
    public var transport: String
    public var verified: Bool
    public var suspended: Bool
    public var rttMs: Int64
    public var loss: Double
    public var inflight: Int
    public var window: Double
    public var nameLen: Int
    public var answerLimit: Int
    public var successes: Int
    public var lastSuccess: String

    public init(resolver: String = "", transport: String = "", verified: Bool = false,
                suspended: Bool = false, rttMs: Int64 = 0, loss: Double = 0,
                inflight: Int = 0, window: Double = 0, nameLen: Int = 0,
                answerLimit: Int = 0, successes: Int = 0, lastSuccess: String = "") {
        self.resolver = resolver
        self.transport = transport
        self.verified = verified
        self.suspended = suspended
        self.rttMs = rttMs
        self.loss = loss
        self.inflight = inflight
        self.window = window
        self.nameLen = nameLen
        self.answerLimit = answerLimit
        self.successes = successes
        self.lastSuccess = lastSuccess
    }
}

/// Live counters written by the extension (parsed from Core Tunnel.Stats()
/// JSON) and read by the app once a second. `up`/`down` are cumulative
/// bytes since the tunnel started; the app derives speeds from deltas.
/// Keys mirror the Go core's Stats() JSON exactly: "up", "down",
/// "rttMs", "state", "serverIP", "serverCountry", "geoError",
/// "healthyResolvers", "totalResolvers", "respondingResolvers",
/// "testedResolvers", "verifiedResolvers", "verifiedCount", "scanRunning", "dialErrors",
/// "activeStreams", "maxStreams", "socksUp", "socksDown",
/// "pipeStallUp", "pipeStallDown", "pathStats", "established",
/// "upLimit", "downLimit", "probeResolvers". Missing keys (older core) default to
/// zero/empty/false, never a crash.
public struct StatsSnapshot: Codable {
    public var up: Int64
    public var down: Int64
    public var rttMs: Int64
    public var state: String        // "stopped" | "connecting" | "connected"
    public var serverIP: String
    public var serverCountry: String
    public var geoError: String
    public var healthyResolvers: Int   // Soar-verified count (never plain-DNS alone)
    public var totalResolvers: Int
    public var respondingResolvers: Int  // stage-1 plain-DNS count (weak signal)
    /// Probes completed in the current/most recent full scan
    /// (0 before any scan). Drives the live "tested X / total" of
    /// the scan stepper.
    public var testedResolvers: Int
    public var verifiedResolvers: [VerifiedResolver]
    public var verifiedCount: Int
    /// (1.0.33): the last scan's OWN Soar-vetted resolvers —
    /// the stage-2 result, independent of whether the tunnel
    /// is connected. verifiedResolvers/verifiedCount stay the
    /// LIVE client's paths (connection health); scanVerified*
    /// is what the scan stepper's stage 2 shows and what gets
    /// saved to the remembered list after a scan.
    public var scanVerifiedResolvers: [VerifiedResolver]
    public var scanVerifiedCount: Int
    // Background healthy probe: size of the Go core's background
    // probe list (the pool, minus any resolver evicted after 3
    // missed probes in a row). 0 on an older core (field missing).
    public var healthyProbeCount: Int
    /// (1.0.53, item 40): the Go core's current probe list itself
    /// (the pool minus evicted resolvers), from Stats()
    /// "probeResolvers" — the merge drops stored resolvers that
    /// were handed to the core but are no longer in this list.
    /// Empty on an older core: evict nothing.
    public var probeResolvers: [String]
    public var scanRunning: Bool
    /// (1.0.32): true while a running full scan is paused
    /// (Pause button). Defaults to false on an older snapshot.
    public var scanPaused: Bool
    public var dialErrors: [DialErrorEntry]
    public var activeStreams: Int
    public var maxStreams: Int
    public var socksUp: Int64
    public var socksDown: Int64
    public var pipeStallUp: Bool
    public var pipeStallDown: Bool
    public var pathStats: [SoarPathStat]
    public var established: Bool
    public var upLimit: Int64
    public var downLimit: Int64
    public var updatedAt: TimeInterval

    public init(up: Int64, down: Int64, rttMs: Int64, state: String,
                serverIP: String, updatedAt: TimeInterval,
                serverCountry: String = "", geoError: String = "",
                healthyResolvers: Int = 0, totalResolvers: Int = 0,
                respondingResolvers: Int = 0,
                testedResolvers: Int = 0,
                verifiedResolvers: [VerifiedResolver] = [],
                verifiedCount: Int = 0,
                scanVerifiedResolvers: [VerifiedResolver] = [],
                scanVerifiedCount: Int = 0,
                healthyProbeCount: Int = 0, probeResolvers: [String] = [],
                scanRunning: Bool = false,
                scanPaused: Bool = false,
                dialErrors: [DialErrorEntry] = [],
                activeStreams: Int = 0, maxStreams: Int = 0,
                socksUp: Int64 = 0, socksDown: Int64 = 0,
                pipeStallUp: Bool = false, pipeStallDown: Bool = false,
                pathStats: [SoarPathStat] = [], established: Bool = false,
                upLimit: Int64 = 0, downLimit: Int64 = 0) {
        self.up = up
        self.down = down
        self.rttMs = rttMs
        self.state = state
        self.serverIP = serverIP
        self.serverCountry = serverCountry
        self.geoError = geoError
        self.healthyResolvers = healthyResolvers
        self.totalResolvers = totalResolvers
        self.respondingResolvers = respondingResolvers
        self.testedResolvers = testedResolvers
        self.verifiedResolvers = verifiedResolvers
        self.verifiedCount = verifiedCount
        self.scanVerifiedResolvers = scanVerifiedResolvers
        self.scanVerifiedCount = scanVerifiedCount
        self.healthyProbeCount = healthyProbeCount
        self.probeResolvers = probeResolvers
        self.scanRunning = scanRunning
        self.scanPaused = scanPaused
        self.dialErrors = dialErrors
        self.activeStreams = activeStreams
        self.maxStreams = maxStreams
        self.socksUp = socksUp
        self.socksDown = socksDown
        self.pipeStallUp = pipeStallUp
        self.pipeStallDown = pipeStallDown
        self.pathStats = pathStats
        self.established = established
        self.upLimit = upLimit
        self.downLimit = downLimit
        self.updatedAt = updatedAt
    }

    public static func fromStatsJSON(_ json: String) -> StatsSnapshot? {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
            return nil
        }
        func int64(_ key: String) -> Int64 {
            return (obj[key] as? NSNumber)?.int64Value ?? 0
        }
        func int(_ key: String) -> Int {
            return (obj[key] as? NSNumber)?.intValue ?? 0
        }
        func str(_ key: String) -> String {
            return (obj[key] as? String) ?? ""
        }
        func bool(_ key: String) -> Bool {
            return (obj[key] as? Bool) ?? false
        }
        // (1.0.51) True wire payload bytes ("wireUp"/"wireDown":
        // acked upstream + data-answer downstream) when the core
        // reports them; fall back to the countingConn totals
        // ("up"/"down") on an older core.
        func wire(_ wireKey: String, _ legacyKey: String) -> Int64 {
            return (obj[wireKey] as? NSNumber)?.int64Value ?? int64(legacyKey)
        }
        var verified: [VerifiedResolver] = []
        if let arr = obj["verifiedResolvers"] as? [[String: Any]] {
            for item in arr {
                guard let addr = item["addr"] as? String, !addr.isEmpty else { continue }
                verified.append(VerifiedResolver(
                    addr: addr,
                    rttMs: (item["rttMs"] as? NSNumber)?.int64Value ?? 0))
            }
        }
        // (1.0.33): the last scan's own vetted list, same shape.
        var scanVerified: [VerifiedResolver] = []
        if let arr = obj["scanVerified"] as? [[String: Any]] {
            for item in arr {
                guard let addr = item["addr"] as? String, !addr.isEmpty else { continue }
                scanVerified.append(VerifiedResolver(
                    addr: addr,
                    rttMs: (item["rttMs"] as? NSNumber)?.int64Value ?? 0))
            }
        }
        var dialErrors: [DialErrorEntry] = []
        // (1.0.53, item 40): the core's current probe list, as a
        // plain string array — missing on an older core.
        let probeResolvers = obj["probeResolvers"] as? [String] ?? []
        if let arr = obj["dialErrors"] as? [[String: Any]] {
            for item in arr {
                guard let msg = item["msg"] as? String, !msg.isEmpty,
                      let seq = (item["seq"] as? NSNumber)?.int64Value else { continue }
                dialErrors.append(DialErrorEntry(seq: seq, msg: msg))
            }
        }
        var pathStats: [SoarPathStat] = []
        if let arr = obj["pathStats"] as? [[String: Any]] {
            for item in arr {
                let lastSuccess = (item["last_success"] as? String ?? "")
                pathStats.append(SoarPathStat(
                    resolver: item["resolver"] as? String ?? "",
                    transport: item["transport"] as? String ?? "",
                    verified: item["verified"] as? Bool ?? false,
                    suspended: item["suspended"] as? Bool ?? false,
                    // Go time.Duration is nanoseconds.
                    rttMs: ((item["rtt"] as? NSNumber)?.int64Value ?? 0) / 1_000_000,
                    loss: (item["loss"] as? NSNumber)?.doubleValue ?? 0,
                    inflight: (item["inflight"] as? NSNumber)?.intValue ?? 0,
                    window: (item["window"] as? NSNumber)?.doubleValue ?? 0,
                    nameLen: (item["name_len"] as? NSNumber)?.intValue ?? 0,
                    answerLimit: (item["answer_limit"] as? NSNumber)?.intValue ?? 0,
                    successes: (item["successes"] as? NSNumber)?.intValue ?? 0,
                    lastSuccess: lastSuccess.hasPrefix("0001") ? "" : lastSuccess))
            }
        }
        return StatsSnapshot(
            up: wire("wireUp", "up"),
            down: wire("wireDown", "down"),
            rttMs: int64("rttMs"),
            state: str("state").isEmpty ? "stopped" : str("state"),
            serverIP: str("serverIP"),
            updatedAt: Date().timeIntervalSince1970,
            serverCountry: str("serverCountry"),
            geoError: str("geoError"),
            healthyResolvers: int("healthyResolvers"),
            totalResolvers: int("totalResolvers"),
            respondingResolvers: int("respondingResolvers"),
            testedResolvers: int("testedResolvers"),
            verifiedResolvers: verified,
            verifiedCount: int("verifiedCount"),
            scanVerifiedResolvers: scanVerified,
            scanVerifiedCount: int("scanVerifiedCount"),
            healthyProbeCount: int("healthyProbeCount"),
            probeResolvers: probeResolvers,
            scanRunning: bool("scanRunning"),
            scanPaused: bool("scanPaused"),
            dialErrors: dialErrors,
            activeStreams: int("activeStreams"),
            maxStreams: int("maxStreams"),
            socksUp: int64("socksUp"),
            socksDown: int64("socksDown"),
            pipeStallUp: bool("pipeStallUp"),
            pipeStallDown: bool("pipeStallDown"),
            pathStats: pathStats,
            established: bool("established"),
            upLimit: int64("upLimit"),
            downLimit: int64("downLimit")
        )
    }

    public func encode() -> Data? {
        return try? JSONEncoder().encode(self)
    }

    public static func decode(_ data: Data) -> StatsSnapshot? {
        return try? JSONDecoder().decode(StatsSnapshot.self, from: data)
    }

    // Tolerant decode: a snapshot written by an older build lacks the
    // newer keys; those default instead of failing the whole decode.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        up = try c.decodeIfPresent(Int64.self, forKey: .up) ?? 0
        down = try c.decodeIfPresent(Int64.self, forKey: .down) ?? 0
        rttMs = try c.decodeIfPresent(Int64.self, forKey: .rttMs) ?? 0
        state = try c.decodeIfPresent(String.self, forKey: .state) ?? "stopped"
        serverIP = try c.decodeIfPresent(String.self, forKey: .serverIP) ?? ""
        serverCountry = try c.decodeIfPresent(String.self, forKey: .serverCountry) ?? ""
        geoError = try c.decodeIfPresent(String.self, forKey: .geoError) ?? ""
        healthyResolvers = try c.decodeIfPresent(Int.self, forKey: .healthyResolvers) ?? 0
        totalResolvers = try c.decodeIfPresent(Int.self, forKey: .totalResolvers) ?? 0
        respondingResolvers = try c.decodeIfPresent(Int.self, forKey: .respondingResolvers) ?? 0
        testedResolvers = try c.decodeIfPresent(Int.self, forKey: .testedResolvers) ?? 0
        verifiedResolvers = try c.decodeIfPresent([VerifiedResolver].self, forKey: .verifiedResolvers) ?? []
        verifiedCount = try c.decodeIfPresent(Int.self, forKey: .verifiedCount) ?? 0
        // (1.0.33): the last scan's own vetted list — added with
        // the properties; the decoder must init them too, or
        // the compiler rejects the initializer.
        scanVerifiedResolvers = try c.decodeIfPresent([VerifiedResolver].self, forKey: .scanVerifiedResolvers) ?? []
        scanVerifiedCount = try c.decodeIfPresent(Int.self, forKey: .scanVerifiedCount) ?? 0
        healthyProbeCount = try c.decodeIfPresent(Int.self, forKey: .healthyProbeCount) ?? 0
        probeResolvers = try c.decodeIfPresent([String].self, forKey: .probeResolvers) ?? []
        scanRunning = try c.decodeIfPresent(Bool.self, forKey: .scanRunning) ?? false
        scanPaused = try c.decodeIfPresent(Bool.self, forKey: .scanPaused) ?? false
        dialErrors = try c.decodeIfPresent([DialErrorEntry].self, forKey: .dialErrors) ?? []
        activeStreams = try c.decodeIfPresent(Int.self, forKey: .activeStreams) ?? 0
        maxStreams = try c.decodeIfPresent(Int.self, forKey: .maxStreams) ?? 0
        socksUp = try c.decodeIfPresent(Int64.self, forKey: .socksUp) ?? 0
        socksDown = try c.decodeIfPresent(Int64.self, forKey: .socksDown) ?? 0
        pipeStallUp = try c.decodeIfPresent(Bool.self, forKey: .pipeStallUp) ?? false
        pipeStallDown = try c.decodeIfPresent(Bool.self, forKey: .pipeStallDown) ?? false
        pathStats = try c.decodeIfPresent([SoarPathStat].self, forKey: .pathStats) ?? []
        established = try c.decodeIfPresent(Bool.self, forKey: .established) ?? false
        upLimit = try c.decodeIfPresent(Int64.self, forKey: .upLimit) ?? 0
        downLimit = try c.decodeIfPresent(Int64.self, forKey: .downLimit) ?? 0
        updatedAt = try c.decodeIfPresent(TimeInterval.self, forKey: .updatedAt) ?? 0
    }
}
