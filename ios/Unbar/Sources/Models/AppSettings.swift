// AppSettings.swift — user settings, stored in the shared App Group
// UserDefaults so the Network Extension reads the same values.
//
// The SOCKS password is NOT stored here: it lives in the iOS Keychain
// (see KeychainHelper) and is only passed to the extension inside the
// VPN provider configuration at connect time. It is never exported.
//
// Every setting auto-saves the moment it changes — there is no Save
// button on any settings section.

import Foundation
import Combine

/// Manual appearance choice. `.system` follows the device.
public enum AppearanceChoice: String, Codable, CaseIterable {
    case system
    case light
    case dark
}

/// In-app language. `.system` follows the device language when it is
/// English or Persian, otherwise English.
public enum LanguageChoice: String, Codable, CaseIterable {
    case system
    case en
    case fa
}

/// Codable snapshot of the settings (servers live separately).
/// Decoding is tolerant: keys added later default instead of failing
/// the whole snapshot, and the pre-redesign resolver fields
/// (resolverMode "discovery"/"pool"/"manual", poolResolvers,
/// manualResolver) are migrated — a manual resolver or a pool list
/// carries over as Custom, anything else becomes Built-in.
public struct AppSettingsSnapshot: Codable {
    public var connectionMode: String
    public var resolverMode: String      // "builtIn" | "custom" | "import"
    public var customResolvers: String   // comma-separated, normalized
    // (1.0.2): the cleaned list from the last imported .txt file,
    // its file name and when it was imported (epoch ms, 0 = never).
    // Separate from customResolvers: Custom's list is never touched.
    public var importedResolvers: [String]
    public var importFileName: String
    public var importedAtMs: Int64
    public var mtu: Int                  // 0 = Auto (measure interface MTU at connect), else 1280/1400/1500
    public var maxAnswer: Int            // DNS answer-size cap: 0 = Auto (probe up to 1232), else 512/768/1024/1232
    public var tcpModeEnabled: Bool      // false = "auto", true = "on"
    public var autoUpdateSubscriptions: Bool
    public var socksPort: Int
    public var socksUsername: String
    public var bandwidthUpKBps: Int       // 0 = unlimited
    public var bandwidthDownKBps: Int     // 0 = unlimited
    public var geoBypass: Bool
    public var bypassDomains: String      // comma-separated
    public var onDemandEnabled: Bool
    public var autoSelectFastest: Bool
    public var appearance: String
    public var language: String
    // Legacy pre-redesign resolver fields: migration source only,
    // never written back.
    public var poolResolvers: String?
    public var manualResolver: String?

    public static let `default` = AppSettingsSnapshot(
        connectionMode: "vpn",
        resolverMode: "builtIn",
        customResolvers: "",
        importedResolvers: [],
        importFileName: "",
        importedAtMs: 0,
        mtu: 0,
        maxAnswer: 0,
        tcpModeEnabled: false,
        autoUpdateSubscriptions: false,
        socksPort: 1080,
        socksUsername: "",
        bandwidthUpKBps: 0,
        bandwidthDownKBps: 0,
        geoBypass: true,
        bypassDomains: "",
        onDemandEnabled: false,
        autoSelectFastest: true,
        appearance: "system",
        language: "system",
        poolResolvers: nil,
        manualResolver: nil
    )

