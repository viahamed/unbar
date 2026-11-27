//
// SettingsView.swift — connection, resolver, proxy, bandwidth, routing and system settings.
//
// Connection settings apply when the tunnel is next started; the on-demand toggle is written to the saved VPN configuration right away. Per-app routing and a kill switch are Android-only and are deliberately NOT here: iOS per-app VPN is enterprise/MDM-only, and iOS has no true kill switch — on-demand VPN is the iOS equivalent.
//
// Resolvers (1.0.16 redesign): Built-in uses the bundled 2,307 Iranian resolvers; Custom uses only the resolvers typed in the field; (1.0.2) Import uses only the list imported from a .txt file (its own stored list; Custom's is never touched). Every setting auto-saves — there is no Save button on any settings section. The custom field saves on a 3s debounce (typing resets the clock) and is never rewritten while typing.

import SwiftUI

public struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var vpn: VPNManager
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var theme: ThemeManager

@State private var socksPortText: String = "1080"
@State private var socksPassword: String = ""
@State private var showingLogs = false

// Check for update: whether a check is running, the release an
// available update offers (shown in the dialog), and the notice
// (up-to-date confirmation or failure) shown in an alert. iOS
// cannot install from inside the app, so the dialog opens the
// release page in Safari instead of downloading a file.
@State private var updateChecking = false
@State private var updateRelease: ReleaseInfo?
@State private var showUpdateDialog = false
@State private var updateNoticeTitle = ""
@State private var updateNoticeMessage = ""
@State private var showUpdateNotice = false

// Custom-resolver editing: the field holds its own text and only
// writes back to settings on a 3s debounce, so the field is never
// rewritten while the user is typing.
@State private var customText: String = ""
@State private var customSaveWorkItem: DispatchWorkItem?

// "Test resolvers" feedback: local state gives the spinner and
// the B1 stepper immediately; completion comes from the scan itself
// (VPNManager polls the core, 120s cap, works disconnected). The
// stepper shows the 3 real stages — DNS + RTT screening (live
// tested/total + responding), Soar handshake vetting (verified),
// ranking + saving (remembered count) — with an orange checkmark
// per completed stage. Blue + orange only, never green.
@State private var isTestingResolvers = false
@State private var scanFinished = false

// Remembered healthy resolvers (1.0.53, item 40: the single
// Soar-verified pool, ~600 entries), refreshed whenever the
// view appears or a scan finishes.
@State private var remembered: [RememberedResolver] = []
// (1.0.3): the core's pre-scan check of the Custom / Import list
// (CoreCheckResolverList), refreshed off the main thread.
@State private var listCheck: ResolverListCheck?

public init() {}

public var body: some View {
    NavigationView {
        Form {
            tutorialSection
            connectionSection
            resolverSection
            proxySection
            bandwidthSection
            routingSection
            systemSection
            supportSection
            Section(footer: Color.clear.frame(height: 56)) {
                EmptyView()
            }
        }
        .navigationBarTitle(loc.tr("settings.title"), displayMode: .large)
        .onAppear {
            socksPortText = "\(settings.socksPort)"
            socksPassword = KeychainHelper.shared.socksPassword()
            customText = settings.customResolvers
            refreshRemembered()
        }
        .onDisappear {
            save()
        }
        .alert(isPresented: $showUpdateDialog) {
            Alert(title: Text(loc.tr("update.available_title")),
                  message: Text(updateDialogMessage),
                  primaryButton: .default(Text(loc.tr("update.open_release"))) {
                      if let release = updateRelease,
                         let url = URL(string: release.pageURL) {
                          UIApplication.shared.open(url, options: [:], completionHandler: nil)
                      }
                      updateRelease = nil
                  },
                  secondaryButton: .cancel(Text(loc.tr("update.later"))) {
                      updateRelease = nil
                  })
        }
        .alert(isPresented: $showUpdateNotice) {
            Alert(title: Text(updateNoticeTitle),
                  message: Text(updateNoticeMessage),
                  dismissButton: .default(Text(loc.tr("common.ok"))))
        }
    }
}

// MARK: - Sections

// Settings Tutorial (1.0.30, top of the Settings screen): the 12
// settings topics, grouped under 5 group headings. Text via loc.tr.
private var tutorialSection: some View {
    Section {
        TutorialAccordion(sections: settingsTutorialSections)
    }
}

