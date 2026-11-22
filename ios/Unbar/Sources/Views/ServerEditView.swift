// ServerEditView.swift — add or edit a server.
//
// Fields: name, zone, server public key (base64 of 32 bytes). The key
// is a public key, not a secret. A Test button verifies the tunnel
// before saving.

import SwiftUI

public struct ServerEditView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var vpn: VPNManager
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.presentationMode) private var presentationMode

    private var existing: Server?

    /// When set (the Add sheet's manual page), the leading bar item
    /// is "< Back" to the sheet's cards instead of Cancel.
    private var onBack: (() -> Void)?

    @State private var name: String
    @State private var zone: String
    @State private var serverKey: String
    @State private var testState: TestState = .idle

    private enum TestState: Equatable {
        case idle
        case testing
        case ok(Int)
        case failed(String)
    }

    public init(server: Server?, onBack: (() -> Void)? = nil) {
        self.existing = server
        self.onBack = onBack
        _name = State(initialValue: server?.name ?? "")
        _zone = State(initialValue: server?.zone ?? "")
        _serverKey = State(initialValue: server?.serverKeyB64 ?? "")
    }

    private var draft: Server {
        // Preserve the fields the form does not edit (Android keeps them
        // via existing.copy): a subscription server must stay linked to
        // its subscription, and the last test result survives an edit
        // that does not re-test.
        return Server(id: existing?.id ?? UUID(),
                      name: name.trimmingCharacters(in: .whitespaces),
                      zone: zone.trimmingCharacters(in: .whitespaces),
                      serverKeyB64: serverKey.trimmingCharacters(in: .whitespaces),
                      lastLatencyMs: existing?.lastLatencyMs,
                      lastTestError: existing?.lastTestError,
                      subscriptionId: existing?.subscriptionId)
    }

    /// The first entry error (zone, then key, then duplicate), or nil.
    private var validationError: String? {
        return ServerValidation.validateEntry(
            servers: settings.servers,
            excludeId: existing?.id,
            zone: zone,
            publicKeyB64: serverKey)
    }

    private var canSave: Bool {
        return !draft.name.isEmpty && validationError == nil
    }

    public var body: some View {
        NavigationView {
            Form {
                Section(header: Text(loc.tr("server_edit.section"))) {
                    TextField(loc.tr("server_edit.name"), text: $name)
                    TextField(loc.tr("server_edit.zone"), text: $zone)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                        .keyboardType(.URL)
                    TextField(loc.tr("server_edit.key"), text: $serverKey)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    NoteText(loc.tr("server_edit.key_hint"))
                }
                Section {
                    Button(action: test) {
                        HStack {
                            Text(loc.tr("server_edit.test"))
                            Spacer()
                            if testState == .testing {
                                ActivityIndicator()
                            }
                        }
                    }
                    .disabled(!draft.isValid || testState == .testing)
                    switch testState {
                    case .ok(let ms):
                        Text(loc.tr("server_edit.test_ok", ms))
                            .foregroundColor(Color("BrandBlue"))
                    case .failed(let message):
                        Text(message)
                            .foregroundColor(Color("BrandOrange"))
                    case .idle, .testing:
                        EmptyView()
                    }
                }
                if let error = validationError, (!zone.isEmpty || !serverKey.isEmpty) {
                    Section {
                        NoteText(error)
                    }
                }
            }
            .navigationBarTitle(existing == nil ? loc.tr("server_edit.add_title")
                : loc.tr("server_edit.edit_title"), displayMode: .inline)
            .navigationBarItems(
                leading: leadingBarButton,
                trailing: Button(loc.tr("common.save")) {
                    save()
                }
                .disabled(!canSave)
            )
        }
    }

    private var leadingBarButton: some View {
        Group {
            if let onBack = onBack {
                Button(loc.tr("common.back")) {
                    onBack()
                }
            } else {
                Button(loc.tr("common.cancel")) {
                    presentationMode.wrappedValue.dismiss()
                }
            }
        }
    }

    private func test() {
        testState = .testing
        vpn.testServer(draft) { latency, error in
            if let latency = latency {
                testState = .ok(latency)
            } else {
                testState = .failed(error ?? loc.tr("common.error"))
            }
        }
    }

    private func save() {
        var server = draft
        if case .ok(let ms) = testState {
            server.lastLatencyMs = ms
            server.lastTestError = nil
        }
        if existing == nil {
            settings.addServer(server)
        } else {
            settings.updateServer(server)
        }
        presentationMode.wrappedValue.dismiss()
    }
}
