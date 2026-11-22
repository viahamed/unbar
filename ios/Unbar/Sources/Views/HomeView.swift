// HomeView.swift — the Model B dashboard.
// A big status card ("You are not protected" / "You are protected" plus the connected server's IP), live stat cards (speed, latency, data usage), the active server, and the status-card footer that connects/disconnects (1.0.53, item 45, variant D — the big Connect button is gone).

import SwiftUI

public struct HomeView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var vpn: VPNManager
    @EnvironmentObject private var stats: StatsStore
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var router: TabRouter
    @Environment(\.colorScheme) private var colorScheme

@State private var showError = false
@State private var errorKey = ""
@State private var showLogs = false

public init() {}

public var body: some View {
    NavigationView {
        ScrollView {
            VStack(spacing: 16) {
                brandHeader
                tutorialSection
                statusCard
                statsGrid
                serverRow
                if vpn.state == .connected && settings.connectionMode == .socks {
                    NoteText(loc.tr("home.socks_note"))
                }
                if let error = vpn.lastError, vpn.state == .disconnected {
                    NoteText(error)
                }
                Spacer().frame(height: 60)
            }
            .padding()
        }
        // Page background (before-release: light #EAF0F8, dark unchanged).
        .background(Color("AppBackground"))
        // (1.0.31): the Home content keeps the locale direction
        // (RTL in Persian); the nav bar itself is forced LTR by the
        // NavigationView environment below, so the title stays left
        // and the moon toggle stays right.
        .environment(\.layoutDirection, loc.isRTL ? .rightToLeft : .leftToRight)
        .navigationBarTitle(loc.tr("home.title"), displayMode: .large)
        .navigationBarItems(trailing: topBarButtons)
        // Hidden link driven by the debug shortcut button.
        .background(
            NavigationLink(destination: LogsView().environmentObject(loc), isActive: $showLogs) {
                EmptyView()
            }
        )
    }
    // (1.0.31): the Home top bar (nav title + moon toggle) stays LTR
    // in every language — in Persian the bar must not mirror.
    .environment(\.layoutDirection, .leftToRight)
    .onAppear {
        if vpn.isConnected {
            stats.startPolling()
            vpn.maybeBackgroundRescan()
        }
    }
    .onReceive(NotificationCenter.default.publisher(
        for: UIApplication.willEnterForegroundNotification)) { _ in
        // (1.0.53, item 40): foreground while connected is a
        // background re-scan trigger — a thin/stale pool refreshes
        // silently. maybeBackgroundRescan itself guards on
        // connected, Built-in, pool state, and once-a-day.
        if vpn.isConnected {
            vpn.maybeBackgroundRescan()
        }
    }
    .onReceive(vpn.$state) { state in
        if state == .connected {
            stats.startPolling()
            vpn.maybeBackgroundRescan()
            if let server = settings.activeServer {
                ConnectionHistoryStore.shared.sessionStarted(
                    serverName: server.name, serverZone: server.zone,
                    upBytes: stats.upBytes, downBytes: stats.downBytes)
            }
        } else if state == .disconnected {
            stats.stopPolling()
            ConnectionHistoryStore.shared.sessionEnded(
                upBytes: stats.upBytes, downBytes: stats.downBytes)
        }
    }
    .alert(isPresented: $showError) {
        Alert(title: Text(loc.tr("common.error")),
              message: Text(loc.tr(errorKey)),
              dismissButton: .default(Text(loc.tr("common.ok"))))
    }
}

// MARK: - Top bar buttons

/// Debug shortcut (1.0.38) next to the theme toggle: opens the
/// logs screen. The theme toggle stays at the far edge.
private var topBarButtons: some View {
    HStack(spacing: 4) {
        Button(action: { showLogs = true }) {
            Image(systemName: "ladybug.fill")
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(Color("BrandBlue"))
        }
        .accessibilityLabel(Text(loc.tr("settings.logs")))
        themeToggleButton
    }
}

// MARK: - Theme toggle

private var isDarkMode: Bool {
    switch settings.appearance {
    case .dark:
        return true
    case .light:
        return false
    case .system:
        return colorScheme == .dark
    }
}