private var settingsTutorialSections: [TutorialSection] {
    [
        TutorialSection(groupTitleKey: "tutorial.settings.group_connection", topics: [
            TutorialTopic(titleKey: "tutorial.settings.vpn.title",
                          bodyKey: "tutorial.settings.vpn.body"),
            TutorialTopic(titleKey: "tutorial.settings.tcp.title",
                          bodyKey: "tutorial.settings.tcp.body"),
            TutorialTopic(titleKey: "tutorial.settings.mtu.title",
                          bodyKey: "tutorial.settings.mtu.body"),
            TutorialTopic(titleKey: "tutorial.settings.answer_size.title",
                          bodyKey: "tutorial.settings.answer_size.body"),
        ]),
        TutorialSection(groupTitleKey: "tutorial.settings.group_resolvers", topics: [
            TutorialTopic(titleKey: "tutorial.settings.resolvers.title",
                          bodyKey: "tutorial.settings.resolvers.body"),
            TutorialTopic(titleKey: "tutorial.settings.test_resolvers.title",
                          bodyKey: "tutorial.settings.test_resolvers.body"),
        ]),
        TutorialSection(groupTitleKey: "tutorial.settings.group_proxy_bandwidth", topics: [
            TutorialTopic(titleKey: "tutorial.settings.proxy.title",
                          bodyKey: "tutorial.settings.proxy.body"),
            TutorialTopic(titleKey: "tutorial.settings.bandwidth.title",
                          bodyKey: "tutorial.settings.bandwidth.body"),
        ]),
        TutorialSection(groupTitleKey: "tutorial.settings.group_routing", topics: [
            TutorialTopic(titleKey: "tutorial.settings.geo_bypass.title",
                          bodyKey: "tutorial.settings.geo_bypass.body"),
            TutorialTopic(titleKey: "tutorial.settings.bypass_list.title",
                          bodyKey: "tutorial.settings.bypass_list.body"),
        ]),
        TutorialSection(groupTitleKey: "tutorial.settings.group_system", topics: [
            TutorialTopic(titleKey: "tutorial.settings.update.title",
                          bodyKey: "tutorial.settings.update.body"),
            TutorialTopic(titleKey: "tutorial.settings.logs.title",
                          bodyKey: "tutorial.settings.logs.body"),
            TutorialTopic(titleKey: "tutorial.settings.paths.title",
                          bodyKey: "tutorial.settings.paths.body"),
        ]),
    ]
}

private var connectionSection: some View {
    Section(header: Text(loc.tr("settings.connection")),
            footer: Text(loc.tr("settings.connection_footer"))) {
        Picker(loc.tr("settings.mode"), selection: $settings.connectionMode) {
            Text(loc.tr("settings.mode_vpn")).tag(ConnectionMode.vpn)
            Text(loc.tr("settings.mode_socks")).tag(ConnectionMode.socks)
        }
        .pickerStyle(SegmentedPickerStyle())
        if settings.connectionMode == .socks {
            NoteText(loc.tr("settings.socks_note"))
        }
        TCPModeSwitchRow(label: loc.tr("settings.tcp_mode"),
                         isOn: $settings.tcpModeEnabled)
        NoteText(loc.tr("settings.tcp_mode_explanation"))
        VStack(alignment: .leading, spacing: 8) {
            Text(loc.tr("settings.mtu_label"))
            HStack(spacing: 10) {
                mtuChip(0)
                ForEach([1280, 1400, 1500], id: \.self) { value in
                    mtuChip(value)
                }
            }
            NoteText(loc.tr("settings.mtu_explanation"))
        }
        VStack(alignment: .leading, spacing: 8) {
            Text(loc.tr("settings.dns_answer_size_label"))
            HStack(spacing: 8) {
                answerSizeChip(0)
                // (1.0.3): Soar class boundaries; 768/1024 were
                // floored to 752/992 anyway and 1232 equals Auto.
                ForEach(AppSettings.manualMaxAnswerOptions, id: \.self) { value in
                    answerSizeChip(value)
                }
            }
            NoteText(loc.tr("settings.dns_answer_size_explanation"))
            // (1.0.3): not live — Soar fixes each path's answer
            // class when the path is created.
            NoteText(loc.tr("settings.dns_answer_size_next_connect"))
        }
    }
}

private func mtuChip(_ value: Int) -> some View {
    let selected = settings.mtu == value
    return Button(action: { settings.mtu = value }) {
        Text(value == 0 ? loc.tr("settings.mtu_auto") : "\(value)")
            .font(.system(size: 15, weight: selected ? .semibold : .regular))
            .lineLimit(1)
            .padding(.horizontal, 16)
            // (1.0.2): fixed 34pt box, text centred vertically.
            .frame(height: 34, alignment: .center)
            .background(selected ? Color("BrandBlue") : Color("SurfaceVariant"))
            .foregroundColor(selected ? .white : Color("BrandBlue"))
            // Free-standing chip: fully round, keeps its shape.
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .stroke(Color("BrandBlue"), lineWidth: selected ? 0 : 1)
            )
    }
    .buttonStyle(PlainButtonStyle())
}

