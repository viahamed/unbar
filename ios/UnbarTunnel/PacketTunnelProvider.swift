// PacketTunnelProvider.swift — the Unbar Network Extension.
//
// This is the iOS data plane: it creates the Go Core tunnel, hands it a
// file descriptor for the TUN interface, and applies the tunnel network
// settings (10.8.0.2/32, default route, DNS 10.8.0.1).
//
// How the TUN file descriptor is obtained
// ---------------------------------------
// NEPacketTunnelProvider has no public accessor for the utun file
// descriptor, so Unbar tries two paths, in order:
//
// 1. Direct fd (preferred): the packet flow keeps its socket in a
//    private ivar named "socket", whose own private ivar
//    "fileDescriptor" is the utun fd. These are the ivar names used by
//    open-source packet-tunnel providers. They are read with the
//    / object_getIvar), never with KVC, so a renamed ivar simply yields
//    nil instead of an exception. The fd is validated with
//    fcntl(fd, F_GETFD) before use.
// 2. Packet bridge (fallback, fully public API): a SOCK_DGRAM Unix
//    socketpair is created; one end is passed to Core AttachTun and
//    packets are shuttled between the other end and packetFlow
//    (readPackets/writePackets). SOCK_DGRAM preserves packet boundaries,
//    and the Go core only does plain Read/Write on the fd, so the
//    socket end behaves exactly like a TUN fd to the core.
//
// Either way the Go core sees one IP packet per Read and one per Write.
// The path used is written to the log, so a future iOS change is
// visible instead of silent.
//
// SOCKS mode: the tunnel is started with no included routes (no device
// traffic is captured) and the core starts its SOCKS5 listener on
// 127.0.0.1:port. iOS backgrounding limits what a loopback SOCKS proxy
// can do for other apps — that is why VPN is the primary mode on iOS.
//
// iOS has no true kill switch like Android. The iOS equivalent is
// on-demand VPN (NEOnDemandRuleConnect), configured by the app in the
// saved NETunnelProviderManager. When the tunnel fails here, the
// provider cancels with an error and the system restarts it if
// on-demand is enabled.

import NetworkExtension
import Network
import Foundation
import UnbarCore
import ObjectiveC

public class PacketTunnelProvider: NEPacketTunnelProvider {

    private var coreTunnel: CoreTunnel?
    private var config: TunnelConfig?
    private var bridge: PacketBridge?
    private var statsTimer: Timer?
    private var healthTimer: Timer?
    private var startTime = Date()
    private var consecutiveHealthFailures = 0
    private var healthTicks = 0
    // The verified set already written to the memory store, so the
    // store is not rewritten on every 1s stats tick.
    private var lastSyncedVerifiedAddrs: Set<String> = []
    // (1.0.53, item 40): the probe list last seen from the core,
    // so the merge only runs when something changed; and the
    // probe list last handed to the core, so the merge can tell
    // which stored resolvers the 30s loop evicted (last handed
    // minus the core's current probe list).
    private var lastSeenProbeAddrs: Set<String> = []
    private var lastHandedProbeAddrs: Set<String> = []

    // Offline pause (field test 1.0.0): mirrors the Android
    // UnbarVpnService pause. When the last network is lost the
    // tunnel is STOPPED (not left up to fire queries into the
    // void) and resumed fresh when a network returns. The
    // monitor observes the path; the flag is set on the monitor
    // queue and cleared synchronously by the resume, and the
    // pause job re-checks it after every blocking step.
    private var pathMonitor: NWPathMonitor?
    // (1.0.3): the resolver-memory network key last seen by the
    // path monitor (nil = global pool).
    private var lastNetworkKey: String?
    private var networkPaused = false
    // Set in stopTunnel so a path callback already in flight
    // cannot pause or resume a provider that is being torn down.
    // Cleared when a fresh start begins.
    private var providerStopped = false
    // The in-flight offline-pause work, so a start can cancel it
    // and wait (bounded) for it to finish before building a new
    // tunnel. The group only ever holds pause work.
    private var pauseWorkItem: DispatchWorkItem?
    private let pauseGroup = DispatchGroup()