private var themeToggleButton: some View {
    Button(action: {
        let next: AppearanceChoice = isDarkMode ? .light : .dark
        settings.appearance = next
        ThemeManager.shared.appearance = next
    }) {
        Image(systemName: isDarkMode ? "sun.max.fill" : "moon.fill")
            .font(.system(size: 18, weight: .medium))
            .foregroundColor(Color("BrandBlue"))
    }
}

// MARK: - Brand header + status card

/// "Unbar" with the "by Break The Barriers" tagline next to the
/// wordmark (the tagline stays English in both languages).
private var brandHeader: some View {
    HStack(alignment: .firstTextBaseline) {
        Text(loc.tr("home.title"))
            .font(.system(size: 34, weight: .bold))
        Text(loc.tr("home.tagline"))
            .font(.caption)
            .foregroundColor(.secondary)
        Spacer()
    }
    // (1.0.31): the brand header (logo) stays LTR in every language.
    .environment(\.layoutDirection, .leftToRight)
}

// MARK: - Home tutorial (1.0.53, item 45)
//
// The 12 Home topics, in one ungrouped section like the Servers
// tutorial. Text via loc.tr — keys tutorial.home.<topic>.title/body.

private var tutorialSection: some View {
    TutorialAccordion(titleKey: "guide.title", sections: [TutorialSection(topics: homeTutorialTopics)])
}

private var homeTutorialTopics: [TutorialTopic] {
    [
        TutorialTopic(titleKey: "tutorial.home.status.title",
                      bodyKey: "tutorial.home.status.body"),
        TutorialTopic(titleKey: "tutorial.home.server.title",
                      bodyKey: "tutorial.home.server.body"),
        TutorialTopic(titleKey: "tutorial.home.mode.title",
                      bodyKey: "tutorial.home.mode.body"),
        TutorialTopic(titleKey: "tutorial.home.download.title",
                      bodyKey: "tutorial.home.download.body"),
        TutorialTopic(titleKey: "tutorial.home.latency.title",
                      bodyKey: "tutorial.home.latency.body"),
        TutorialTopic(titleKey: "tutorial.home.quality.title",
                      bodyKey: "tutorial.home.quality.body"),
        TutorialTopic(titleKey: "tutorial.home.active.title",
                      bodyKey: "tutorial.home.active.body"),
        TutorialTopic(titleKey: "tutorial.home.scan.title",
                      bodyKey: "tutorial.home.scan.body"),
        TutorialTopic(titleKey: "tutorial.home.today.title",
                      bodyKey: "tutorial.home.today.body"),
        TutorialTopic(titleKey: "tutorial.home.session.title",
                      bodyKey: "tutorial.home.session.body"),
        TutorialTopic(titleKey: "tutorial.home.time.title",
                      bodyKey: "tutorial.home.time.body"),
        TutorialTopic(titleKey: "tutorial.home.streams.title",
                      bodyKey: "tutorial.home.streams.body"),
    ]
}