    public init(connectionMode: String, resolverMode: String, customResolvers: String,
                importedResolvers: [String] = [], importFileName: String = "", importedAtMs: Int64 = 0,
                mtu: Int, maxAnswer: Int, tcpModeEnabled: Bool, autoUpdateSubscriptions: Bool,
                socksPort: Int, socksUsername: String, bandwidthUpKBps: Int,
                bandwidthDownKBps: Int, geoBypass: Bool, bypassDomains: String,
                onDemandEnabled: Bool, autoSelectFastest: Bool, appearance: String,
                language: String, poolResolvers: String? = nil, manualResolver: String? = nil) {
        self.connectionMode = connectionMode
        self.resolverMode = resolverMode
        self.customResolvers = customResolvers
        self.importedResolvers = importedResolvers
        self.importFileName = importFileName
        self.importedAtMs = importedAtMs
        self.mtu = mtu
        self.maxAnswer = maxAnswer
        self.tcpModeEnabled = tcpModeEnabled
        self.autoUpdateSubscriptions = autoUpdateSubscriptions
        self.socksPort = socksPort
        self.socksUsername = socksUsername
        self.bandwidthUpKBps = bandwidthUpKBps
        self.bandwidthDownKBps = bandwidthDownKBps
        self.geoBypass = geoBypass
        self.bypassDomains = bypassDomains
        self.onDemandEnabled = onDemandEnabled
        self.autoSelectFastest = autoSelectFastest
        self.appearance = appearance
        self.language = language
        self.poolResolvers = poolResolvers
        self.manualResolver = manualResolver
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        connectionMode = try c.decodeIfPresent(String.self, forKey: .connectionMode) ?? "vpn"
        resolverMode = try c.decodeIfPresent(String.self, forKey: .resolverMode) ?? "builtIn"
        customResolvers = try c.decodeIfPresent(String.self, forKey: .customResolvers) ?? ""
        importedResolvers = try c.decodeIfPresent([String].self, forKey: .importedResolvers) ?? []
        importFileName = try c.decodeIfPresent(String.self, forKey: .importFileName) ?? ""
        importedAtMs = try c.decodeIfPresent(Int64.self, forKey: .importedAtMs) ?? 0
        mtu = try c.decodeIfPresent(Int.self, forKey: .mtu) ?? 0
        maxAnswer = try c.decodeIfPresent(Int.self, forKey: .maxAnswer) ?? 0
        tcpModeEnabled = try c.decodeIfPresent(Bool.self, forKey: .tcpModeEnabled) ?? false
        autoUpdateSubscriptions = try c.decodeIfPresent(Bool.self, forKey: .autoUpdateSubscriptions) ?? false
        socksPort = try c.decodeIfPresent(Int.self, forKey: .socksPort) ?? 1080
        socksUsername = try c.decodeIfPresent(String.self, forKey: .socksUsername) ?? ""
        bandwidthUpKBps = try c.decodeIfPresent(Int.self, forKey: .bandwidthUpKBps) ?? 0
        bandwidthDownKBps = try c.decodeIfPresent(Int.self, forKey: .bandwidthDownKBps) ?? 0
        geoBypass = try c.decodeIfPresent(Bool.self, forKey: .geoBypass) ?? true
        bypassDomains = try c.decodeIfPresent(String.self, forKey: .bypassDomains) ?? ""
        onDemandEnabled = try c.decodeIfPresent(Bool.self, forKey: .onDemandEnabled) ?? false
        autoSelectFastest = try c.decodeIfPresent(Bool.self, forKey: .autoSelectFastest) ?? true
        appearance = try c.decodeIfPresent(String.self, forKey: .appearance) ?? "system"
        language = try c.decodeIfPresent(String.self, forKey: .language) ?? "system"
        poolResolvers = try c.decodeIfPresent(String.self, forKey: .poolResolvers)
        manualResolver = try c.decodeIfPresent(String.self, forKey: .manualResolver)
    }
}

public final class AppSettings: ObservableObject {
    public static let shared = AppSettings()

    // MARK: - Published state (the UI binds to these)

    @Published public var servers: [Server] = []
    @Published public var activeServerId: UUID?
    @Published public var connectionMode: ConnectionMode = .vpn { didSet { save() } }
    @Published public var resolverMode: ResolverMode = .builtIn { didSet { save() } }
    @Published public var customResolvers: String = "" { didSet { save() } }
    // (1.0.2) Import mode's own list; set through setImportedResolvers
    // / clearImportedResolvers so the three values change together.
    @Published public private(set) var importedResolvers: [String] = []
    @Published public private(set) var importFileName: String = ""
    @Published public private(set) var importedAtMs: Int64 = 0
    @Published public var mtu: Int = 0 { didSet { save() } }
    @Published public var maxAnswer: Int = 0 { didSet { save() } }
    @Published public var tcpModeEnabled: Bool = false { didSet { save() } }
    @Published public var autoUpdateSubscriptions: Bool = false { didSet { save() } }
    @Published public var socksPort: Int = 1080 { didSet { save() } }
    @Published public var socksUsername: String = "" { didSet { save() } }
    @Published public var bandwidthUpKBps: Int = 0 { didSet { save() } }
    @Published public var bandwidthDownKBps: Int = 0 { didSet { save() } }
    @Published public var geoBypass: Bool = true { didSet { save() } }
    @Published public var bypassDomains: String = "" { didSet { save() } }
    @Published public var onDemandEnabled: Bool = false { didSet { save() } }
    @Published public var autoSelectFastest: Bool = true { didSet { save() } }
    @Published public var appearance: AppearanceChoice = .system { didSet { save() } }
    @Published public var language: LanguageChoice = .system { didSet { save() } }