    // MARK: - Start

    public override func startTunnel(options: [String: NSObject]?,
                                     completionHandler: @escaping (Error?) -> Void) {
        startTunnelCore(completionHandler: completionHandler)
    }

    /// The shared start path. The system entry point above and the
    /// offline-pause resume both run it, so a resume rebuilds the
    /// tunnel exactly the way a fresh start does: new core, fresh
    /// TUN attach, network settings, bridge, timers.
    private func startTunnelCore(completionHandler: @escaping (Error?) -> Void) {
        providerStopped = false
        // Cancel any in-flight offline pause and wait (bounded)
        // for it to finish, so it cannot tear down the tunnel
        // this start is building. Mirrors the Android startTunnel
        // join of the pause job (1s bound).
        pauseWorkItem?.cancel()
        pauseWorkItem = nil
        _ = pauseGroup.wait(timeout: .now() + 1.0)
        // A start supersedes any offline pause (a user connect, or
        // the system restarting the tunnel, wins over the pause),
        // but a start into a network the monitor already knows is
        // dead is refused: the monitor resumes the tunnel when a
        // network returns. The resume clears the flag before
        // calling here, so a live resume is unaffected.
        if networkPaused {
            if let path = pathMonitor?.currentPath, path.status != .satisfied {
                let message = "Network is unavailable; refusing tunnel start while paused"
                SharedLog.append("startTunnel: \(message)")
                completionHandler(tunnelError(message))
                return
            }
            networkPaused = false
        }
        startTime = Date()
        consecutiveHealthFailures = 0
        healthTicks = 0

        guard let proto = protocolConfiguration as? NETunnelProviderProtocol,
              let dict = proto.providerConfiguration,
              var config = TunnelConfig.fromDictionary(dict) else {
            let error = tunnelError("Missing tunnel configuration")
            SharedLog.append("startTunnel: no provider configuration")
            completionHandler(error)
            return
        }
        // MTU Auto (1.0.34): resolve Auto (0) to the measured
        // effective MTU before anything uses it (Go tunstack via
        // SetMTU and the TUN interface via the network settings).
        // A manual value passes through unchanged.
        // (1.0.3): the active interface (NWPathMonitor) drives both
        // MTU Auto and the per-network resolver memory.
        let activeInterface = currentActiveInterfaceName()
        ResolverHealthStore.shared.interfaceNameOverride = activeInterface
        lastNetworkKey = ResolverHealthStore.shared.currentNetworkKey()
        let effectiveMtu = resolveMTU(config.mtu, interfaceName: activeInterface)
        if config.mtu == 0 {
            SharedLog.append("MTU Auto resolved to \(effectiveMtu)")
            config.mtu = effectiveMtu
        }
        self.config = config
        SharedLog.append("Starting tunnel for zone \(config.zone) (mode: \(config.mode))")

        guard let tunnel = CoreNewTunnel() else {
            let error = tunnelError("The tunnel core could not be created")
            SharedLog.append("startTunnel: CoreNewTunnel returned nil")
            completionHandler(error)
            return
        }
        coreTunnel = tunnel

        // (1.0.53, item 40): the rest of the pool goes as Discovery
        // candidates — before start(), for the same reason as
        // setMaxAnswer below: Start() reads it when building the
        // client config. Custom mode has no pool: empty.
        tunnel.setDiscoveryCandidates(config.candidates)

        do {
            try tunnel.start(config.zone,
                             serverKeyB64: config.serverKeyB64,
                             resolvers: config.resolvers,
                             discoveryEnabled: config.discoveryEnabled,
                             country: config.country,
                             tcpMode: config.tcpMode)
        } catch {
            let message = "Start failed: \(error.localizedDescription)"
            SharedLog.append(message)
            setLastError(message)
            completionHandler(tunnelError(message))
            return
        }

        tunnel.setBandwidthLimit(config.bandwidthUp, downBytesPerSec: config.bandwidthDown)
        tunnel.setGeoBypass(config.geoBypass)
        tunnel.setBypassDomains(config.bypassDomains)
        // Hand the whole pool to the Go core so its background
        // 30s loop rotates over it from the first tick (never the
        // full 2,307). In Custom mode the list is the user's own
        // resolvers; in Built-in mode with no memory it is empty
        // and the loop has nothing to probe until a Test scan
        // supplies one. Remember what was handed, so the merge
        // below can tell evicted resolvers apart.
        lastHandedProbeAddrs = Set(config.probeResolvers
            .split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        // (1.0.3): the 30s pool probe uses the scan transport too, so
        // a resolver kept by a UDP + TCP scan (TCP-only) is not
        // evicted by UDP-only probes. Before setHealthyProbeResolvers
        // so the first round already uses it.
        tunnel.setScanTransport(config.scanTransport)
        tunnel.setHealthyProbeResolvers(config.probeResolvers)
        // One MTU drives the whole data plane: the Go tunstack (via
        // SetMTU) and the TUN interface (via the network settings
        // below). Applies on next connect.
        tunnel.setMTU(config.mtu)
        // DNS answer-size cap (1.0.28): 0 = Auto, otherwise
        // (1.0.3) a Soar class boundary 512/752/992. Applies on next connect.
        tunnel.setMaxAnswer(config.maxAnswer)

        if config.mode == "socks" {
            do {
                try tunnel.startSocks(config.socksPort,
                                      username: config.socksUsername,
                                      password: config.socksPassword)
                SharedLog.append("SOCKS proxy listening on 127.0.0.1:\(config.socksPort)")
            } catch {
                let message = "SOCKS start failed: \(error.localizedDescription)"
                SharedLog.append(message)
                setLastError(message)
                try? tunnel.stop()
                self.coreTunnel = nil
                completionHandler(tunnelError(message))
                return
            }
        }

        // Attach the TUN fd (VPN mode only) before setting network settings.
        if config.mode == "vpn" {
            if !attachTun(tunnel: tunnel) {
                let message = "Could not obtain the tunnel file descriptor"
                SharedLog.append(message)
                setLastError(message)
                try? tunnel.stop()
                self.coreTunnel = nil
                completionHandler(tunnelError(message))
                return
            }
        }

        let settings = makeNetworkSettings(for: config)
        setTunnelNetworkSettings(settings) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                let message = "Network settings failed: \(error.localizedDescription)"
                SharedLog.append(message)
                self.setLastError(message)
                try? tunnel.stop()
                self.coreTunnel = nil
                completionHandler(tunnelError(message))
                return
            }
            self.bridge?.start()
            self.startTimers()
            self.startPathMonitor()
            SharedLog.append("Tunnel started (mode: \(config.mode))")
            completionHandler(nil)
        }
    }

    // MARK: - Stop

    public override func stopTunnel(with reason: NEProviderStopReason,
                                    completionHandler: @escaping () -> Void) {
        SharedLog.append("Stopping tunnel (reason: \(reason.rawValue))")
        // The session is over: stop observing first, so no path
        // callback can pause or resume this teardown, and a stale
        // pause must not survive into the next start.
        providerStopped = true
        networkPaused = false
        stopPathMonitor()
        pauseWorkItem?.cancel()
        pauseWorkItem = nil
        stopTimers()
        // Take the bridge out first so a concurrent pause cannot
        // stop the same instance twice.
        let oldBridge = bridge
        bridge = nil
        oldBridge?.stop()
        if let tunnel = coreTunnel {
            try? tunnel.detachTun()
            try? tunnel.stop()
        }
        coreTunnel = nil
        writeStoppedStats()
        completionHandler()
    }

    // MARK: - Offline pause (field test 1.0.0)

    /// Start observing the network path once the tunnel is up.
    /// A monitor already running is kept: the pause does not stop
    /// it, so it keeps watching and fires the resume when a
    /// network returns.
    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            self?.pathDidChange(path)
        }
        monitor.start(queue: DispatchQueue(label: "org.breakthebarriers.unbar.pathmonitor"))
        pathMonitor = monitor
    }

    private func stopPathMonitor() {
        pathMonitor?.cancel()
        pathMonitor = nil
    }

    /// (1.0.3): the active physical interface of a path — the
    /// first of availableInterfaces that is Wi-Fi, cellular or
    /// wired (never our own utun, which reports as .other).
    private func activeInterfaceName(_ path: Network.NWPath) -> String? {
        let physical = path.availableInterfaces.first {
            $0.type == .wifi || $0.type == .cellular || $0.type == .wiredEthernet
        }
        return physical?.name ?? path.availableInterfaces.first?.name
    }

    /// (1.0.3): the active interface name right now. Uses the
    /// running monitor when there is one; otherwise (first start:
    /// the monitor only starts once the tunnel is up) a short-lived
    /// monitor is read, bounded to 0.5s. nil when unknown.
    private func currentActiveInterfaceName() -> String? {
        if let path = pathMonitor?.currentPath, path.status == .satisfied {
            return activeInterfaceName(path)
        }
        let monitor = NWPathMonitor()
        let queue = DispatchQueue(label: "org.breakthebarriers.unbar.pathprobe")
        let semaphore = DispatchSemaphore(value: 0)
        var name: String?
        monitor.pathUpdateHandler = { [weak self] path in
            // Runs on `queue`; read back through queue.sync below.
            name = self?.activeInterfaceName(path)
            semaphore.signal()
        }
        monitor.start(queue: queue)
        _ = semaphore.wait(timeout: .now() + 0.5)
        monitor.cancel()
        return queue.sync { name }
    }

    private func pathDidChange(_ path: Network.NWPath) {
        // (1.0.3): per-network resolver memory follows the active
        // interface. A changed interface/network resets the synced
        // sets so the next stats tick merges the live verified set
        // into the NEW network's pool and hands that pool to the
        // core.
        if path.status == .satisfied {
            let name = activeInterfaceName(path)
            let store = ResolverHealthStore.shared
            let oldKey = lastNetworkKey
            store.interfaceNameOverride = name
            let newKey = store.currentNetworkKey()
            if newKey != oldKey {
                lastNetworkKey = newKey
                lastSyncedVerifiedAddrs = []
                lastSeenProbeAddrs = []
                let label = newKey.map { String($0.split(separator: ":").first ?? "") } ?? "global"
                SharedLog.append("Resolver memory: network=\(label) pool=\(store.load().count)")
            }
        }
        // Satisfied means a usable path exists; anything else
        // (unsatisfied, or requiresConnection) means the tunnel
        // cannot send, so it pauses like a total loss.
        if path.status == .satisfied {
            resumeIfPaused()
        } else {
            pauseIfNeeded()
        }
    }

    /// Total loss: no usable path. The old code did nothing here,
    /// so the tunnel stayed up and the Go core kept firing DNS
    /// queries into the void — every query expired, paths
    /// suspended, and app connections piled past the stream cap
    /// (field test 1.0.0: Streams 400/256). Pause instead: stop
    /// the tunnel now (no new streams) and resume fresh when a
    /// network returns. Runs on a background queue, never on the
    /// monitor queue: the stops block and the monitor queue must
    /// keep delivering path changes.
    private func pauseIfNeeded() {
        guard !providerStopped, !networkPaused, coreTunnel != nil else { return }
        networkPaused = true
        consecutiveHealthFailures = 0
        SharedLog.append("Network lost; pausing tunnel until a network returns")
        pauseWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.pauseTunnelForOffline()
        }
        pauseWorkItem = item
        DispatchQueue.global(qos: .utility).async(group: pauseGroup, execute: item)
    }

    /// Stop the tunnel the same way stopTunnel does (bridge, then
    /// the Go tunnel), but do NOT cancel the provider and do NOT
    /// restart it. The monitor stays alive to resume on the next
    /// satisfied path. The flag is re-checked after every step: a
    /// resume clears it, and the job must then abort instead of
    /// tearing down the fresh tunnel. The tunnel instance is
    /// captured up front and only cleared when it is still the
    /// current one, so a resume that already replaced it is never
    /// touched. In SOCKS mode the bridge stop is a no-op and there
    /// is no TUN, so only the Go stop applies.
    private func pauseTunnelForOffline() {
        guard networkPaused, !providerStopped else { return }
        guard let tunnel = coreTunnel else { return }
        // Take the bridge out first so a concurrent stop cannot
        // stop the same instance twice.
        let oldBridge = bridge
        bridge = nil
        oldBridge?.stop()
        guard networkPaused, !providerStopped else { return }
        try? tunnel.detachTun()
        try? tunnel.stop()
        guard networkPaused, !providerStopped else { return }
        if coreTunnel === tunnel {
            coreTunnel = nil
            // What moves the Home card to disconnected: the card
            // reads the stats snapshot, not the tunnel object.
            writeStoppedStats()
            SharedLog.append("Tunnel paused: no network; will resume when one returns")
        }
    }

    /// Resume after an offline pause. The flag is cleared
    /// synchronously, before the restart, so the pause job aborts
    /// at its next check instead of tearing down the fresh tunnel.
    private func resumeIfPaused() {
        guard networkPaused, !providerStopped else { return }
        networkPaused = false
        SharedLog.append("Network available; resuming paused tunnel")
        restartTunnel()
    }

    /// Restart through the same start path a fresh start uses.
    /// A failed resume only logs: the provider stays alive and
    /// the monitor retries on the next path change. It never
    /// cancels the tunnel with an error on network loss.
    private func restartTunnel() {
        startTunnelCore { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                let message = "Resume failed: \(error.localizedDescription)"
                SharedLog.append(message)
                self.setLastError(message)
            } else {
                SharedLog.append("Tunnel resumed")
            }
        }
    }

    // MARK: - TUN attachment

    /// Returns true when the core has a working fd (direct or bridged).
    private func attachTun(tunnel: CoreTunnel) -> Bool {
        if let fd = directTunnelFd() {
            do {
                try tunnel.attachTun(Int(fd))
                SharedLog.append("Attached direct TUN fd \(fd)")
                return true
            } catch {
                SharedLog.append("Direct TUN attach failed: \(error.localizedDescription)")
            }
        } else {
            SharedLog.append("Direct TUN fd not available; using the packet bridge")
        }
        guard let bridge = PacketBridge(provider: self) else {
            SharedLog.append("Packet bridge creation failed")
            return false
        }
        do {
            try tunnel.attachTun(Int(bridge.coreFd))
            self.bridge = bridge
            SharedLog.append("Attached packet-bridge fd \(bridge.coreFd)")
            return true
        } catch {
            SharedLog.append("Bridge TUN attach failed: \(error.localizedDescription)")
            bridge.stop()
            return false
        }
    }

    // MARK: - Network settings

    private func makeNetworkSettings(for config: TunnelConfig) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: config.zone)
        let ipv4 = NEIPv4Settings(addresses: ["10.8.0.2"],
                                  subnetMasks: ["255.255.255.255"])
        if config.mode == "vpn" {
            // Full tunnel: every packet goes through the TUN.
            ipv4.includedRoutes = [NEIPv4Route.default()]
        } else {
            // SOCKS mode: capture nothing; the device keeps its normal
            // routes and only the SOCKS listener is offered.
            ipv4.includedRoutes = []
        }
        settings.ipv4Settings = ipv4
        if config.mode == "vpn" {
            let dns = NEDNSSettings(servers: ["10.8.0.1"])
            dns.matchDomains = [""]  // all domains resolve through the tunnel
            settings.dnsSettings = dns
        }
        settings.mtu = NSNumber(value: config.mtu)
        return settings
    }

    // MARK: - Direct fd via the Objective-C runtime

    /// The utun fd, read from private ivars without KVC (a missing ivar
    /// returns nil instead of raising). Returns nil when the ivars are
    /// not found or the fd is not valid.
    private func directTunnelFd() -> Int32? {
        if let socket = ivarObject(packetFlow as NSObject, "socket"),
           let fd = ivarInt(socket, "fileDescriptor"),
           fd >= 0, fcntl(fd, F_GETFD) != -1 {
            return fd
        }
        if let fd = ivarInt(self, "tunnelFileDescriptor"),
           fd >= 0, fcntl(fd, F_GETFD) != -1 {
            return fd
        }
        return nil
    }

    private func ivarObject(_ object: NSObject, _ name: String) -> NSObject? {
        guard let ivar = class_getInstanceVariable(type(of: object), name),
              let value = object_getIvar(object, ivar) else {
            return nil
        }
        return value as? NSObject
    }

    /// Reads an integer ivar, whatever its width (int or long).
    private func ivarInt(_ object: NSObject, _ name: String) -> Int32? {
        guard let ivar = class_getInstanceVariable(type(of: object), name) else {
            return nil
        }
        let offset = ivar_getOffset(ivar)
        let base = Unmanaged.passUnretained(object).toOpaque()
        guard let encoding = ivar_getTypeEncoding(ivar) else { return nil }
        switch String(cString: encoding) {
        case "i", "I":
            return base.advanced(by: offset).assumingMemoryBound(to: Int32.self).pointee
        case "q", "Q":
            let value = base.advanced(by: offset).assumingMemoryBound(to: Int64.self).pointee
            guard value >= 0, value <= Int64(Int32.max) else { return nil }
            return Int32(value)
        default:
            return nil
        }
    }

    // MARK: - Timers: stats + health

    private func startTimers() {
        stopTimers()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.publishStats()
        }
        healthTimer = Timer.scheduledTimer(withTimeInterval: 15.0, repeats: true) { [weak self] _ in
            self?.healthCheck()
        }
        publishStats()
    }

    private func stopTimers() {
        statsTimer?.invalidate()
        statsTimer = nil
        healthTimer?.invalidate()
        healthTimer = nil
    }

    private func publishStats() {
        // Offline pause: while paused the bridge is stopped, so
        // there is nothing live to publish. Mirrors the Android
        // watchdog skipping its tick while paused.
        guard !networkPaused else { return }
        guard let tunnel = coreTunnel else { return }
        guard let snapshot = StatsSnapshot.fromStatsJSON(tunnel.stats()) else { return }
        if let data = snapshot.encode() {
            AppGroup.defaults?.set(data, forKey: SharedKey.stats)
            AppGroup.defaults?.set(snapshot.updatedAt, forKey: SharedKey.statsUpdatedAt)
        }
        syncVerifiedResolvers(snapshot)
    }

    /// Remember the Soar-verified resolvers (1.0.53, item 40).
    /// ONLY the verified list ever enters the memory — the
    /// plain-DNS "responding" count never does. The sync MERGES:
    /// the live verified set joins the stored pool, so a resolver
    /// that drops out of the live set is NOT forgotten — it stays
    /// until the 30s loop evicts it (3 missed probes) or it goes
    /// stale (7 days). Built-in mode only: Custom-mode paths are
    /// the user's own resolvers, not the built-in pool. Compared
    /// as a set, so a mere RTT reordering does not rewrite the
    /// store on every tick.
    private func syncVerifiedResolvers(_ snapshot: StatsSnapshot) {
        guard config?.resolverMode == "builtIn" else { return }
        let verified = snapshot.verifiedResolvers
        guard !verified.isEmpty else { return }
        let verifiedAddrs = Set(verified.map { $0.addr })
        let probeAddrs = Set(snapshot.probeResolvers)
        guard verifiedAddrs != lastSyncedVerifiedAddrs
                || probeAddrs != lastSeenProbeAddrs else { return }
        lastSyncedVerifiedAddrs = verifiedAddrs
        lastSeenProbeAddrs = probeAddrs
        // Evicted by the 30s loop since the last handoff: in the
        // handed set but no longer in the core's probe list. They
        // leave the pool here — the loop's 3-miss verdict is the
        // pool's death path (staleness is the backstop for entries
        // the loop never re-probes). An empty probe list means the
        // core predates the field (or never received a list):
        // evict nothing.
        let evicted: Set<String> = probeAddrs.isEmpty ? [] : lastHandedProbeAddrs.subtracting(probeAddrs)
        ResolverHealthStore.shared.merge(verified.map {
            RememberedResolver(addr: $0.addr, rttMs: Int($0.rttMs), checkedAtMs: 0, transport: $0.transport)
        }, evicting: evicted)
        // Hand the pool to the Go core when it changed, so its
        // 30s loop rotates over the fresh pool. (The old code
        // handed the list on every sync — that reset the loop's
        // miss counters and rotation cursor each time.)
        let poolAddrs = ResolverHealthStore.shared.freshAddrs()
        if Set(poolAddrs) != lastHandedProbeAddrs {
            lastHandedProbeAddrs = Set(poolAddrs)
            coreTunnel?.setHealthyProbeResolvers(poolAddrs.joined(separator: ", "))
        }
    }

    private func writeStoppedStats() {
        let snapshot = StatsSnapshot(up: 0, down: 0, rttMs: 0, state: "stopped",
                                     serverIP: "", updatedAt: Date().timeIntervalSince1970)
        if let data = snapshot.encode() {
            AppGroup.defaults?.set(data, forKey: SharedKey.stats)
        }
    }

    /// Health check: while connecting, fail after 45s without a
    /// connection; while connected, probe the tunnel about once a minute
    /// and fail after two consecutive failures. Failing cancels the
    /// tunnel with an error; with on-demand VPN on, the system restarts
    /// it.
    private func healthCheck() {
        // Offline pause: skip the whole check while paused, or
        // the two-failure teardown would fire a reconnect into
        // a dead network. Mirrors the Android watchdog skip.
        guard !networkPaused else { return }
        guard let tunnel = coreTunnel else { return }
        healthTicks += 1
        let state = StatsSnapshot.fromStatsJSON(tunnel.stats())?.state ?? "stopped"
        if state != "connected" {
            if Date().timeIntervalSince(startTime) > 45 {
                let message = "Tunnel did not connect within 45 seconds"
                SharedLog.append(message)
                setLastError(message)
                cancelTunnelWithError(tunnelError(message))
            }
            return
        }
        guard healthTicks % 4 == 0 else { return }  // ~once a minute
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self, let tunnel = self.coreTunnel else { return }
            let failure = tunnel.test()
            DispatchQueue.main.async {
                if failure.isEmpty {
                    self.consecutiveHealthFailures = 0
                } else {
                    self.consecutiveHealthFailures += 1
                    SharedLog.append("Health check failed (\(self.consecutiveHealthFailures)): \(failure)")
                    if self.consecutiveHealthFailures >= 2 {
                        let message = "Tunnel health check failed: \(failure)"
                        self.setLastError(message)
                        self.cancelTunnelWithError(self.tunnelError(message))
                    }
                }
            }
        }
    }

    // MARK: - Helpers

    private func tunnelError(_ message: String) -> NSError {
        return NSError(domain: "org.breakthebarriers.unbar",
                       code: 1,
                       userInfo: [NSLocalizedDescriptionKey: message])
    }

    private func setLastError(_ message: String) {
        AppGroup.defaults?.set(message, forKey: SharedKey.lastError)
    }
}