// DNS answer-size cap (1.0.28; 1.0.3 class boundaries): 0 = Auto,
// otherwise 512/752/992. Same chip style as MTU, with tighter
// spacing/padding so all options fit one row. Auto-saves on tap (no
// Save button), applies on next connect (logged as such).
private func answerSizeChip(_ value: Int) -> some View {
    let selected = settings.maxAnswer == value
    let label = value == 0 ? loc.tr("settings.dns_answer_size_auto") : "\(value)"
    return Button(action: {
        settings.maxAnswer = value
        SharedLog.append("DNS answer size set to \(value == 0 ? "Auto" : "\(value)"); applies on next connect")
    }) {
        Text(label)
            .font(.system(size: 15, weight: selected ? .semibold : .regular))
            .lineLimit(1)
            .padding(.horizontal, 12)
            // (1.0.2): fixed 34pt box, text centred vertically.
            .frame(height: 34, alignment: .center)
            .background(selected ? Color("BrandBlue") : Color("SurfaceVariant"))
            .foregroundColor(selected ? .white : Color("BrandBlue"))
            // Free-standing chip: fully round, keeps its shape.
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .stroke(Color("BrandBlue"), lineWidth: selected ? 0 : 1)
            )
    }
    .buttonStyle(PlainButtonStyle())
}

