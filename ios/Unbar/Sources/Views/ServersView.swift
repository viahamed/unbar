// ServersView.swift — list, add, edit, delete and test servers.
//
// The active server is the one the VPN connects to. "Auto-select
// fastest" tests every server and activates the quickest one that
// works. Config import (pasted link) and export (QR code) live here.
//
// Add (1.0.19, Model C): the + action opens ONE "Add" sheet with
// three cards — Import config, Add server, Subscription — and each
// form has a "< Back" link to the cards. The subscription section
// below renders ONLY when at least one subscription exists.

import SwiftUI

private enum AddSheetPage {
    case cards
    case importConfig
    case manual
    case subscription
}

public struct ServersView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var vpn: VPNManager
    @EnvironmentObject private var loc: LocalizationManager
    @ObservedObject private var subscriptions = SubscriptionStore.shared

    @State private var editingServer: Server?
    @State private var showingAdd = false
    @State private var addPage: AddSheetPage = .cards
    @State private var importText = ""
    @State private var importError = false
    @State private var showingScanner = false
    @State private var subURL = ""
    @State private var subAdding = false
    @State private var subStatusText: String?
    // Id of the subscription being edited in the Add sheet, or nil
    // when the sheet is adding a new one. Set by the pencil on a
    // subscription row; cleared when the sheet opens for adding.
    @State private var editingSubscriptionId: UUID?
    @State private var exportingServer: Server?
    @State private var testingId: UUID?
    @State private var autoSelecting = false
    @State private var autoSelectMessage: String?

    public init() {}

    public var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 14) {
                    TutorialAccordion(sections: serversTutorialSections)
                    if settings.servers.isEmpty {
                        Text(loc.tr("servers.empty"))
                            .foregroundColor(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        ForEach(settings.servers) { server in
                            serverRow(server)
                        }
                    }
                    subscriptionSection
                    Button(action: openAddSheet) {
                        HStack {
                            Image(systemName: "plus")
                            Text(loc.tr("servers.add"))
                            Spacer()
                        }
                        .padding(14)
                        .background(Color(.systemBackground))
                        .cornerRadius(14)
                        .foregroundColor(Color("BrandBlue"))
                    }
                    .buttonStyle(PlainButtonStyle())
                    // Auto-select (V1, button 2): solid blue pill,
                    // white wand + white text. Same action, enabled
                    // state and spinner state as before — the spinner
                    // is white here, or it would vanish on the blue.
                    Button(action: autoSelect) {
                        HStack(spacing: 8) {
                            if autoSelecting {
                                ActivityIndicator(color: .white)
                            } else {
                                Image(systemName: "wand.and.stars")
                            }
                            Text(loc.tr("servers.auto_select"))
                        }
                        .font(.system(size: 16, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .foregroundColor(.white)
                        .background(Color("BrandBlue"))
                        .cornerRadius(28)
                    }
                    .buttonStyle(PlainButtonStyle())
                    .disabled(settings.servers.isEmpty || autoSelecting)
                    if let message = autoSelectMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .navigationBarTitle(loc.tr("servers.title"), displayMode: .large)
            .sheet(isPresented: $showingAdd) {
                addSheet
                    .environmentObject(settings)
                    .environmentObject(vpn)
                    .environmentObject(loc)
            }
            .sheet(item: $editingServer) { server in
                ServerEditView(server: server)
                    .environmentObject(settings)
                    .environmentObject(vpn)
                    .environmentObject(loc)
            }
            .sheet(item: $exportingServer) { server in
                ExportView(server: server)
                    .environmentObject(settings)
                    .environmentObject(loc)
            }
            .alert(isPresented: $importError) {
                Alert(title: Text(loc.tr("common.error")),
                      message: Text(loc.tr("servers.import_invalid")),
                      dismissButton: .default(Text(loc.tr("common.ok"))))
            }
        }
    }

    // MARK: - Tutorial (1.0.30)

    /// Servers Tutorial (top of the Servers screen): the 5
    /// server topics, ungrouped. Text via loc.tr.
    private var serversTutorialSections: [TutorialSection] {
        [TutorialSection(topics: [
            TutorialTopic(titleKey: "tutorial.servers.add.title",
                          bodyKey: "tutorial.servers.add.body"),
            TutorialTopic(titleKey: "tutorial.servers.import.title",
                          bodyKey: "tutorial.servers.import.body"),
            TutorialTopic(titleKey: "tutorial.servers.subscription.title",
                          bodyKey: "tutorial.servers.subscription.body"),
            TutorialTopic(titleKey: "tutorial.servers.latency.title",
                          bodyKey: "tutorial.servers.latency.body"),
            TutorialTopic(titleKey: "tutorial.servers.edit.title",
                          bodyKey: "tutorial.servers.edit.body"),
        ])]
    }

    // MARK: - Rows

    // V1 compact server card: status dot + name + badge + latency
    // pill on the top row, mono zone below, a divider, then the four
    // action icons spread across one row. Selected = filled blue dot
    // + blue border; unselected = hollow grey dot + grey border.
    private func serverRow(_ server: Server) -> some View {
        let active = settings.activeServerId == server.id
            || (settings.activeServerId == nil && settings.servers.first?.id == server.id)
        return VStack(alignment: .leading, spacing: 0) {
            Button(action: { settings.selectServer(server) }) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Image(systemName: active ? "circle.fill" : "circle")
                            .font(.system(size: 10))
                            .foregroundColor(active ? Color("BrandBlue") : Color(.systemGray3))
                        Text(server.name)
                            .font(.system(size: 16, weight: .bold))
                            .lineLimit(1)
                        if server.subscriptionId != nil {
                            Text(loc.tr("subscription.badge"))
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .foregroundColor(Color("BrandOrange"))
                                .background(Color("BrandOrange").opacity(0.14))
                                .cornerRadius(6)
                        }
                        Spacer()
                        latencyPill(server)
                    }
                    Text(server.zone)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(.secondary)
                        .padding(.leading, 16)
                    if server.lastLatencyMs == nil,
                       let error = server.lastTestError, !error.isEmpty {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(Color("BrandOrange"))
                            .lineLimit(2)
                    }
                }
            }
            .buttonStyle(PlainButtonStyle())
            Divider()
                .padding(.top, 10)
            HStack(spacing: 0) {
                Button(action: { test(server) }) {
                    if testingId == server.id {
                        ActivityIndicator()
                    } else {
                        Image(systemName: "gauge")
                    }
                }
                .disabled(testingId != nil)
                .frame(maxWidth: .infinity)
                Button(action: { exportingServer = server }) {
                    Image(systemName: "qrcode")
                }
                .frame(maxWidth: .infinity)
                Button(action: { editingServer = server }) {
                    Image(systemName: "pencil")
                }
                .frame(maxWidth: .infinity)
                Button(action: { deleteServer(server) }) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                }
                .frame(maxWidth: .infinity)
            }
            .foregroundColor(Color("BrandBlue"))
            .buttonStyle(PlainButtonStyle())
            .padding(.top, 6)
        }
        .padding(14)
        .background(Color(.systemBackground))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(active ? Color("BrandBlue") : Color(.systemGray4), lineWidth: active ? 2 : 1)
        )
    }

    /// The latency at the right of the card's top row: a light-blue
    /// pill while a measurement exists, a spinner while testing,
    /// nothing when never tested.
    @ViewBuilder
    private func latencyPill(_ server: Server) -> some View {
        if testingId == server.id {
            ActivityIndicator()
        } else if let latency = server.lastLatencyMs {
            Text("\u{200E}\(latency) ms")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(Color("BrandBlue"))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Color("BrandBlue").opacity(0.12))
                .cornerRadius(12)
        }
    }

    /// Delete one server (trash icon in the card action row): the
    /// same path as the old swipe-to-delete, via the row's index.
    private func deleteServer(_ server: Server) {
        if let index = settings.servers.firstIndex(where: { $0.id == server.id }) {
            settings.deleteServer(at: IndexSet(integer: index))
        }
    }

    // MARK: - Subscription section (only when >= 1 subscription)

    private var subscriptionSection: some View {
        Group {
            if !subscriptions.subscriptions.isEmpty {
                subscriptionCard
                ForEach(subscriptions.subscriptions) { sub in
                    subscriptionRow(sub)
                        .padding(14)
                        .background(Color(.systemBackground))
                        .cornerRadius(14)
                }
            }
        }
    }

    /// The V1 subscription banner: orange-bordered card with a book
    /// icon + "Subscription" + the status on the right, the
    /// auto-update toggle row, and the HTTPS note.
    private var subscriptionCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "book")
                    .foregroundColor(Color("BrandOrange"))
                Text(loc.tr("subscription.title"))
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                subscriptionStatusView
            }
            if let errorMessage = subscriptionErrorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundColor(.red)
            }
            TCPModeSwitchRow(label: loc.tr("subscription.auto_update"),
                             isOn: $settings.autoUpdateSubscriptions)
            NoteText(loc.tr("subscription.https_note"))
        }
        .padding(14)
        .background(Color(.systemBackground))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color("BrandOrange"), lineWidth: 1.5)
        )
    }

    /// The effective subscription status: the store's status, or the
    /// latest subscription's state when the store has none yet (same
    /// fallback as Android's effectiveSubscriptionStatus).
    private var effectiveSubscriptionStatus: SubscriptionStatus {
        if let status = subscriptions.status { return status }
        if let latest = subscriptions.subscriptions
            .filter({ $0.lastUpdated != nil })
            .max(by: { ($0.lastUpdated ?? .distantPast) < ($1.lastUpdated ?? .distantPast) }) {
            return .updated(serverCount: latest.serverCount, skippedCount: 0)
        }
        return .neverUpdated
    }

    /// The error message for the current status, or nil when the
    /// status is not an error. Kept out of the view builder so the
    /// banner can use a plain `if let`.
    private var subscriptionErrorMessage: String? {
        if case .error(let kind, let detail) = effectiveSubscriptionStatus {
            return subscriptionErrorText(kind: kind, detail: detail)
        }
        return nil
    }

    /// The status at the right of the banner header: Updated is an
    /// orange pill with the server count; Updating a spinner + text;
    /// NeverUpdated muted text. Error is rendered below the header.
    @ViewBuilder
    private var subscriptionStatusView: some View {
        switch effectiveSubscriptionStatus {
        case .updated(let count, _):
            Text(loc.tr("subscription.status_pill", count))
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Color("BrandOrange"))
                .cornerRadius(16)
        case .updating:
            HStack(spacing: 6) {
                ActivityIndicator(color: UIColor(named: "BrandOrange"))
                Text(loc.tr("subscription.updating"))
                    .font(.footnote)
                    .foregroundColor(Color("BrandOrange"))
            }
        case .neverUpdated:
            Text(loc.tr("subscription.never_updated"))
                .font(.footnote)
                .foregroundColor(.secondary)
        case .error:
            EmptyView()
        }
    }

    private func subscriptionRow(_ sub: Subscription) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(sub.url)
                .font(.subheadline)
                .lineLimit(2)
            HStack(spacing: 12) {
                Text(sub.lastUpdated.map { loc.tr("subscription.last_updated", shortDate($0)) }
                     ?? loc.tr("subscription.never_updated"))
                Text(loc.tr("subscription.server_count", sub.serverCount))
            }
            .font(.caption)
            .foregroundColor(.secondary)
            HStack(spacing: 16) {
                Button(action: { refreshSubscription(sub) }) {
                    HStack(spacing: 4) {
                        if subscriptions.updatingIds.contains(sub.id) {
                            ActivityIndicator()
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                        Text(loc.tr("subscription.refresh"))
                    }
                }
                .disabled(subscriptions.updatingIds.contains(sub.id))
                Button(action: { editSubscription(sub) }) {
                    HStack(spacing: 4) {
                        Image(systemName: "pencil")
                        Text(loc.tr("common.edit"))
                    }
                }
                Button(action: { subscriptions.deleteSubscription(id: sub.id) }) {
                    HStack(spacing: 4) {
                        Image(systemName: "trash")
                        Text(loc.tr("common.delete"))
                    }
                }
                .foregroundColor(Color("BrandOrange"))
            }
            .font(.subheadline)
            .foregroundColor(Color("BrandBlue"))
            .buttonStyle(PlainButtonStyle())
        }
    }

    private func shortDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        formatter.locale = Locale(identifier: loc.effectiveCode)
        return formatter.string(from: date)
    }

    private func subscriptionErrorText(kind: SubscriptionErrorKind, detail: String?) -> String {
        switch kind {
        case .emptyURL:
            return loc.tr("subscription.error_empty_url")
        case .httpScheme, .nonHttpsScheme:
            return loc.tr("subscription.error_http_scheme")
        case .invalidURL:
            return loc.tr("subscription.error_invalid_url")
        case .httpStatus:
            return loc.tr("subscription.error_http_status", detail ?? "")
        case .invalidFormat:
            return loc.tr("subscription.error_invalid_format")
        case .network:
            return loc.tr("subscription.error_network", detail ?? "")
        }
    }

    // MARK: - Add sheet (Model C)

    private func openAddSheet() {
        editingSubscriptionId = nil
        addPage = .cards
        showingAdd = true
    }

    private var addSheet: some View {
        Group {
            if addPage == .manual {
                // ServerEditView brings its own NavigationView; its
                // leading item is "< Back" to the cards.
                ServerEditView(server: nil, onBack: { addPage = .cards })
                    .environmentObject(settings)
                    .environmentObject(vpn)
                    .environmentObject(loc)
            } else {
                NavigationView {
                    addSheetBody
                        .navigationBarTitle(loc.tr("servers.add_sheet_title"),
                                            displayMode: .inline)
                        .navigationBarItems(trailing: Button(loc.tr("common.cancel")) {
                            showingAdd = false
                        })
                }
            }
        }
    }

    private var addSheetBody: some View {
        Group {
            switch addPage {
            case .cards:
                cardsPage
            case .importConfig:
                importPage
            case .subscription:
                subscriptionPage
            case .manual:
                EmptyView()
            }
        }
    }

    private var cardsPage: some View {
        List {
            addCard(icon: "square.and.arrow.down",
                    title: loc.tr("servers.import"),
                    subtitle: loc.tr("servers.add_card_import_subtitle")) {
                addPage = .importConfig
            }
            addCard(icon: "server.rack",
                    title: loc.tr("servers.add"),
                    subtitle: loc.tr("servers.add_card_server_subtitle")) {
                addPage = .manual
            }
            addCard(icon: "link",
                    title: loc.tr("subscription.title"),
                    subtitle: loc.tr("servers.add_card_subscription_subtitle")) {
                subStatusText = nil
                editingSubscriptionId = nil
                addPage = .subscription
            }
        }
        .listStyle(GroupedListStyle())
    }

    private func addCard(icon: String, title: String, subtitle: String,
                         action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 24))
                    .foregroundColor(Color("BrandOrange"))
                    .frame(width: 36)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundColor(.primary)
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundColor(.secondary)
            }
            .padding(.vertical, 6)
        }
        .buttonStyle(PlainButtonStyle())
    }

    private var backButton: some View {
        Button(action: { addPage = .cards }) {
            HStack(spacing: 4) {
                Image(systemName: "chevron.left")
                Text(loc.tr("common.back"))
            }
            .foregroundColor(Color("BrandBlue"))
        }
        .buttonStyle(PlainButtonStyle())
    }

    // MARK: - Import page

    private var importPage: some View {
        Form {
            Section {
                backButton
            }
            Section(header: Text(loc.tr("servers.import_title"))) {
                TextField(loc.tr("servers.import_placeholder"),
                          text: $importText)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                NoteText(loc.tr("servers.import_hint"))
            }
            Section {
                Button(action: { showingScanner = true }) {
                    HStack {
                        Image(systemName: "qrcode.viewfinder")
                        Text(loc.tr("servers.scan_qr"))
                    }
                }
                Button(action: pasteImport) {
                    HStack {
                        Image(systemName: "doc.on.clipboard")
                        Text(loc.tr("common.paste"))
                    }
                }
                Button(action: doImport) {
                    Text(loc.tr("servers.import_button"))
                }
                .disabled(importText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .sheet(isPresented: $showingScanner) {
            QRScannerView(onCode: { code in
                // The scan re-opens the sheet on the Import page,
                // pre-filled with the scanned link.
                importText = code
                addPage = .importConfig
            })
            .environmentObject(loc)
        }
    }

    private func pasteImport() {
        if let text = UIPasteboard.general.string {
            importText = text
        }
    }

    private func doImport() {
        if let server = ConfigCodec.importServer(from: importText) {
            settings.addServer(server)
            importText = ""
            showingAdd = false
        } else {
            importError = true
        }
    }

    // MARK: - Subscription page

    private var subscriptionPage: some View {
        Form {
            Section {
                backButton
            }
            Section(header: Text(loc.tr("subscription.title"))) {
                TextField(loc.tr("subscription.url_label"), text: $subURL)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .keyboardType(.URL)
                NoteText(loc.tr("subscription.https_note"))
            }
            Section {
                Button(action: pasteSubscription) {
                    HStack {
                        Image(systemName: "doc.on.clipboard")
                        Text(loc.tr("common.paste"))
                    }
                }
                Button(action: saveSubscription) {
                    HStack {
                        Text(loc.tr(editingSubscriptionId != nil ? "common.save" : "servers.add_action"))
                        Spacer()
                        if subAdding {
                            ActivityIndicator()
                        }
                    }
                }
                .disabled(subURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                          || subAdding)
                if let status = subStatusText {
                    Text(status)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func pasteSubscription() {
        if let text = UIPasteboard.general.string {
            subURL = text
        }
    }

    // In edit mode the action updates the existing subscription by id
    // (its servers stay linked); otherwise it adds a new subscription.
    private func saveSubscription() {
        if let id = editingSubscriptionId {
            updateSubscription(id: id)
        } else {
            addSubscription()
        }
    }

    private func editSubscription(_ sub: Subscription) {
        subURL = sub.url
        subStatusText = nil
        editingSubscriptionId = sub.id
        addPage = .subscription
        showingAdd = true
    }

    private func updateSubscription(id: UUID) {
        let url = subURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        subAdding = true
        subStatusText = nil
        subscriptions.updateSubscriptionUrl(id: id, url: url) { status in
            subAdding = false
            switch status {
            case .updated(let count, let skipped):
                if skipped > 0 {
                    subStatusText = String(format: loc.tr("subscription.status_updated_skipped"),
                                           locale: Locale(identifier: loc.effectiveCode),
                                           count, skipped)
                } else {
                    subStatusText = loc.tr("subscription.status_updated", count)
                }
                subURL = ""
                editingSubscriptionId = nil
            case .error(let kind, let detail):
                subStatusText = subscriptionErrorText(kind: kind, detail: detail)
            case .updating, .neverUpdated:
                subStatusText = nil
            }
        }
    }

    private func addSubscription() {
        let url = subURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        subAdding = true
        subStatusText = nil
        subscriptions.addOrUpdateSubscription(url: url) { status in
            subAdding = false
            switch status {
            case .updated(let count, let skipped):
                if skipped > 0 {
                    subStatusText = String(format: loc.tr("subscription.status_updated_skipped"),
                                           locale: Locale(identifier: loc.effectiveCode),
                                           count, skipped)
                } else {
                    subStatusText = loc.tr("subscription.status_updated", count)
                }
                subURL = ""
            case .error(let kind, let detail):
                subStatusText = subscriptionErrorText(kind: kind, detail: detail)
            case .updating, .neverUpdated:
                subStatusText = nil
            }
        }
    }

    // MARK: - Actions

    private func test(_ server: Server) {
        testingId = server.id
        vpn.testServer(server) { latency, error in
            testingId = nil
            var updated = server
            updated.lastLatencyMs = latency
            updated.lastTestError = error
            settings.updateServer(updated)
        }
    }

    private func autoSelect() {
        autoSelecting = true
        autoSelectMessage = nil
        vpn.autoSelectFastest { winner in
            autoSelecting = false
            if let winner = winner {
                autoSelectMessage = loc.tr("servers.auto_select_done", winner.name)
            } else {
                autoSelectMessage = loc.tr("servers.auto_select_none")
            }
        }
    }

    private func refreshSubscription(_ sub: Subscription) {
        subscriptions.refreshSubscription(id: sub.id) { _ in }
    }
}

// MARK: - Export sheet (QR + copyable link)

private struct ExportView: View {
    var server: Server
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var loc: LocalizationManager
    @State private var copied = false

    var body: some View {
        NavigationView {
            VStack(spacing: 16) {
                if let link = ConfigCodec.exportLink(for: server, settings: settings) {
                    QRCodeView(text: link)
                    Text(link)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                    Button(action: {
                        UIPasteboard.general.string = link
                        copied = true
                    }) {
                        Text(copied ? loc.tr("common.copied") : loc.tr("servers.copy_link"))
                    }
                    .padding()
                    .background(Color("BrandBlue"))
                    .foregroundColor(.white)
                    .cornerRadius(10)
                } else {
                    Text(loc.tr("common.error"))
                }
                NoteText(loc.tr("servers.export_note"))
                    .padding(.horizontal)
                Spacer()
            }
            .padding(.top, 24)
            .navigationBarTitle(server.name, displayMode: .inline)
        }
    }
}

/// Small UIKit activity indicator for inline use. An explicit
/// color tints the spinner (e.g. orange for the scan stepper);
/// without one it keeps the default system colour.
public struct ActivityIndicator: UIViewRepresentable {
    private let color: UIColor?

    public init(color: UIColor? = nil) {
        self.color = color
    }

    public func makeUIView(context: Context) -> UIActivityIndicatorView {
        let view = UIActivityIndicatorView(style: .medium)
        if let color {
            view.color = color
        }
        view.startAnimating()
        return view
    }

    public func updateUIView(_ uiView: UIActivityIndicatorView, context: Context) {}
}

// MARK: - Tutorial accordion (1.0.30)
//
// Two-level accordion shared by the Servers and Settings
// tutorials: only the "Tutorial" header shows first; tapping it
// reveals the topic rows; tapping a topic shows its paragraph.
// Collapsed by default. All text comes from Localizable.strings
// via loc.tr — no hardcoded user-visible strings.

/// One tutorial topic: title + body string keys.
public struct TutorialTopic {
    public let titleKey: String
    public let bodyKey: String
    public init(titleKey: String, bodyKey: String) {
        self.titleKey = titleKey
        self.bodyKey = bodyKey
    }
}

/// A group of tutorial topics, optionally under a group heading
/// (the Settings tutorial groups its topics; Servers has one
/// ungrouped section).
public struct TutorialSection {
    public let groupTitleKey: String?
    public let topics: [TutorialTopic]
    public init(groupTitleKey: String? = nil, topics: [TutorialTopic]) {
        self.groupTitleKey = groupTitleKey
        self.topics = topics
    }
}

public struct TutorialAccordion: View {
    @EnvironmentObject private var loc: LocalizationManager
    public let sections: [TutorialSection]
    public let titleKey: String
    @State private var expanded = false
    @State private var openTopics: Set<String> = []

    // The header title is a parameter: Home passes "guide.title";
    // Servers/Settings use the default "tutorial.title".
    public init(titleKey: String = "tutorial.title", sections: [TutorialSection]) {
        self.titleKey = titleKey
        self.sections = sections
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: { expanded.toggle() }) {
                HStack {
                    Image(systemName: "book")
                        .foregroundColor(Color("BrandOrange"))
                    Text(loc.tr(titleKey))
                        .font(.headline)
                        .foregroundColor(Color("BrandOrange"))
                    Spacer()
                    Image(systemName: "chevron.down")
                        .foregroundColor(Color("BrandOrange"))
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .padding(.vertical, 6)
            }
            .buttonStyle(PlainButtonStyle())
            if expanded {
                ForEach(sections.indices, id: \.self) { index in
                    let section = sections[index]
                    if let groupKey = section.groupTitleKey {
                        Text(loc.tr(groupKey))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(Color("BrandOrange"))
                            .padding(.top, 10)
                    }
                    ForEach(section.topics, id: \.titleKey) { topic in
                        topicRow(topic)
                    }
                }
            }
        }
    }

    private func topicRow(_ topic: TutorialTopic) -> some View {
        let isOpen = openTopics.contains(topic.titleKey)
        return VStack(alignment: .leading, spacing: 0) {
            Button(action: {
                if isOpen {
                    openTopics.remove(topic.titleKey)
                } else {
                    openTopics.insert(topic.titleKey)
                }
            }) {
                HStack {
                    Text(loc.tr(topic.titleKey))
                        .font(.subheadline)
                        .foregroundColor(.primary)
                    Spacer()
                    Image(systemName: "chevron.down")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .rotationEffect(.degrees(isOpen ? 180 : 0))
                }
                .padding(.vertical, 8)
            }
            .buttonStyle(PlainButtonStyle())
            if isOpen {
                Text(loc.tr(topic.bodyKey))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 8)
            }
            Divider()
        }
    }
}