private var statusCard: some View {
    let connected = vpn.isConnected
    let connecting = vpn.state == .connecting || vpn.state == .reasserting
    return VStack(alignment: .leading, spacing: 8) {
        HStack(spacing: 8) {
            Image(systemName: connected ? "shield.fill" : "shield.slash.fill")
                .font(.system(size: 28))
                .foregroundColor(.white)
            Text(connected ? loc.tr("home.protected")
                : connecting ? loc.tr("home.connecting")
                : loc.tr("home.not_protected"))
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.white)
        }
        if connected {
            if !stats.serverIP.isEmpty {
                if stats.serverCountry.isEmpty {
                    Text(loc.tr("home.server_ip", stats.serverIP))
                        .font(.subheadline)
                        .foregroundColor(.white)
                } else {
                    // Country flag (bundled asset, all ISO codes)
                    // right after the code, e.g. "Server: 1.1.1.1
                    // (DE)" + German flag; no asset -> text only.
                    HStack(spacing: 6) {
                        Text(String(format: loc.tr("home.server_ip_country"),
                                    locale: Locale(identifier: loc.effectiveCode),
                                    stats.serverIP, stats.serverCountry))
                            .font(.subheadline)
                            .foregroundColor(.white)
                        if let flag = flagImage(for: stats.serverCountry) {
                            flag
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 20, height: 15)
                                .cornerRadius(2)
                        }
                    }
                }
            }
            if let server = settings.activeServer {
                Text(server.name)
                    .font(.subheadline)
                    .foregroundColor(.white.opacity(0.85))
            }
            HStack(spacing: 16) {
                Text(loc.tr("home.session_total",
                            StatsStore.formatBytes(stats.upBytes + stats.downBytes)))
                Text(streamText)
            }
            .font(.subheadline)
            .foregroundColor(.white.opacity(0.85))
        } else {
            Text(loc.tr("home.not_protected_hint"))
                .font(.subheadline)
                .foregroundColor(.white.opacity(0.85))
        }
        // (1.0.53, item 45, variant D): the big Connect button is
        // gone — the card footer connects/disconnects. iOS footer
        // text is ALWAYS white (the card is always colored: blue
        // connected / orange disconnected); the divider is white
        // 20% alpha. The mode switcher, server name and IP rows
        // above are unchanged. iOS has no mode switcher on the
        // card (mode is a SegmentedPicker in Settings).
        Rectangle()
            .fill(Color.white.opacity(0.2))
            .frame(height: 1)
            .padding(.top, 10)
        Button(action: toggleConnection) {
            HStack(spacing: 10) {
                if vpn.isTransitioning {
                    ProgressViewPlaceholder()
                    Text(connecting ? loc.tr("home.connecting") : loc.tr("home.working"))
                } else {
                    Image(systemName: "power")
                        .font(.system(size: 22, weight: .medium))
                    Text(connected ? loc.tr("home.disconnect") : loc.tr("home.connect"))
                }
            }
            .font(.system(size: 17, weight: .bold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
        .buttonStyle(PlainButtonStyle())
        .disabled(vpn.isTransitioning || settings.activeServer == nil)
    }
    .padding(20)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(connected ? Color("BrandBlue") : Color("AccentColor"))
    .cornerRadius(16)
}

/// "Streams: 12/256" while connected (max 0 = just the live count).
private var streamText: String {
    if stats.maxStreams > 0 {
        return "\(loc.tr("home.streams")) \(stats.activeStreams)/\(stats.maxStreams)"
    }
    return "\(loc.tr("home.streams")) \(stats.activeStreams)"
}

// MARK: - Stats

private var statsGrid: some View {
    VStack(spacing: 12) {
        HStack(spacing: 12) {
            StatCard(title: loc.tr("home.download"),
                     value: StatsStore.formatSpeed(stats.downSpeedBps),
                     icon: "arrow.down")
            StatCard(title: loc.tr("home.upload"),
                     value: StatsStore.formatSpeed(stats.upSpeedBps),
                     icon: "arrow.up")
        }
        HStack(spacing: 12) {
            StatCard(title: loc.tr("home.latency"),
                     value: vpn.isConnected ? StatsStore.formatLatency(stats.rttMs) : "—",
                     customIcon: .speed)
            qualityStatView
        }
        HStack(spacing: 12) {
            // (1.0.53, item 41): the LIVE traffic-spreading
            // count — Soar-verified + not-suspended paths,
            // uncapped. "—" while disconnected. The old
            // "below 16 → re-run Scan" signal is gone: dead
            // resolvers are replaced automatically.
            StatCard(title: loc.tr("home.healthy_resolvers"),
                     value: vpn.isConnected ? "\(stats.healthyResolverCount)" : "—",
                     customIcon: .dns)
            scanResolversCard
        }
        HStack(spacing: 12) {
            StatCard(title: loc.tr("home.data_today"),
                     value: StatsStore.formatBytes(stats.todayBytes),
                     customIcon: .dataUsage)
            StatCard(title: loc.tr("home.data_total"),
                     value: StatsStore.formatBytes(stats.upBytes + stats.downBytes),
                     customIcon: .storage)
        }
        HStack(spacing: 12) {
            // Connection time, matching Android (which shows it
            // instead of a monthly total on Home; the monthly total
            // is still in History). Ticks every second via the
            // 1s stats polling re-render while connected.
            StatCard(title: loc.tr("home.connection_time"),
                     value: connectionTimeValue,
                     customIcon: .timer)
            StatCard(title: loc.tr("home.streams"),
                     value: stats.maxStreams > 0
                         ? "\(stats.activeStreams)/\(stats.maxStreams)"
                         : "\(stats.activeStreams)",
                     customIcon: .swapHoriz)
        }
    }
}

/// Bundled flag image for a 2-letter country code ("flag_de"),
/// or nil when the code has no asset — the caller then shows
/// just the code text, never a broken image.
private func flagImage(for countryCode: String) -> Image? {
    guard countryCode.count == 2,
          UIImage(named: "flag_" + countryCode.lowercased()) != nil else {
        return nil
    }
    return Image("flag_" + countryCode.lowercased())
}

/// Connection quality card: same card shell as StatCard, but the
/// value is the quality WORD (large, bold, orange) + the score
/// ("92 / 100"). No progress bar — the word + score are the whole
/// content. Compact inside (10pt vertical padding, 10pt gap) to fit
/// the shared 78pt height. Disconnected (or no live verified path
/// yet): "—".
private var qualityStatView: some View {
    VStack(alignment: .leading, spacing: 10) {
        HStack(spacing: 6) {
            Image("ShieldCheck")
                .resizable()
                .scaledToFit()
                .frame(width: 16, height: 16)
            Text(loc.tr("home.connection_quality"))
                .font(.caption)
                .foregroundColor(.secondary)
        }
        if vpn.isConnected, let score = stats.qualityScore {
            // Five-segment quality bar (design 3): lit segments =
            // quality level (5 Excellent, 4 Good, 3 Fair, 2/1 Poor).
            // No word, no number — the bar alone shows the quality.
            HStack(spacing: 4) {
                let lit = qualityBars(for: score)
                ForEach(0..<5) { i in
                    RoundedRectangle(cornerRadius: 8)
                        .fill(i < lit ? Color("BrandOrange") : Color(UIColor.systemGray4))
                        .frame(maxWidth: .infinity)
                        .frame(height: 16)
                }
            }
        } else {
            Text("—")
                .font(.headline)
        }
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 10)
    .frame(maxWidth: .infinity, alignment: .leading)
    .frame(height: statCardHeight)
    .background(Color(UIColor.secondarySystemBackground))
    .cornerRadius(12)
}

private func qualityBars(for score: Int) -> Int {
    switch score {
    case 80...: return 5
    case 60...: return 4
    case 40...: return 3
    case 20...: return 2
    default: return 1
    }
}

/// Scan-stage state for the Home card stepper, mirroring the
/// Settings stepper (1.0.36): stage 1 is DONE as soon as tested
/// reaches total, stage 2 is ACTIVE while stage 1 is done and
/// the scan still runs, stages 2 and 3 are DONE once the scan
/// completes. All PENDING before any scan.
private enum ScanStepState {
    case pending, active, done
}

/// Scan-resolvers shortcut card (1.0.53, item 39): same card
/// shell as StatCard. Top: magnifier icon + label + a small
/// orange "Scan" pill at the right (disabled/greyed while a
/// scan runs). Below: the horizontal 3-stage stepper (variant
/// C) — blue hollow circle = pending, orange spinning ring =
/// active, orange filled circle with a white check = done
/// (checks always sit inside the circle). NO text under the
/// bar — just the button and the stepper. Tapping Scan runs
/// the same testResolvers call the Settings button uses.
/// Compact inside to fit the shared 78pt height.
private var scanResolversCard: some View {
    let running = vpn.testScanRunning
    return VStack(alignment: .leading, spacing: 8) {
        HStack(spacing: 6) {
            Image("MagnifierCheck")
                .resizable()
                .scaledToFit()
                .frame(width: 16, height: 16)
            Text(loc.tr("settings.test_resolvers"))
                .font(.caption)
                .foregroundColor(.secondary)
            Spacer()
            Button(action: { vpn.testResolvers() }) {
                Text(loc.tr("home.test_button"))
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(running ? .secondary : .white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(running ? Color(UIColor.systemGray5) : Color("BrandOrange"))
                    .cornerRadius(11)
            }
            .disabled(running)
            .buttonStyle(PlainButtonStyle())
        }
        HStack(spacing: 0) {
            scanStepNode(scanStage1State)
            scanStepConnector
            scanStepNode(scanStage2State)
            scanStepConnector
            scanStepNode(scanStage3State)
        }
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 10)
    .frame(maxWidth: .infinity, alignment: .leading)
    .frame(height: statCardHeight)
    .background(Color(UIColor.secondarySystemBackground))
    .cornerRadius(12)
}

/// True once a scan has completed (or the tunnel already has
/// verified paths) — the stepper then shows all 3 stages done.
private var scanCompleted: Bool {
    !vpn.testScanRunning && (vpn.scanTestedCount > 0
        || vpn.scanRespondingCount > 0
        || vpn.scanVerifiedCount > 0
        || stats.verifiedCount > 0)
}

private var scanStage1Done: Bool {
    scanCompleted || (vpn.scanTotalCount > 0 && vpn.scanTestedCount >= vpn.scanTotalCount)
}

private var scanStage1State: ScanStepState {
    if scanStage1Done { return .done }
    if vpn.testScanRunning { return .active }
    return .pending
}

private var scanStage2State: ScanStepState {
    if scanCompleted { return .done }
    if scanStage1Done && vpn.testScanRunning { return .active }
    return .pending
}

private var scanStage3State: ScanStepState {
    scanCompleted ? .done : .pending
}

private var scanStepConnector: some View {
    Rectangle()
        .fill(Color(UIColor.systemGray5))
        .frame(maxWidth: .infinity)
        .frame(height: 2)
}

private func scanStepNode(_ state: ScanStepState) -> some View {
    ZStack {
        switch state {
        case .done:
            ZStack {
                Circle()
                    .fill(Color("BrandOrange"))
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
            }
        case .active:
            // Orange spinning ring (the 20pt medium indicator,
            // scaled to the 18pt node).
            ActivityIndicator(color: UIColor(named: "BrandOrange"))
                .scaleEffect(0.9)
        case .pending:
            Circle()
                .strokeBorder(Color("BrandBlue"), lineWidth: 2)
        }
    }
    .frame(width: 18, height: 18)
}

/// Connection time (HH:MM:SS) since the current session started,
/// matching Android's ConnectionTimeStatCard. "—" when disconnected.
private var connectionTimeValue: String {
    guard vpn.isConnected,
          let start = ConnectionHistoryStore.shared.currentSessionStartedAt else {
        return "—"
    }
    return formatElapsed(Date().timeIntervalSince(start))
}

private func formatElapsed(_ seconds: TimeInterval) -> String {
    let total = max(0, Int(seconds))
    let h = total / 3600
    let m = (total % 3600) / 60
    let s = total % 60
    return String(format: "%02d:%02d:%02d", h, m, s)
}

// MARK: - Server row

private var serverRow: some View {
    HStack {
        Image(systemName: "server.rack")
            .foregroundColor(Color("BrandBlue"))
        if let server = settings.activeServer {
            VStack(alignment: .leading, spacing: 2) {
                Text(server.name).font(.headline)
                Text(server.zone).font(.caption).foregroundColor(.secondary)
            }
        } else {
            Text(loc.tr("home.no_server")).font(.headline)
        }
        Spacer()
        Button(action: { router.selectedTab = 1 }) {
            Text(loc.tr("home.change"))
                .font(.subheadline)
                .foregroundColor(Color("BrandBlue"))
        }
    }
    .padding(12)
    .background(Color(UIColor.secondarySystemBackground))
    .cornerRadius(12)
}

private func toggleConnection() {
    if vpn.isConnected {
        vpn.disconnect()
        return
    }
    vpn.connect { errorKey in
        if let key = errorKey {
            self.errorKey = key
            self.showError = true
        }
    }
}

}

/// The button shows a spinning activity indicator from UIKit instead of SwiftUI's ProgressView.
private struct ProgressViewPlaceholder: UIViewRepresentable {
    func makeUIView(context: Context) -> UIActivityIndicatorView {
        let view = UIActivityIndicatorView(style: .medium)
        view.color = .white
        view.startAnimating()
        return view
    }

    func updateUIView(_ uiView: UIActivityIndicatorView, context: Context) {}

}
