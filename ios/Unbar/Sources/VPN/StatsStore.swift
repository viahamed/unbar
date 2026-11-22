// StatsStore.swift — live stats for the Model B dashboard.
//
// The extension writes a StatsSnapshot into the shared App Group about
// once a second. This store polls it on a 1-second timer while the VPN
// is up and derives current up/down speeds from deltas of the
// cumulative counters. Daily and monthly usage totals are accumulated
// locally in the shared defaults (keyed by day/month) so they survive
// app restarts.

import Foundation
import Combine

public final class StatsStore: ObservableObject {
    public static let shared = StatsStore()

    @Published public var upBytes: Int64 = 0
    @Published public var downBytes: Int64 = 0
    @Published public var upSpeedBps: Int64 = 0
    @Published public var downSpeedBps: Int64 = 0
    @Published public var rttMs: Int64 = 0
    @Published public var serverIP: String = ""
    @Published public var serverCountry: String = ""
    @Published public var tunnelState: String = "stopped"
    @Published public var todayBytes: Int64 = 0
    @Published public var monthBytes: Int64 = 0
    /// Soar-verified healthy resolver count (never plain-DNS alone).
    @Published public var healthyResolverCount: Int = 0
    /// Background healthy probe count: size of the Go core's
    /// background probe list (the pool, minus any resolver
    /// evicted after 3 missed probes in a row).
    @Published public var healthyProbeCount: Int = 0
    @Published public var totalResolvers: Int = 0
    /// Stage-1 plain-DNS responding count (weak signal).
    @Published public var respondingResolvers: Int = 0
    /// Probes completed in the current/most recent full scan
    /// (0 before any scan). Live "tested X" for the scan stepper.
    @Published public var testedResolvers: Int = 0
    /// Soar-verified resolver paths, ranked by RTT, uncapped
    /// (1.0.53, item 40) — every verified resolver lands in the
    /// pool.
    @Published public var verifiedResolvers: [VerifiedResolver] = []
    @Published public var verifiedCount: Int = 0
    @Published public var scanRunning: Bool = false
    /// Live resolver paths (verified + loss + RTT) from the Go
    /// core's Stats() "pathStats" — feeds the Home quality score.
    @Published public var pathStats: [SoarPathStat] = []
    /// Connection quality 0-100 (Home quality card): smoothed
    /// aggregate across live paths — see rawQualityScore.
    /// nil when disconnected / nothing verified yet.
    @Published public var qualityScore: Int? = nil
    /// Live tunnel stream count and its cap (256).
    @Published public var activeStreams: Int = 0
    @Published public var maxStreams: Int = 0

    private var timer: Timer?
    private var previousUp: Int64?
    private var previousDown: Int64?
    private var previousDate: Date?
    /// EMA state for the quality score (alpha 0.3) — loss is a
    /// 20-outcome window, so the raw score jumps each poll.
    private var smoothedQualityScore: Double? = nil

    private init() {}