    private var loading = false

    public var activeServer: Server? {
        guard let id = activeServerId else { return servers.first }
        return servers.first(where: { $0.id == id }) ?? servers.first
    }

    /// (1.0.2): the user's own resolver list for the selected mode —
    /// Custom's field or the imported file; empty in Built-in mode.
    public var userResolvers: [String] {
        switch resolverMode {
        case .builtIn:
            return []
        case .custom:
            return ResolverNormalization.normalizeResolvers(customResolvers)
        case .imported:
            return importedResolvers
        }
    }

    /// Resolvers string for the current mode, for config export and
    /// the scan. Built-in -> "" (the core substitutes the bundled
    /// list when resolvers="" and discovery is on); Custom / Import
    /// -> the normalized user list of that mode only.
    public var effectiveResolvers: String {
        switch resolverMode {
        case .builtIn:
            return ""
        case .custom, .imported:
            return ResolverNormalization.normalizeResolvers(userResolvers.joined(separator: ",")).joined(separator: ", ")
        }
    }

    /// Discovery is on for Built-in (bundled list) and off for
    /// Custom and Import (only that mode's own resolvers).
    public var discoveryEnabled: Bool {
        return resolverMode == .builtIn
    }

    /// Resolvers handed to the core when a tunnel starts. Built-in
    /// with a remembered pool uses the top 16 (the fast-start seed:
    /// start from paths known healthy instead of re-scanning the
    /// whole bundled list); the rest of the pool goes as Discovery
    /// candidates (connectCandidates). Built-in with an empty
    /// memory falls back to "" so the core uses the bundled list.
    /// Custom uses only the user's normalized resolvers.
    public var connectResolvers: String {
        switch resolverMode {
        case .builtIn:
            let remembered = ResolverHealthStore.shared.freshAddrs()
            return remembered.isEmpty ? "" : Array(remembered.prefix(ResolverHealthStore.seedCount)).joined(separator: ", ")
        case .custom, .imported:
            return effectiveResolvers
        }
    }

    /// (1.0.53, item 40): the rest of the remembered pool (pool
    /// minus the fast-start seed), RTT-ordered — the Soar client
    /// vets them as Discovery candidates at connect and keeps
    /// Want=16 healthy, replacing dead ones itself. Custom mode
    /// has no pool: empty.
    public var connectCandidates: String {
        switch resolverMode {
        case .builtIn:
            let remembered = ResolverHealthStore.shared.freshAddrs()
            return Array(remembered.dropFirst(ResolverHealthStore.seedCount)).joined(separator: ", ")
        case .custom, .imported:
            return ""
        }
    }

    /// (1.0.53, item 40): the list the Go core's 30s loop
    /// re-probes — the whole remembered pool in Built-in mode
    /// (it rotates 16 per round), the user's own resolvers in
    /// Custom mode (unchanged).
    public var connectProbeResolvers: String {
        switch resolverMode {
        case .builtIn:
            return ResolverHealthStore.shared.freshAddrs().joined(separator: ", ")
        case .custom, .imported:
            return effectiveResolvers
        }
    }

    /// Discovery flag handed to the core when a tunnel starts:
    /// Built-in always on, Custom and Import always off.
    public var connectDiscoveryEnabled: Bool {
        return resolverMode == .builtIn
    }

    /// TCP mode string handed to the core: "auto" (off) or "on".
    public var tcpModeString: String {
        return tcpModeEnabled ? "on" : "auto"
    }