private var resolverSection: some View {
    Section(header: Text(loc.tr("settings.resolvers")),
            footer: Text(loc.tr("settings.resolvers_footer"))) {
        Picker(loc.tr("settings.resolver_mode"), selection: $settings.resolverMode) {
            Text(loc.tr("settings.resolver_builtin")).tag(ResolverMode.builtIn)
            Text(loc.tr("settings.resolver_custom")).tag(ResolverMode.custom)
            // (1.0.2): Import, right after Custom.
            Text(loc.tr("settings.resolver_import")).tag(ResolverMode.imported)
        }
        .pickerStyle(SegmentedPickerStyle())
        // (1.0.33, fix 3): a mode switch clears the displayed
        // scan results — they belong to the other mode.
        .onChange(of: settings.resolverMode) { _ in
            vpn.clearScanResults()
        }
        if settings.resolverMode == .builtIn {
            NoteText(loc.tr("settings.resolver_builtin_note"))
        } else if settings.resolverMode == .imported {
            // (1.0.2): file card / Replace / View list / Clear and
            // the fix report. The scan row below is shared and
            // unchanged (same 3-stage stepper as Custom).
            ResolverImportView()
        } else {
            TextField(loc.tr("settings.custom_resolvers_label"),
                      text: customBinding)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                .keyboardType(.URL)
            NoteText(loc.tr("settings.custom_resolvers_format_note"))
            // (1.0.33): the count is the EXPANDED number of
            // addresses the scan will probe — a /24 shows 256,
            // not 1 — so a range never reads as a single IP.
            Text(loc.tr("settings.custom_resolvers_count", ResolverNormalization.expandedResolverCount(customText)))
                .font(.footnote)
                .foregroundColor(.secondary)
            if !droppedCustom.isEmpty {
                Text(loc.tr("settings.custom_resolvers_ignored",
                            droppedCustom.joined(separator: ", ")))
                    .font(.footnote)
                    .foregroundColor(Color("BrandOrange"))
                    .fixedSize(horizontal: false, vertical: true)
            }
            // (1.0.33): warn about ranges too large to scan
            // (>4096 addresses, prefix < /20) — the core skips
            // them, so say so instead of letting the scan run
            // on nothing.
            if !oversizeCustom.isEmpty {
                Text(loc.tr("settings.custom_resolvers_range_too_large",
                            oversizeCustom.joined(separator: ", ")))
                    .font(.footnote)
                    .foregroundColor(Color("BrandOrange"))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        testResolversRow
        rememberedSection
    }
}

/// The custom field's binding: every keystroke updates the local
/// text and (re)starts the 3s save debounce; the field itself is
/// never written back to.
private var customBinding: Binding<String> {
    Binding(get: { customText }, set: { newValue in
        customText = newValue
        scheduleCustomSave()
    })
}

/// The field's resolvers, normalized for display (dot-as-separator
/// and :53 already handled by ResolverNormalization).
private var normalizedCustom: [String] {
    return ResolverNormalization.normalizeResolvers(customText)
}

/// The field's entries normalization drops, shown as a warning
/// instead of being dropped silently.
private var droppedCustom: [String] {
    return ResolverNormalization.droppedResolverTokens(customText)
}

/// The field's ranges too large to scan (>4096 addresses),
/// shown as a warning instead of being skipped silently.
private var oversizeCustom: [String] {
    return ResolverNormalization.oversizeResolverRanges(customText)
}

/// (1.0.3): one "Scan over" chip — same look as the MTU chips
/// (34pt capsule, text centred, blue only). Locked while a scan
/// runs (the running scan keeps the mode it started with).
private func scanOverChip(udpTcp: Bool) -> some View {
    let selected = settings.scanUdpTcp == udpTcp
    return Button(action: { if !scanInProgress { settings.scanUdpTcp = udpTcp } }) {
        Text(udpTcp ? "UDP + TCP" : "UDP")
            .font(.system(size: 15, weight: selected ? .semibold : .regular))
            .lineLimit(1)
            .padding(.horizontal, 16)
            .frame(height: 34, alignment: .center)
            .background(selected ? Color("BrandBlue") : Color("SurfaceVariant"))
            .foregroundColor(selected ? .white : Color("BrandBlue"))
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .stroke(Color("BrandBlue"), lineWidth: selected ? 0 : 1)
            )
    }
    .buttonStyle(PlainButtonStyle())
}

/// (1.0.3): the list the core will expand — Custom / Import only.
private var checkListCsv: String {
    return settings.resolverMode == .builtIn ? "" : settings.effectiveResolvers
}

/// (1.0.3): recompute the core's list check off the main thread.
private func refreshListCheck() {
    let csv = checkListCsv
    guard !csv.isEmpty else {
        listCheck = nil
        return
    }
    DispatchQueue.global(qos: .userInitiated).async {
        let result = VPNManager.checkResolverList(csv)
        DispatchQueue.main.async {
            // Drop a stale answer if the list changed meanwhile.
            if csv == checkListCsv { listCheck = result }
        }
    }
}

private var testResolversRow: some View {
    VStack(alignment: .leading, spacing: 8) {
        // (1.0.3): scan transport, above the Scan button. UDP is the
        // classic scan; UDP + TCP also keeps resolvers that answer
        // only over TCP/53 (the scan takes longer). Auto-saves.
        Text(loc.tr("settings.scan_over"))
        HStack(spacing: 10) {
            scanOverChip(udpTcp: false)
            scanOverChip(udpTcp: true)
        }
        NoteText(loc.tr("settings.scan_over_note"))
        // (1.0.32): Stop / Pause / Resume sit next to Test while a
        // scan runs. Test is disabled while testing; Stop cancels
        // the scan (partial results kept); Pause holds it and
        // Resume continues it — the button flips between Pause and
        // Resume from vpn.scanPaused, so only one is ever shown.
        HStack(spacing: 8) {
            Button(action: testResolvers) {
                HStack {
                    Text(loc.tr("settings.test_resolvers"))
                    Spacer()
                }
            }
            .disabled(isTestingResolvers || vpn.testScanRunning)
            if scanInProgress {
                Button(action: { vpn.stopScan() }) {
                    Text(loc.tr("settings.stop_scan"))
                }
                if vpn.scanPaused {
                    Button(action: { vpn.resumeScan() }) {
                        Text(loc.tr("settings.resume_scan"))
                    }
                } else {
                    Button(action: { vpn.pauseScan() }) {
                        Text(loc.tr("settings.pause_scan"))
                    }
                }
            }
        }
        // B1 stepper: appears when a scan starts, stays visible
        // during the scan, and shows all 3 stages done after it
        // completes. The old spinner + "Testing…" next to the
        // button and the verified/responding lines are replaced by
        // the stepper's live stage stats.
        if scanInProgress || scanFinished {
            scanStepper
        }
        // (1.0.3): pre-scan check with the core's own expansion
        // (CoreCheckResolverList, no tunnel): how many resolvers the
        // Custom / Import list gives and what is left out (ranges
        // wider than /20, 5000 cap). The core's warning lines are
        // English and name the exact ranges: shown LTR as details.
        if settings.resolverMode != .builtIn, let check = listCheck {
            NoteText(loc.tr("settings.resolver_list_check_count", check.count))
            if check.dropped > 0 {
                Text(loc.tr("settings.resolver_list_check_dropped", check.dropped))
                    .font(.footnote)
                    .foregroundColor(Color("BrandOrange"))
                    .fixedSize(horizontal: false, vertical: true)
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(check.warnings.prefix(5).enumerated()), id: \.offset) { _, w in
                        Text(w)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
            }
        }
        // (1.0.3): the core reports resolver-list entries it could
        // not use — CIDR ranges larger than /20 are skipped and the
        // expanded list is capped at 5000 (Stats()
        // "resolverListDropped"). Custom / Import only, and only when
        // the pre-scan check above is unavailable (it says the same).
        if settings.resolverMode != .builtIn && vpn.scanListDropped > 0 && listCheck == nil {
            Text(loc.tr("settings.resolver_list_trimmed_warning", vpn.scanListDropped))
                .font(.footnote)
                .foregroundColor(Color("BrandOrange"))
        }
    }
    .onAppear(perform: refreshListCheck)
    .onChange(of: checkListCsv) { _ in refreshListCheck() }
}

// MARK: - B1 scan stepper

/// Scan-stage state, derived from the observed scan state only:
///
/// - stage 1 is ACTIVE while a scan runs, DONE after it completes;
/// - stage 2 is DONE once the verified count is known after the scan;
/// - stage 3 is DONE once the remembered list is updated after the scan;
/// - all PENDING before any scan.
///
/// Stages 2 and 3 complete quickly after stage 1 — that is the real
/// behavior; stage 1 is the long live part.
private enum ScanStageStatus {
    case pending, active, done
}

private var scanInProgress: Bool {
    isTestingResolvers || vpn.testScanRunning
}

/// (1.0.36): per-stage states follow REAL progress, not just
/// "scan running / scan done" — the 1.0.35 field report was that
/// the scan looked stuck on stage 1 (spinner) and then suddenly
/// jumped to all-done with the verified count. Stage 1 is DONE
/// as soon as tested reaches total (stage 1 finished), even
/// while the scan still runs (stage 2 vetting). Stage 2 is
/// ACTIVE while stage 1 is done and the scan still runs — its
/// count is live now (the Go core publishes scanVerified on
/// every vet poll).
private var stage1Done: Bool {
    scanFinished || (vpn.scanTotalCount > 0 && vpn.scanTestedCount >= vpn.scanTotalCount)
}

private var stage1Status: ScanStageStatus {
    if stage1Done { return .done }
    if scanInProgress { return .active }
    return .pending
}

private var stage2Status: ScanStageStatus {
    if scanFinished { return .done }
    if stage1Done && scanInProgress { return .active }
    return .pending
}

private var stage3Status: ScanStageStatus {
    scanFinished ? .done : .pending
}

/// Stage 1 live stats: probes completed / total, plus how many
/// answered so far (shown once any have responded).
private var stage1Details: [String] {
    var details = [loc.tr("settings.scan_tested", vpn.scanTestedCount, vpn.scanTotalCount)]
    if vpn.scanRespondingCount > 0 {
        details.append(loc.tr("settings.resolvers_responding", vpn.scanRespondingCount))
    }
    return details
}

/// Stage 2 live stats: the Soar-verified count. (1.0.36): shown
/// live while vetting runs (the Go core publishes the verified
/// list on every vet poll), not only once the scan has finished.
private var stage2Details: [String] {
    guard stage2Status != .pending else { return [] }
    return [loc.tr("settings.resolvers_verified", vpn.scanVerifiedCount)]
}

/// Stage 3 live stats: how many healthy resolvers were saved to
/// the remembered list after the scan.
private var stage3Details: [String] {
    guard scanFinished else { return [] }
    return [loc.tr("settings.scan_saved", remembered.count)]
}

/// The vertical stepper, minimal style (approved 2026-11-20):
/// a 1pt line, tiny markers (9pt hollow blue dot pending, 13pt
/// orange check done, 12pt orange spinner active), no card
/// behind the timeline, compact text and airy spacing. Blue +
/// orange only — a completed stage gets an ORANGE checkmark,
/// never green; the current stage is highlighted with a spinner.
private var scanStepper: some View {
    VStack(alignment: .leading, spacing: 0) {
        scanStageRow(titleKey: "settings.scan_stage1_title",
                     status: stage1Status, details: stage1Details)
        scanStageConnector(done: stage1Status == .done)
        scanStageRow(titleKey: "settings.scan_stage2_title",
                     status: stage2Status, details: stage2Details)
        scanStageConnector(done: stage2Status == .done)
        scanStageRow(titleKey: "settings.scan_stage3_title",
                     status: stage3Status, details: stage3Details)
    }
    .padding(.vertical, 4)
}

private func scanStageRow(titleKey: String,
                          status: ScanStageStatus, details: [String]) -> some View {
    HStack(alignment: .top, spacing: 10) {
        scanStageIcon(status: status)
        VStack(alignment: .leading, spacing: 2) {
            Text(loc.tr(titleKey))
                .font(.system(size: 13, weight: status == .pending ? .regular : .medium))
                .foregroundColor(status == .pending ? .secondary : .primary)
            ForEach(details, id: \.self) { detail in
                Text(detail)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        Spacer()
    }
}

private func scanStageIcon(status: ScanStageStatus) -> some View {
    // Tiny markers centred in a 13pt column (the largest marker,
    // the check, is 13pt). No filled circle behind the check and
    // no number in the pending dot — just the dot itself.
    ZStack {
        switch status {
        case .done:
            // Orange checkmark, never green.
            Image(systemName: "checkmark")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(Color("BrandOrange"))
        case .active:
            // Small orange spinner (medium style is 20pt; 0.6x = 12pt).
            ActivityIndicator(color: UIColor(named: "BrandOrange"))
                .scaleEffect(0.6)
        case .pending:
            Circle()
                .strokeBorder(Color("BrandBlue").opacity(0.5), lineWidth: 1)
                .frame(width: 9, height: 9)
        }
    }
    .frame(width: 13, height: 13)
}

private func scanStageConnector(done: Bool) -> some View {
    Rectangle()
        .fill(Color("BrandBlue").opacity(done ? 0.5 : 0.25))
        .frame(width: 1, height: 24)
        // Centered under the 13pt marker column (13 / 2 - 1 / 2 = 6).
        .padding(.leading, 6)
        .padding(.vertical, 2)
}

private var rememberedSection: some View {
    Group {
        if settings.resolverMode == .builtIn && !remembered.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(loc.tr("settings.remembered_resolvers_title"))
                    .font(.headline)
                Text(loc.tr("settings.resolvers_spread_note", remembered.count))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                // (1.0.53, item 40): the whole remembered pool
                // (~600) scrolls here, so the rows must stay lazy
                // — a plain VStack would compose every row on each
                // store update. 5 rows x ~20pt + 4 x 8pt spacing
                // = 132pt visible; the rest scrolls.
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(remembered, id: \.addr) { entry in
                            rememberedRow(entry)
                        }
                    }
                }
                .frame(height: 132)
                Button(action: copyAllRemembered) {
                    Text(loc.tr("settings.copy_all_resolvers", remembered.count))
                        .foregroundColor(Color("BrandBlue"))
                }
            }
        }
    }
}