// MARK: - PacketBridge
//
// Fallback data plane: a SOCK_DGRAM Unix socketpair. One end is handed
// to the Go core (which only does plain Read/Write on it); the other
// end is shuttled to/from packetFlow here. SOCK_DGRAM preserves packet
// boundaries, so each datagram is exactly one IP packet.

private final class PacketBridge {
    /// The fd handed to Core AttachTun.
    let coreFd: Int32
    /// The fd this class reads/writes on.
    private let swiftFd: Int32
    private weak var provider: NEPacketTunnelProvider?
    private var readSource: DispatchSourceRead?
    private var running = false

    init?(provider: NEPacketTunnelProvider) {
        var fds = [Int32](repeating: -1, count: 2)
        guard socketpair(AF_UNIX, SOCK_DGRAM, 0, &fds) == 0,
              fds[0] >= 0, fds[1] >= 0 else {
            return nil
        }
        coreFd = fds[0]
        swiftFd = fds[1]
        self.provider = provider
        // Non-blocking on the Swift side so the dispatch source can drain it.
        _ = fcntl(swiftFd, F_SETFL, O_NONBLOCK)
    }

    func start() {
        guard !running else { return }
        running = true
        let queue = DispatchQueue(label: "org.breakthebarriers.unbar.bridge")
        readSource = DispatchSource.makeReadSource(fileDescriptor: swiftFd, queue: queue)
        readSource?.setEventHandler { [weak self] in
            self?.drainSocket()
        }
        readSource?.resume()
        pumpRead()
    }

