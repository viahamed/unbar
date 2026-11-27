// VPNManager.swift — owns the system VPN configuration
// (NETunnelProviderManager) and talks to the UnbarTunnel extension.
//
// The app itself never runs the tunnel. It configures the system VPN,
// starts/stops it, and reads live stats the extension writes into the
// shared App Group. The one exception is server testing: the app links
// the Core framework too, so "Test" and "auto-select fastest" run a
// throwaway Core tunnel in the app process (no TUN involved — Start
// plus Test, then Stop).
//
// iOS has no true kill switch like Android. The iOS equivalent is
// on-demand VPN (NEOnDemandRuleConnect): with it on, the system brings
// the tunnel up whenever network is available. That is what the
// "On-demand VPN" setting controls.

import Foundation
import NetworkExtension
import Combine
import UnbarCore

public enum VPNState: Equatable {
    case disconnected
    case connecting
    case connected
    case disconnecting
    case reasserting
    case invalid

    public static func from(_ status: NEVPNStatus) -> VPNState {
        switch status {
        case .connected: return .connected
        case .connecting: return .connecting
        case .disconnected: return .disconnected
        case .disconnecting: return .disconnecting
        case .reasserting: return .reasserting
        case .invalid: return .invalid
        @unknown default: return .invalid
        }
    }
}

public final class VPNManager: ObservableObject {
    public static let shared = VPNManager()

    public static let providerBundleId = "org.breakthebarriers.unbar.UnbarTunnel"

    @Published public private(set) var state: VPNState = .disconnected
    @Published public private(set) var lastError: String?

    /// Resolver-scan state (the "Test resolvers" full scan): true
    /// from the moment the scan starts until the core reports it
    /// finished (or the 240s cap hits, so it never sticks).
    @Published public private(set) var testScanRunning: Bool = false
    /// True while a running full scan is paused (Pause button).
    /// Reset at scan start; the stepper's Pause/Resume button
    /// flips from this flag.
    @Published public private(set) var scanPaused: Bool = false
    /// True while a silent background re-scan runs (1.0.53,
    /// item 40). Separate from testScanRunning, which a silent
    /// scan never publishes — but a manual scan must still wait
    /// for it, and vice versa, so two scans never fight over
    /// scanTunnel.
    private var backgroundScanRunning: Bool = false
    /// The throwaway tunnel the running scan uses, so Stop /
    /// Pause / Resume can reach the Go core mid-scan. Nil when
    /// no scan is running.
    private var scanTunnel: CoreTunnel?
    /// Stage-1 plain-DNS responding count from the last scan.
    @Published public private(set) var scanRespondingCount: Int = 0
    /// Soar-verified resolvers from the last scan (empty while
    /// disconnected, by design — with no tunnel client there is
    /// nothing to verify against).
    @Published public private(set) var scanVerifiedResolvers: [VerifiedResolver] = []
    /// Soar-verified count from the last scan.
    @Published public private(set) var scanVerifiedCount: Int = 0
    /// (1.0.3): addresses the core left out of the scanned resolver
    /// list (ranges larger than /20 skipped, or the 5000 cap) —
    /// Stats() "resolverListDropped". 0 = nothing left out.
    @Published public private(set) var scanListDropped: Int = 0
    /// Live stage-1 progress of the running scan: probes completed
    /// (from the throwaway tunnel's Stats() "testedResolvers") and
    /// the scan's total probe count. Reset at scan start.
    @Published public private(set) var scanTestedCount: Int = 0
    @Published public private(set) var scanTotalCount: Int = 0

    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?
    private let workQueue = DispatchQueue(label: "org.breakthebarriers.unbar.vpnmanager",
                                          qos: .userInitiated)
    /// A scan can run up to 240s; it gets its own queue so it never
    /// blocks server tests on workQueue.
    private let scanQueue = DispatchQueue(label: "org.breakthebarriers.unbar.resolverscan",
                                          qos: .utility)
    /// Guards stale async test results (a new test invalidates old ones).
    private var testGeneration = 0
    /// Guards stale async scan results (a new scan invalidates old ones).
    private var scanGeneration = 0