private func rememberedRow(_ entry: RememberedResolver) -> some View {
    Button(action: {
        UIPasteboard.general.string = entry.addr
    }) {
        HStack {
            Text(entry.addr)
                .font(.subheadline)
                .foregroundColor(.primary)
            // (1.0.3): transport tag between ip:port and the ping.
            transportTag(entry.transport)
            Spacer()
            Text("\(entry.rttMs) ms")
                .font(.caption)
                .foregroundColor(.secondary)
            Image(systemName: "doc.on.doc")
                .font(.caption)
                .foregroundColor(Color("BrandBlue"))
        }
        // The row is numbers + an IP (mathematical, left-to-right): forced LTR
        // so the Persian RTL locale neither mirrors the row nor reverses the IP.
        .environment(\.layoutDirection, .leftToRight)
    }
    .buttonStyle(PlainButtonStyle())
}

/// (1.0.3): small transport tag on a remembered row — "UDP" on a
/// blue container, "TCP" on an orange container, "UDP·TCP" blue
/// container with orange text (blue and orange only). Unknown
/// (entries saved before 1.0.3) shows nothing. Pill radius 8,
/// text centred.
@ViewBuilder
private func transportTag(_ transport: String?) -> some View {
    switch (transport ?? "").lowercased().replacingOccurrences(of: " ", with: "") {
    case "udp":
        tagPill("UDP", fill: Color("BrandBlue").opacity(0.16), text: Color("BrandBlue"))
    case "tcp":
        tagPill("TCP", fill: Color("BrandOrange").opacity(0.20), text: Color("BrandOrange"))
    case "udp+tcp", "tcp+udp":
        tagPill("UDP·TCP", fill: Color("BrandBlue").opacity(0.16), text: Color("BrandOrange"))
    default:
        EmptyView()
    }
}