    func stop() {
        running = false
        readSource?.cancel()
        readSource = nil
        close(swiftFd)
        close(coreFd)
    }

    /// Packets from the system -> the socket -> the Go core.
    private func pumpRead() {
        guard running, let provider = provider else { return }
        provider.packetFlow.readPackets { [weak self] packets, _ in
            guard let self = self, self.running else { return }
            for packet in packets {
                packet.withUnsafeBytes { raw in
                    guard let base = raw.baseAddress else { return }
                    _ = send(self.swiftFd, base, packet.count, 0)
                }
            }
            self.pumpRead()
        }
    }

    /// Packets from the Go core -> the socket -> the system.
    private func drainSocket() {
        var buffer = [UInt8](repeating: 0, count: 65535)
        while running {
            let count = recv(swiftFd, &buffer, buffer.count, 0)
            guard count > 0 else { return }
            let data = Data(buffer[0..<count])
            provider?.packetFlow.writePackets([data],
                                              withProtocols: [NSNumber(value: AF_INET)])
        }
    }
}

// MARK: - MTU Auto (1.0.34)

/// MTU Auto: resolve the MTU setting to the effective value used
/// for the Go tunstack (via SetMTU) and the TUN interface (via the
/// network settings). A manual value (1280/1400/1500) is used
/// as-is. Auto (0) measures the active network interface MTU (1.0.3:
/// the interface NWPathMonitor reports as active) and
/// subtracts the tunnel encapsulation overhead (IP + UDP + DNS
/// framing), so the TUN MTU fits the real path instead of
/// guessing; it falls back to 1280 (the safe value) when the
/// interface MTU cannot be read.
private let mtuAutoOverhead = 200
private let mtuAutoFallback = 1280