    public func startPolling() {
        stopPolling()
        previousUp = nil
        previousDown = nil
        previousDate = nil
        loadUsageTotals()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.poll()
        }
        poll()
    }

    public func stopPolling() {
        timer?.invalidate()
        timer = nil
    }

    private func dayKey(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        return "usage-day-" + formatter.string(from: date)
    }

    private func monthKey(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM"
        return "usage-month-" + formatter.string(from: date)
    }

    private func loadUsageTotals() {
        let defaults = AppGroup.defaults
        let now = Date()
        todayBytes = defaults?.object(forKey: dayKey(now)) as? Int64 ?? 0
        monthBytes = defaults?.object(forKey: monthKey(now)) as? Int64 ?? 0
    }

    private func poll() {
        guard let defaults = AppGroup.defaults,
              let data = defaults.data(forKey: SharedKey.stats),
              let snapshot = StatsSnapshot.decode(data) else {
            return
        }
        let now = Date()
        if let prevUp = previousUp, let prevDown = previousDown,
           let prevDate = previousDate {
            let elapsed = now.timeIntervalSince(prevDate)
            if elapsed > 0 {
                upSpeedBps = Int64(Double(snapshot.up - prevUp) / elapsed)
                downSpeedBps = Int64(Double(snapshot.down - prevDown) / elapsed)
                let delta = max(Int64(0), (snapshot.up - prevUp) + (snapshot.down - prevDown))
                if delta > 0 {
                    todayBytes += delta
                    monthBytes += delta
                    defaults.set(todayBytes, forKey: dayKey(now))
                    defaults.set(monthBytes, forKey: monthKey(now))
                }
            }
        }
        previousUp = snapshot.up
        previousDown = snapshot.down
        previousDate = now
        upBytes = snapshot.up
        downBytes = snapshot.down
        rttMs = snapshot.rttMs
        serverIP = snapshot.serverIP
        serverCountry = snapshot.serverCountry
        tunnelState = snapshot.state
        healthyResolverCount = snapshot.healthyResolvers
        healthyProbeCount = snapshot.healthyProbeCount
        totalResolvers = snapshot.totalResolvers
        respondingResolvers = snapshot.respondingResolvers
        testedResolvers = snapshot.testedResolvers
        verifiedResolvers = snapshot.verifiedResolvers
        verifiedCount = snapshot.verifiedCount
        scanRunning = snapshot.scanRunning
        pathStats = snapshot.pathStats
        // Home quality score: smoothed aggregate (see
        // rawQualityScore). EMA (alpha 0.3) keeps the bars from
        // flickering — loss is a 20-outcome window, so the raw
        // score jumps each poll.
        if let raw = rawQualityScore(from: snapshot.pathStats) {
            if let prev = smoothedQualityScore {
                smoothedQualityScore = prev * 0.7 + Double(raw) * 0.3
            } else {
                smoothedQualityScore = Double(raw)
            }
            qualityScore = Int(smoothedQualityScore!.rounded())
        } else {
            smoothedQualityScore = nil
            qualityScore = nil
        }
        activeStreams = snapshot.activeStreams
        maxStreams = snapshot.maxStreams
    }

    // MARK: - Formatting

    /// Connection quality (Home quality card): 0-100 aggregated
    /// across ALL live verified paths — not just the best one, so
    /// one lucky path can't mask a dying tunnel and one hiccup on
    /// the best path can't tank the score. Loss = mean across live
    /// paths; RTT = median (robust to one very slow/fast path).
    /// Redundancy penalizes the FRACTION of configured paths that
    /// are dead — a deliberately single-path setup (1/1 live) is
    /// not fragile, but 1 live out of 16 configured is.
    /// RTT penalty starts at 80 ms and ramps (rtt-80)/10, capped
    /// at 40 — RTT is what the user feels on every query. nil when
    /// no live verified path exists (disconnected / nothing
    /// verified yet).
    private func rawQualityScore(from paths: [SoarPathStat]) -> Int? {
        let live = paths.filter { $0.verified && !$0.suspended }
        guard !live.isEmpty else { return nil }
        let meanLoss = live.reduce(0.0) { $0 + $1.loss } / Double(live.count)
        let sortedRtt = live.map { $0.rttMs }.sorted()
        let medianRtt = sortedRtt[live.count / 2]
        let rttPenalty = min(40.0, max(0.0, Double(medianRtt - 80) / 10.0))
        let redundancyPenalty = (1.0 - Double(live.count) / Double(paths.count)) * 20.0
        let score = 100.0 - meanLoss * 60.0 - rttPenalty - redundancyPenalty
        return Int(max(0.0, min(100.0, score)).rounded())
    }

    // LRM prefix: amounts are mathematical (left-to-right), so they must render
    // as "1.1 MB" even in the Persian RTL locale (which would flip it to "MB 1.1").
    public static func formatBytes(_ bytes: Int64) -> String {
        let lrm = "\u{200E}"
        let value = Double(bytes)
        switch value {
        case 0..<1024:
            return lrm + "\(bytes) B"
        case 1024..<(1024 * 1024):
            return lrm + String(format: "%.1f KB", value / 1024)
        case (1024 * 1024)..<(1024 * 1024 * 1024):
            return lrm + String(format: "%.1f MB", value / (1024 * 1024))
        default:
            return lrm + String(format: "%.2f GB", value / (1024 * 1024 * 1024))
        }
    }

    public static func formatSpeed(_ bytesPerSecond: Int64) -> String {
        return formatBytes(bytesPerSecond) + "/s"
    }

    public static func formatLatency(_ ms: Int64) -> String {
        // LRM prefix (same reason as formatBytes): "3048 ms" must not flip to "ms 3048" in RTL.
        return "\u{200E}\(ms) ms"
    }
}