private func tagPill(_ label: String, fill: Color, text: Color) -> some View {
    Text(label)
        .font(.system(size: 11, weight: .semibold))
        .lineLimit(1)
        .multilineTextAlignment(.center)
        .foregroundColor(text)
        .padding(.horizontal, 6)
        .padding(.vertical, 1)
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(fill))
}

private var proxySection: some View {
    Section(header: Text(loc.tr("settings.proxy")),
            footer: Text(loc.tr("settings.proxy_footer"))) {
        HStack {
            Text(loc.tr("settings.proxy_port"))
            Spacer()
            TextField("1080", text: $socksPortText)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 100)
        }
        TextField(loc.tr("settings.proxy_username"), text: $settings.socksUsername)
            .autocapitalization(.none)
            .disableAutocorrection(true)
        SecureField(loc.tr("settings.proxy_password"), text: $socksPassword)
            .autocapitalization(.none)
            .disableAutocorrection(true)
    }
}

private var bandwidthSection: some View {
    Section(header: Text(loc.tr("settings.bandwidth")),
            footer: Text(loc.tr("settings.bandwidth_footer"))) {
        Stepper(value: $settings.bandwidthUpKBps, in: 0...102400, step: 128) {
            Text(loc.tr("settings.bandwidth_up", settings.bandwidthUpKBps))
        }
        Stepper(value: $settings.bandwidthDownKBps, in: 0...102400, step: 128) {
            Text(loc.tr("settings.bandwidth_down", settings.bandwidthDownKBps))
        }
    }
}

private var routingSection: some View {
    Section(header: Text(loc.tr("settings.routing")),
            footer: Text(loc.tr("settings.routing_footer"))) {
        Toggle(loc.tr("settings.geo_bypass"), isOn: $settings.geoBypass)
        TextField(loc.tr("settings.bypass_placeholder"),
                  text: $settings.bypassDomains)
            .autocapitalization(.none)
            .disableAutocorrection(true)
    }
}