    private init() {
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refreshStatus()
        }
        refreshManager { [weak self] in
            self?.refreshStatus()
        }
    }

    deinit {
        if let observer = statusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Status

    public func refreshStatus() {
        if let manager = manager {
            state = VPNState.from(manager.connection.status)
        } else {
            state = .invalid
        }
        lastError = AppGroup.defaults?.string(forKey: SharedKey.lastError)
    }

    public var isConnected: Bool { state == .connected }
    public var isTransitioning: Bool {
        state == .connecting || state == .disconnecting || state == .reasserting
    }

    // MARK: - Manager loading

    private func refreshManager(completion: (() -> Void)? = nil) {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            if let error = error {
                SharedLog.append("Failed to load VPN managers: \(error.localizedDescription)")
                DispatchQueue.main.async { completion?() }
                return
            }
            let ours = managers?.first(where: {
                ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier
                    == VPNManager.providerBundleId
            })
            self?.manager = ours
            DispatchQueue.main.async { completion?() }
        }
    }

    // MARK: - Connect / disconnect

    /// Start the tunnel for the active server. `completion` reports a
    /// configuration error (e.g. no server yet); tunnel failures after
    /// start are reported through `state` and `lastError`.
    public func connect(completion: @escaping (String?) -> Void) {
        let settings = AppSettings.shared
        guard let server = settings.activeServer else {
            completion("no_server")
            return
        }
        guard server.isValid else {
            completion("server_invalid")
            return
        }
        let config = settings.tunnelConfig(for: server)
        workQueue.async { [weak self] in
            guard let self = self else { return }
            self.configureAndStart(config: config, onDemand: settings.onDemandEnabled,
                                   completion: completion)
        }
    }

    private func configureAndStart(config: TunnelConfig, onDemand: Bool,
                                   completion: @escaping (String?) -> Void) {
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = VPNManager.providerBundleId
        proto.serverAddress = config.zone
        proto.providerConfiguration = config.asDictionary()
        // The extension disconnects on its own when the tunnel fails;
        // keep the session alive across short network changes.
        proto.disconnectOnSleep = false

        let configure: (NETunnelProviderManager) -> Void = { manager in
            manager.localizedDescription = "Unbar"
            manager.protocolConfiguration = proto
            manager.isEnabled = true
            manager.isOnDemandEnabled = onDemand
            if onDemand {
                // iOS on-demand: bring the tunnel up whenever network is
                // available. This is the iOS equivalent of always-on;
                // there is no true kill switch on iOS.
                manager.onDemandRules = [NEOnDemandRuleConnect()]
            } else {
                manager.onDemandRules = []
            }
        }

        let start: (NETunnelProviderManager) -> Void = { [weak self] manager in
            manager.loadFromPreferences { error in
                if let error = error {
                    SharedLog.append("Reload after save failed: \(error.localizedDescription)")
                    DispatchQueue.main.async { completion("save_failed") }
                    return
                }
                guard let session = manager.connection as? NETunnelProviderSession else {
                    DispatchQueue.main.async { completion("session_unavailable") }
                    return
                }
                do {
                    try session.startVPNTunnel(options: nil)
                    self?.manager = manager
                    DispatchQueue.main.async {
                        self?.refreshStatus()
                        completion(nil)
                    }
                } catch {
                    SharedLog.append("startVPNTunnel failed: \(error.localizedDescription)")
                    DispatchQueue.main.async { completion("start_failed") }
                }
            }
        }

        if let existing = manager {
            configure(existing)
            existing.saveToPreferences { error in
                if let error = error {
                    SharedLog.append("Save failed: \(error.localizedDescription)")
                    DispatchQueue.main.async { completion("save_failed") }
                    return
                }
                start(existing)
            }
        } else {
            let fresh = NETunnelProviderManager()
            configure(fresh)
            fresh.saveToPreferences { [weak self] error in
                if let error = error {
                    SharedLog.append("Save failed: \(error.localizedDescription)")
                    DispatchQueue.main.async { completion("save_failed") }
                    return
                }
                self?.manager = fresh
                start(fresh)
            }
        }
    }

    public func disconnect() {
        (manager?.connection as? NETunnelProviderSession)?.stopVPNTunnel()
        refreshStatus()
    }

    /// Apply a settings change (e.g. on-demand toggled) without
    /// reconnecting: rewrites the saved configuration.
    public func applySettings(completion: (() -> Void)? = nil) {
        let settings = AppSettings.shared
        guard let server = settings.activeServer else {
            completion?()
            return
        }
        let config = settings.tunnelConfig(for: server)
        workQueue.async { [weak self] in
            guard let self = self, let manager = self.manager else {
                DispatchQueue.main.async { completion?() }
                return
            }
            let proto = NETunnelProviderProtocol()
            proto.providerBundleIdentifier = VPNManager.providerBundleId
            proto.serverAddress = config.zone
            proto.providerConfiguration = config.asDictionary()
            proto.disconnectOnSleep = false
            manager.protocolConfiguration = proto
            manager.isOnDemandEnabled = settings.onDemandEnabled
            manager.onDemandRules = settings.onDemandEnabled ? [NEOnDemandRuleConnect()] : []
            manager.saveToPreferences { _ in
                DispatchQueue.main.async { completion?() }
            }
        }
    }

    // MARK: - Server testing (Core in the app process)

    /// Tests one server with a throwaway Core tunnel: Start, then
    /// Test(). Completion gives latency in ms, or an error message.
    /// Runs off the main thread; Test() can block for up to ~30s.
    public func testServer(_ server: Server,
                           completion: @escaping (Int?, String?) -> Void) {
        testGeneration += 1
        let generation = testGeneration
        let settings = AppSettings.shared
        workQueue.async {
            guard let tunnel = CoreNewTunnel() else {
                DispatchQueue.main.async { completion(nil, "core_unavailable") }
                return
            }
            tunnel.setMTU(settings.mtu)
            tunnel.setMaxAnswer(settings.maxAnswer)
            do {
                try tunnel.start(server.zone.trimmingCharacters(in: .whitespaces),
                                 serverKeyB64: server.serverKeyB64.trimmingCharacters(in: .whitespaces),
                                 resolvers: settings.connectResolvers,
                                 discoveryEnabled: settings.connectDiscoveryEnabled,
                                 country: "IR",
                                 tcpMode: settings.tcpModeString)
            } catch {
                DispatchQueue.main.async {
                    if generation == self.testGeneration {
                        completion(nil, error.localizedDescription)
                    }
                }
                return
            }
            let start = Date()
            let failure = tunnel.test()
            let latencyMs = Int(Date().timeIntervalSince(start) * 1000)
            try? tunnel.stop()
            DispatchQueue.main.async {
                if generation == self.testGeneration {
                    if failure.isEmpty {
                        completion(latencyMs, nil)
                    } else {
                        completion(nil, failure)
                    }
                }
            }
        }
    }

    /// Tests every server and picks the fastest that works. The winner
    /// becomes the active server.
    public func autoSelectFastest(completion: @escaping (Server?) -> Void) {
        let servers = AppSettings.shared.servers
        guard !servers.isEmpty else {
            completion(nil)
            return
        }
        // Runs on a global queue, NOT on workQueue: each test dispatches
        // onto workQueue and this loop waits on a semaphore for it, so
        // waiting here must never block workQueue itself.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            var best: (server: Server, latency: Int)?
            // Test sequentially: parallel tunnels fight over resolvers.
            for server in servers {
                let semaphore = DispatchSemaphore(value: 0)
                var result: (Int?, String?) = (nil, nil)
                self.testServerWithoutGuard(server) { latency, error in
                    result = (latency, error)
                    semaphore.signal()
                }
                semaphore.wait()
                if let latency = result.0 {
                    if best == nil || latency < best!.latency {
                        best = (server, latency)
                    }
                }
            }
            if let winner = best {
                DispatchQueue.main.async {
                    AppSettings.shared.selectServer(winner.server)
                    completion(winner.server)
                }
            } else {
                DispatchQueue.main.async { completion(nil) }
            }
        }
    }

    /// Test used by auto-select: same as testServer but without the
    /// generation guard (the sequential caller owns ordering).
    private func testServerWithoutGuard(_ server: Server,
                                        completion: @escaping (Int?, String?) -> Void) {
        let settings = AppSettings.shared
        workQueue.async {
            guard let tunnel = CoreNewTunnel() else {
                completion(nil, "core_unavailable")
                return
            }
            tunnel.setMTU(settings.mtu)
            tunnel.setMaxAnswer(settings.maxAnswer)
            do {
                try tunnel.start(server.zone.trimmingCharacters(in: .whitespaces),
                                 serverKeyB64: server.serverKeyB64.trimmingCharacters(in: .whitespaces),
                                 resolvers: settings.connectResolvers,
                                 discoveryEnabled: settings.connectDiscoveryEnabled,
                                 country: "IR",
                                 tcpMode: settings.tcpModeString)
            } catch {
                completion(nil, error.localizedDescription)
                return
            }
            let start = Date()
            let failure = tunnel.test()
            let latencyMs = Int(Date().timeIntervalSince(start) * 1000)
            try? tunnel.stop()
            if failure.isEmpty {
                completion(latencyMs, nil)
            } else {
                completion(nil, failure)
            }
        }
    }

    // MARK: - Resolver scan (two-stage, the ONLY full scan)

    /// Runs a one-off full resolver scan — the ONLY full scan;
    /// connects never trigger one. Non-blocking: the core runs it in
    /// a goroutine and Stats() reports scanRunning while it runs.
    /// Safe to call while disconnected: the probe list is set from
    /// the current settings first (a fresh tunnel object has an empty
    /// probe list — only Start sets it — so without this the scan
    /// would be a no-op when disconnected). Custom mode scans only
    /// the user's resolvers; Built-in mode passes an empty list with
    /// discovery on and the core uses the bundled list.
    ///
    /// Stage 1 is the plain-DNS probe + RTT (pre-filter only);
    /// stage 2 is the Soar handshake vetting in the core. Only the
    /// Soar-verified list ever enters the remembered healthy list —
    /// the plain-DNS "responding" count never does. (1.0.33): the
    /// scan now vets its own responders (stage 2) via a
    /// short-lived vetting client, so a scan produces a verified
    /// list — and the remembered list updates from it — whether
    /// or not the tunnel is running.
    ///
    /// Completion is watched by polling the throwaway tunnel's
    /// Stats() directly — it reads the core synchronously and works
    /// while disconnected. The scan starts in a goroutine, so the
    /// first reads may still say false: wait up to 5s for it to be
    /// observed running, then until it stops, with a 240s cap so the
    /// state never sticks if the scan never starts or never reports
    /// back. (240s covers the 1.0.33 worst case: 2,462 resolvers /
    /// 32 concurrent x 2s per-probe deadline is ~2.5 min for
    /// stage 1, plus up to 15s for the stage-2 Soar vetting.)
    /// Completion gives true when the scan finished.
    ///
    /// `silent` (1.0.53, item 40) runs a background re-scan: it
    /// never publishes testScanRunning, so no UI state moves for
    /// it — matching Android's manual=false path.
    public func testResolvers(silent: Bool = false, completion: ((Bool) -> Void)? = nil) {
        guard !testScanRunning && !backgroundScanRunning else {
            completion?(false)
            return
        }
        scanGeneration += 1
        let generation = scanGeneration
        let settings = AppSettings.shared
        if silent {
            backgroundScanRunning = true
        } else {
            testScanRunning = true
        }
        scanPaused = false
        scanTestedCount = 0
        scanTotalCount = 0
        scanRespondingCount = 0
        scanVerifiedResolvers = []
        scanVerifiedCount = 0
        scanListDropped = 0
        scanQueue.async { [weak self] in
            guard let self = self else { return }
            guard let tunnel = CoreNewTunnel() else {
                DispatchQueue.main.async {
                    if generation == self.scanGeneration {
                        if silent {
                            self.backgroundScanRunning = false
                        } else {
                            self.testScanRunning = false
                        }
                        completion?(false)
                    }
                }
                return
            }
            // Kept so Stop / Pause / Resume can reach the core
            // mid-scan; cleared when the scan finishes.
            self.scanTunnel = tunnel
            let discovery = settings.discoveryEnabled
            let probeCsv = discovery ? "" : settings.effectiveResolvers
            // (1.0.35): hand the selected server's credentials to
            // the scan's stage-2 vetting, so it can Soar-vet its
            // responders even before the first connect in this app
            // session (the Go core used to get them only from
            // Start()). No active server → the Go side keeps
            // whatever it has (or nothing) and vetting returns
            // empty, as before.
            if let server = settings.activeServer {
                tunnel.setVetCredentials(server.zone, serverKeyB64: server.serverKeyB64, country: "IR", tcpMode: settings.tcpModeString)
            }
            tunnel.setProbeResolvers(probeCsv, discoveryEnabled: discovery)
            // (1.0.3): scan over UDP or UDP + TCP (Settings).
            tunnel.setScanTransport(settings.scanTransportString)
            tunnel.testResolvers()
            let startedAt = Date()
            var sawRunning = false
            var finished = false
            // Last live progress published to the main thread, so
            // the stepper updates only when something changed.
            var lastTested = -1
            var lastTotal = -1
            var lastResponding = -1
            var lastVerified = -1
            var lastPaused = false
            while Date().timeIntervalSince(startedAt) < 240 {
                let snapshot = StatsSnapshot.fromStatsJSON(tunnel.stats())
                let running = snapshot?.scanRunning ?? false
                if running { sawRunning = true }
                if sawRunning && !running {
                    finished = true
                    break
                }
                if !sawRunning && Date().timeIntervalSince(startedAt) >= 5 { break }
                // Live stage-1 progress for the stepper: probes
                // completed / total, plus the responding count so
                // far, plus the paused flag. Read from the same
                // Stats() JSON as the running flag, every 0.5s poll.
                // (1.0.36): the stage-2 verified count too — the
                // Go core publishes scanVerified live on every vet
                // poll now, so the stepper's vetting count rises
                // during vetting instead of jumping 0 -> final.
                let tested = snapshot?.testedResolvers ?? 0
                let total = snapshot?.totalResolvers ?? 0
                let responding = snapshot?.respondingResolvers ?? 0
                let verified = snapshot?.scanVerifiedCount ?? 0
                let paused = snapshot?.scanPaused ?? false
                if tested != lastTested || total != lastTotal || responding != lastResponding || verified != lastVerified || paused != lastPaused {
                    lastTested = tested
                    lastTotal = total
                    lastResponding = responding
                    lastVerified = verified
                    lastPaused = paused
                    DispatchQueue.main.async { [weak self] in
                        guard let self = self, generation == self.scanGeneration else { return }
                        self.scanTestedCount = tested
                        self.scanTotalCount = total
                        self.scanRespondingCount = responding
                        self.scanVerifiedCount = verified
                        self.scanPaused = paused
                    }
                }
                Thread.sleep(forTimeInterval: 0.5)
            }
            // Timed out only when the scan was seen running and never
            // reported back within the 240s cap. When it never
            // started, the stats below are simply what the core has.
            let timedOut = sawRunning && !finished
            let final = StatsSnapshot.fromStatsJSON(tunnel.stats())
            try? tunnel.stop()
            DispatchQueue.main.async {
                guard generation == self.scanGeneration else { return }
                self.scanTunnel = nil
                self.scanRespondingCount = final?.respondingResolvers ?? 0
                // (1.0.33): the SCAN's own vetted list (stage 2),
                // not the live client's paths.
                self.scanVerifiedResolvers = final?.scanVerifiedResolvers ?? []
                self.scanVerifiedCount = final?.scanVerifiedCount ?? 0
                self.scanListDropped = final?.resolverListDropped ?? 0
                for warning in final?.resolverListWarnings ?? [] {
                    SharedLog.append("Resolver list: \(warning)")
                }
                self.scanTestedCount = final?.testedResolvers ?? 0
                self.scanTotalCount = final?.totalResolvers ?? 0
                self.scanPaused = false
                if silent {
                    self.backgroundScanRunning = false
                } else {
                    self.testScanRunning = false
                }
                // Memory sync (Built-in only, verified only): the
                // scan's own vetted list MERGES into the stored
                // pool (1.0.53, item 40), so a resolver that drops
                // out of the scan is not forgotten — it stays
                // until the 30s loop evicts it or it goes stale.
                // An empty vetted list (nothing verified, or a
                // scan before the first connect) stores nothing.
                if settings.resolverMode == .builtIn,
                   let verified = final?.scanVerifiedResolvers, !verified.isEmpty {
                    ResolverHealthStore.shared.merge(verified.map {
                        RememberedResolver(addr: $0.addr, rttMs: Int($0.rttMs), checkedAtMs: 0, transport: $0.transport)
                    })
                }
                if timedOut {
                    SharedLog.append("Test resolvers timed out after 240s")
                }
                completion?(!timedOut)
            }
        }
    }

    /// Silent background re-scan (1.0.53, item 40). Runs only
    /// while connected, only in Built-in mode, only when the pool
    /// is thin (fewer than 32 fresh entries) or stale (not
    /// refreshed in 7 days), at most once a day, never while a
    /// manual scan (or another background scan) runs, and only
    /// when an active server exists — stage-2 vetting needs it.
    /// The scan itself is silent: no UI state moves for it.
    /// Called on app foreground and on connect, while connected.
    public func maybeBackgroundRescan() {
        guard isConnected else { return }
        guard !testScanRunning && !backgroundScanRunning else { return }
        let settings = AppSettings.shared
        guard settings.resolverMode == .builtIn else { return }
        guard settings.activeServer != nil else { return }
        let store = ResolverHealthStore.shared
        let fresh = store.load()
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let thin = fresh.count < 32
        let stale = nowMs - store.lastTestMs() > ResolverHealthStore.staleAfterMs
        guard thin || stale else { return }
        guard nowMs - store.lastBackgroundScanMs() > 24 * 60 * 60 * 1000 else { return }
        store.markBackgroundScan(nowMs: nowMs)
        SharedLog.append("Background re-scan: pool thin=\(thin) stale=\(stale) (\(fresh.count) fresh)")
        testResolvers(silent: true)
    }

    /// Clears the displayed scan results (counts). Called when
    /// the resolver mode changes (Built-in <-> Custom), so a
    /// scan's results never linger under the other mode's
    /// heading. (1.0.33, fix 3.)
    public func clearScanResults() {
        scanTestedCount = 0
        scanTotalCount = 0
        scanRespondingCount = 0
        scanVerifiedResolvers = []
        scanVerifiedCount = 0
        scanListDropped = 0
    }

    /// Stops a running full scan (Stop button): the Go core cancels
    /// it — in-flight probes finish, no new probes launch, partial
    /// results kept. The polling loop above observes scanRunning go
    /// false and fetches the partial results, so the UI settles
    /// within ~2s. A no-op when no scan is running.
    public func stopScan() {
        scanQueue.async { [weak self] in
            self?.scanTunnel?.stopScan()
        }
    }

    /// Pauses a running full scan (Pause button): no new probe
    /// starts until resumeScan. Stop still wins over a pause.
    /// A no-op when no scan is running.
    public func pauseScan() {
        scanQueue.async { [weak self] in
            self?.scanTunnel?.pauseScan()
        }
    }

    /// Resumes a paused full scan.
    public func resumeScan() {
        scanQueue.async { [weak self] in
            self?.scanTunnel?.resumeScan()
        }
    }

}

// MARK: - Resolver list check (1.0.3)

/// What the core will make of a Custom / Import resolver list.
public struct ResolverListCheck: Equatable {
    public var count: Int
    public var dropped: Int
    public var warnings: [String]
}

extension VPNManager {
    /// (1.0.3): the core's own expansion of a resolver list, without
    /// a tunnel — gomobile `CoreCheckResolverList(_:)` (Go
    /// CheckResolverList), JSON {"count","dropped","warnings"}.
    /// nil for an empty list or an unparsable answer. Cheap (at most
    /// 5000 entries), but call it off the main thread.
    public static func checkResolverList(_ csv: String) -> ResolverListCheck? {
        let trimmed = csv.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let raw = CoreCheckResolverList(trimmed)
        guard let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        return ResolverListCheck(
            count: (obj["count"] as? NSNumber)?.intValue ?? 0,
            dropped: (obj["dropped"] as? NSNumber)?.intValue ?? 0,
            warnings: (obj["warnings"] as? [String]) ?? [])
    }
}