private func resolveMTU(_ mtuSetting: Int, interfaceName: String? = nil) -> Int {
    if mtuSetting != 0 { return mtuSetting }
    let ifaceMtu = readInterfaceMTU(preferred: interfaceName)
    let effective = (ifaceMtu ?? mtuAutoFallback) - mtuAutoOverhead
    return min(max(effective, 1280), 1500)
}

/// Read an interface MTU via getifaddrs: the AF_LINK entry's
/// if_data carries ifi_mtu. (1.0.3): when `preferred` (the active
/// interface from NWPathMonitor currentPath.availableInterfaces) is
/// given, that interface's MTU is used; with no name, or no match,
/// it falls back to the old behaviour — the first usable
/// (non-loopback) interface.
private func readInterfaceMTU(preferred: String? = nil) -> Int? {
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }
    var firstUsable: Int?
    var cursor: UnsafeMutablePointer<ifaddrs>? = first
    while let ptr = cursor {
        let ifa = ptr.pointee
        if let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_LINK) {
            let name = String(cString: ifa.ifa_name)
            if name != "lo0", let data = ifa.ifa_data {
                let mtu = Int(data.assumingMemoryBound(to: if_data.self).pointee.ifi_mtu)
                if mtu > 0 {
                    if let wanted = preferred {
                        if name == wanted { return mtu }
                        if firstUsable == nil { firstUsable = mtu }
                    } else {
                        return mtu
                    }
                }
            }
        }
        cursor = ifa.ifa_next
    }
    return firstUsable
}