private var systemSection: some View {
    Section(header: Text(loc.tr("settings.system")),
            footer: Text(loc.tr("settings.system_footer"))) {
        Toggle(loc.tr("settings.on_demand"), isOn: $settings.onDemandEnabled)
        Toggle(loc.tr("settings.auto_select"), isOn: $settings.autoSelectFastest)
        Picker(loc.tr("settings.appearance"), selection: $settings.appearance) {
            Text(loc.tr("settings.appearance_system")).tag(AppearanceChoice.system)
            Text(loc.tr("settings.appearance_light")).tag(AppearanceChoice.light)
            Text(loc.tr("settings.appearance_dark")).tag(AppearanceChoice.dark)
        }
        Picker(loc.tr("settings.language"), selection: $settings.language) {
            Text(loc.tr("settings.language_system")).tag(LanguageChoice.system)
            Text("English").tag(LanguageChoice.en)
            Text("فارسی").tag(LanguageChoice.fa)
        }
        .onReceive(settings.$language) { _ in
            // Apply the language immediately; strings reload through
            // the LocalizationManager.
            LocalizationManager.shared.language = settings.language
        }
        .onReceive(settings.$appearance) { _ in
            ThemeManager.shared.appearance = settings.appearance
        }
        // Check for update: one tap checks GitHub Releases for a
        // newer build. While a check runs the row is disabled and
        // shows a spinner instead of the chevron.
        Button(action: startUpdateCheck) {
            HStack {
                Text(loc.tr("update.check"))
                Spacer()
                if updateChecking {
                    ActivityIndicator()
                } else {
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .disabled(updateChecking)
    }
}

private var supportSection: some View {
    Section {
        NavigationLink(destination: HistoryView().environmentObject(loc)) {
            Text(loc.tr("settings.history"))
        }
        NavigationLink(destination: LogsView().environmentObject(loc)) {
            Text(loc.tr("settings.logs"))
        }
        // (1.0.3): live per-path diagnostics (state, transport,
        // RTT, loss, window, answer limit, name length) + Copy.
        NavigationLink(destination: PathDiagnosticsView().environmentObject(loc)) {
            Text(loc.tr("paths.title"))
        }
        NavigationLink(destination: AboutView().environmentObject(loc)) {
            Text(loc.tr("settings.about"))
        }
    }
}

// MARK: - Actions

/// 3s debounce: every keystroke cancels the pending save and
/// restarts the clock; the save writes the raw text and the
/// snapshot normalizes it, without ever touching the field.
private func scheduleCustomSave() {
    customSaveWorkItem?.cancel()
    let work = DispatchWorkItem { [customText] in
        settings.customResolvers = customText
    }
    customSaveWorkItem = work
    DispatchQueue.main.asyncAfter(deadline: .now() + 3.0, execute: work)
}

private func testResolvers() {
    guard !isTestingResolvers && !vpn.testScanRunning else { return }
    isTestingResolvers = true
    scanFinished = false
    vpn.testResolvers { _ in
        isTestingResolvers = false
        scanFinished = true
        refreshRemembered()
    }
}

/// One check per tap, no retries: the result is a notice alert,
/// or the update dialog when a newer release exists.
private func startUpdateCheck() {
    guard !updateChecking else { return }
    updateChecking = true
    AppUpdateChecker.checkForUpdate(currentVersion: appVersion()) { result in
        updateChecking = false
        switch result {
        case .upToDate:
            updateNoticeTitle = loc.tr("update.check")
            updateNoticeMessage = loc.tr("update.latest")
            showUpdateNotice = true
        case .failed(let kind):
            updateNoticeTitle = loc.tr("common.error")
            switch kind {
            case .noPage:
                updateNoticeMessage = loc.tr("update.no_page")
            default:
                updateNoticeMessage = loc.tr("update.check_failed")
            }
            showUpdateNotice = true
        case .available(let release):
            updateRelease = release
            showUpdateDialog = true
        }
    }
}

/// The installed version name, from the bundle — the same value
/// the About screen shows (without the build number).
private func appVersion() -> String {
    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1"
}

/// The update dialog's message: the new version, then the release
/// notes. Notes are capped at 500 characters — the full notes are
/// on the release page the dialog opens.
private var updateDialogMessage: String {
    guard let release = updateRelease else { return "" }
    var message = loc.tr("update.available_body", release.version)
    if !release.notes.isEmpty {
        let shown = release.notes.count > 500
            ? String(release.notes.prefix(500)) + "…"
            : release.notes
        message += "\n\n" + shown
    }
    return message
}

private func refreshRemembered() {
    remembered = ResolverHealthStore.shared.load()
}

private func copyAllRemembered() {
    UIPasteboard.general.string = remembered.map { $0.addr }.joined(separator: ", ")
}

// MARK: - Save

private func save() {
    customSaveWorkItem?.cancel()
    settings.customResolvers = customText
    if let port = Int(socksPortText), port > 0, port <= 65535 {
        settings.socksPort = port
    }
    KeychainHelper.shared.setSocksPassword(socksPassword)
    settings.save()
    // Writes the on-demand choice into the saved VPN configuration
    // without reconnecting; other changes apply on next connect.
    vpn.applySettings()
}

}


// MARK: - Path diagnostics (1.0.3)

/// Path diagnostics: the core's Stats() "pathStats" array, as the
/// extension writes it into the shared stats snapshot, shown per
/// path — state, transport, RTT, loss, window, answer limit and
/// query-name length — with a Copy button for bug reports.
/// Re-read every 3 s while the page is open. Nested radius: 24
/// outer card / 16 padding / 8 inner panels. Numbers and IPs are
/// forced LTR so the Persian layout never mirrors them.
struct PathDiagnosticsView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @State private var paths: [SoarPathStat] = []
    @State private var copied = false
    private let timer = Timer.publish(every: 3, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                HStack(alignment: .center, spacing: 12) {
                    Text(paths.isEmpty
                         ? loc.tr("paths.empty")
                         : loc.tr("paths.summary", paths.count, paths.filter { isHealthy($0) }.count))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Spacer()
                    Button(action: copyReport) {
                        HStack(spacing: 6) {
                            Image(systemName: "doc.on.doc")
                            Text(copied ? loc.tr("common.copied") : loc.tr("common.copy"))
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Color("BrandBlue"))
                    }
                }
                ForEach(Array(paths.enumerated()), id: \.offset) { _, path in
                    row(path)
                }
            }
            .padding(UnbarRadius.cardPadding)
            .background(Color("Surface"))
            .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.outer, style: .continuous))
            .padding()
        }
        .background(Color("AppBackground"))
        .navigationBarTitle(loc.tr("paths.title"), displayMode: .inline)
        .onAppear(perform: reload)
        .onReceive(timer) { _ in reload() }
    }

    private func reload() {
        guard let data = AppGroup.defaults?.data(forKey: SharedKey.stats),
              let snapshot = StatsSnapshot.decode(data),
              snapshot.state != "stopped" else {
            paths = []
            return
        }
        paths = snapshot.pathStats
    }

    private func isHealthy(_ p: SoarPathStat) -> Bool {
        // Older extension builds have no "healthy" key: approximate.
        return p.healthy ?? (p.verified && !p.suspended && p.loss < 0.5)
    }

    private func stateKey(_ p: SoarPathStat) -> String {
        if p.suspended { return "paths.state.suspended" }
        if !p.verified { return "paths.state.unverified" }
        return isHealthy(p) ? "paths.state.healthy" : "paths.state.degraded"
    }

    private func stateColor(_ p: SoarPathStat) -> Color {
        if p.suspended || !p.verified { return Color("BrandOrange") }
        return isHealthy(p) ? Color("BrandBlue") : .secondary
    }

    private func row(_ p: SoarPathStat) -> some View {
        VStack(spacing: 6) {
            HStack {
                Text(p.resolver.isEmpty ? "—" : p.resolver)
                    .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                    .environment(\.layoutDirection, .leftToRight)
                Spacer()
                Text(loc.tr(stateKey(p)))
                    .font(.caption.weight(.bold))
                    .foregroundColor(stateColor(p))
            }
            HStack(spacing: 0) {
                metric(loc.tr("paths.transport"), p.transport.isEmpty ? "—" : p.transport)
                metric(loc.tr("paths.rtt"), "\(p.rttMs) ms")
                metric(loc.tr("paths.loss"), String(format: "%.0f%%", locale: Locale(identifier: "en_US_POSIX"), p.loss * 100))
            }
            HStack(spacing: 0) {
                metric(loc.tr("paths.window"), String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), p.window))
                metric(loc.tr("paths.answer_limit"), "\(p.answerLimit)")
                metric(loc.tr("paths.name_len"), "\(p.nameLen)")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color("SurfaceVariant"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.inner, style: .continuous))
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundColor(.secondary)
            Text(value)
                .font(.system(.caption, design: .monospaced))
                .environment(\.layoutDirection, .leftToRight)
        }
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
    }

    private func copyReport() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        var lines = ["Unbar \(version) path diagnostics: \(paths.count) paths, \(paths.filter { isHealthy($0) }.count) healthy"]
        for p in paths {
            let state: String
            if p.suspended { state = "suspended" }
            else if !p.verified { state = "unverified" }
            else { state = isHealthy(p) ? "healthy" : "degraded" }
            lines.append("\(p.resolver) state=\(state) transport=\(p.transport) rtt=\(p.rttMs)ms "
                + String(format: "loss=%.2f window=%.1f", locale: Locale(identifier: "en_US_POSIX"), p.loss, p.window)
                + " answer_limit=\(p.answerLimit) name_len=\(p.nameLen) inflight=\(p.inflight)")
        }
        UIPasteboard.general.string = lines.joined(separator: "\n")
        copied = true
    }
}