    private init() {
        load()
    }

    // MARK: - Persistence

    public func load() {
        loading = true
        defer { loading = false }
        guard let defaults = AppGroup.defaults else { return }
        if let data = defaults.data(forKey: SharedKey.servers),
           let decoded = try? JSONDecoder().decode([Server].self, from: data) {
            servers = decoded
        }
        if let idString = defaults.string(forKey: SharedKey.activeServerId) {
            activeServerId = UUID(uuidString: idString)
        }
        if let data = defaults.data(forKey: SharedKey.settings),
           let snap = try? JSONDecoder().decode(AppSettingsSnapshot.self, from: data) {
            applySnapshot(snap)
        }
    }

    public func save() {
        guard !loading, let defaults = AppGroup.defaults else { return }
        if let data = try? JSONEncoder().encode(servers) {
            defaults.set(data, forKey: SharedKey.servers)
        }
        if let id = activeServerId {
            defaults.set(id.uuidString, forKey: SharedKey.activeServerId)
        } else {
            defaults.removeObject(forKey: SharedKey.activeServerId)
        }
        if let data = try? JSONEncoder().encode(snapshot()) {
            defaults.set(data, forKey: SharedKey.settings)
        }
    }

    /// DNS answer-size cap (1.0.28): only Auto (0) or 512/768/1024/
    /// 1232 are valid; anything else (e.g. a value stored by an older
    /// build) reads back as Auto.
    public static func validatedMaxAnswer(_ v: Int) -> Int {
        return [0, 512, 768, 1024, 1232].contains(v) ? v : 0
    }

    // MTU (1.0.34): 0 = Auto, otherwise 1280/1400/1500.
    // Anything else is stored as Auto (0).
    public static func validatedMTU(_ v: Int) -> Int {
        return [0, 1280, 1400, 1500].contains(v) ? v : 0
    }

    private func snapshot() -> AppSettingsSnapshot {
        return AppSettingsSnapshot(
            connectionMode: connectionMode.rawValue,
            resolverMode: resolverMode.rawValue,
            customResolvers: ResolverNormalization.normalizeResolvers(customResolvers).joined(separator: ", "),
            importedResolvers: importedResolvers,
            importFileName: importFileName,
            importedAtMs: importedAtMs,
            mtu: Self.validatedMTU(mtu),
            maxAnswer: Self.validatedMaxAnswer(maxAnswer),
            tcpModeEnabled: tcpModeEnabled,
            autoUpdateSubscriptions: autoUpdateSubscriptions,
            socksPort: socksPort,
            socksUsername: socksUsername,
            bandwidthUpKBps: bandwidthUpKBps,
            bandwidthDownKBps: bandwidthDownKBps,
            geoBypass: geoBypass,
            bypassDomains: bypassDomains,
            onDemandEnabled: onDemandEnabled,
            autoSelectFastest: autoSelectFastest,
            appearance: appearance.rawValue,
            language: language.rawValue,
            poolResolvers: nil,
            manualResolver: nil
        )
    }

    private func applySnapshot(_ snap: AppSettingsSnapshot) {
        connectionMode = ConnectionMode(rawValue: snap.connectionMode) ?? .vpn
        // Legacy resolver modes migrate: "manual" -> Custom, anything
        // else ("discovery", "pool", unknown) -> Built-in.
        switch snap.resolverMode {
        case "custom", "manual":
            resolverMode = .custom
        case "import":
            resolverMode = .imported
        default:
            resolverMode = .builtIn
        }
        // Legacy resolver lists migrate into Custom so a user's
        // resolvers are not lost by the redesign.
        let customRaw: String
        if !snap.customResolvers.trimmingCharacters(in: .whitespaces).isEmpty {
            customRaw = snap.customResolvers
        } else if let manual = snap.manualResolver,
                  !manual.trimmingCharacters(in: .whitespaces).isEmpty {
            customRaw = manual
        } else {
            customRaw = snap.poolResolvers ?? ""
        }
        customResolvers = ResolverNormalization.normalizeResolvers(customRaw).joined(separator: ", ")
        importedResolvers = snap.importedResolvers
        importFileName = snap.importFileName
        importedAtMs = snap.importedAtMs
        mtu = Self.validatedMTU(snap.mtu)
        maxAnswer = Self.validatedMaxAnswer(snap.maxAnswer)
        tcpModeEnabled = snap.tcpModeEnabled
        autoUpdateSubscriptions = snap.autoUpdateSubscriptions
        socksPort = snap.socksPort
        socksUsername = snap.socksUsername
        bandwidthUpKBps = snap.bandwidthUpKBps
        bandwidthDownKBps = snap.bandwidthDownKBps
        geoBypass = snap.geoBypass
        bypassDomains = snap.bypassDomains
        onDemandEnabled = snap.onDemandEnabled
        autoSelectFastest = snap.autoSelectFastest
        appearance = AppearanceChoice(rawValue: snap.appearance) ?? .system
        language = LanguageChoice(rawValue: snap.language) ?? .system
    }

    // MARK: - Imported resolvers (1.0.2)

    /// Stores an imported (already cleaned) resolver list and switches
    /// to Import mode in one save. The Custom list is untouched.
    public func setImportedResolvers(_ entries: [String], fileName: String, at date: Date = Date()) {
        loading = true
        importedResolvers = entries
        importFileName = fileName
        importedAtMs = Int64(date.timeIntervalSince1970 * 1000)
        resolverMode = .imported
        loading = false
        save()
    }

    /// Forgets the imported list (Clear). Stays in Import mode with an
    /// empty list, like an empty Custom field.
    public func clearImportedResolvers() {
        loading = true
        importedResolvers = []
        importFileName = ""
        importedAtMs = 0
        loading = false
        save()
    }

    // MARK: - Servers

    public func addServer(_ server: Server) {
        servers.append(server)
        if activeServerId == nil {
            activeServerId = server.id
        }
        save()
    }

    public func updateServer(_ server: Server) {
        if let index = servers.firstIndex(where: { $0.id == server.id }) {
            servers[index] = server
            save()
        }
    }

    public func deleteServer(at offsets: IndexSet) {
        let removed = offsets.map { servers[$0] }
        servers.remove(atOffsets: offsets)
        if let active = activeServerId, removed.contains(where: { $0.id == active }) {
            activeServerId = servers.first?.id
        }
        save()
    }

    public func selectServer(_ server: Server) {
        activeServerId = server.id
        save()
    }

    /// Replaces every server belonging to one subscription with a new
    /// list (a subscription refresh). Manual servers are untouched,
    /// and the active server is NOT changed here — the caller remaps
    /// it. Returns the inserted servers.
    @discardableResult
    public func replaceSubscriptionServers(subscriptionId: UUID, with newServers: [Server]) -> [Server] {
        servers.removeAll(where: { $0.subscriptionId == subscriptionId })
        servers.append(contentsOf: newServers)
        save()
        return newServers
    }

    // MARK: - Tunnel config for the extension

    /// Builds the TunnelConfig the extension starts with. The SOCKS
    /// password is read from the Keychain here and never persisted
    /// anywhere else.
    public func tunnelConfig(for server: Server) -> TunnelConfig {
        return TunnelConfig(
            zone: server.zone.trimmingCharacters(in: .whitespaces),
            serverKeyB64: server.serverKeyB64.trimmingCharacters(in: .whitespaces),
            resolverMode: resolverMode.rawValue,
            resolvers: connectResolvers,
            discoveryEnabled: connectDiscoveryEnabled,
            country: "IR",
            tcpMode: tcpModeString,
            mtu: min(max(mtu, 1280), 1500),
            maxAnswer: Self.validatedMaxAnswer(maxAnswer),
            mode: connectionMode.rawValue,
            socksPort: socksPort,
            socksUsername: socksUsername,
            socksPassword: KeychainHelper.shared.socksPassword(),
            bandwidthUp: Int64(bandwidthUpKBps) * 1024,
            bandwidthDown: Int64(bandwidthDownKBps) * 1024,
            geoBypass: geoBypass,
            bypassDomains: bypassDomains,
            candidates: connectCandidates,
            probeResolvers: connectProbeResolvers
        )
    }
}
