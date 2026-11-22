// LogsView.swift — capped debug log.
//
// Lines are written by the app and the extension into the shared App
// Group (see SharedLog). The log is capped at 300 lines. It never
// contains the SOCKS password or the server key.

import SwiftUI

public struct LogsView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @State private var lines: [String] = []
    @State private var showDetails = false
    @State private var showingClearConfirm = false

    // Display-time filter only: the periodic 20s path/stream lines
    // (tags "[SoarPath]" / "[Streams]") are hidden by default. The
    // shared log itself is untouched, so nothing is lost.
    // Note: on iOS the live stats normally travel via SharedKey.stats,
    // not the log, so this is a symmetric guard with the Android
    // screen (LogsScreen.kt) for any such line that lands here.
    private var displayedLines: [String] {
        showDetails ? lines : lines.filter { !Self.isPeriodic($0) }
    }

    private static func isPeriodic(_ line: String) -> Bool {
        line.contains("[SoarPath]") || line.contains("[Streams]")
    }

    public init() {}

    public var body: some View {
        List {
            Section {
                Toggle(loc.tr("logs.show_details"), isOn: $showDetails)
            }
            if displayedLines.isEmpty {
                if lines.isEmpty {
                    Text(loc.tr("logs.empty"))
                        .foregroundColor(.secondary)
                }
            }
            ForEach(displayedLines, id: \.self) { line in
                Text(line)
                    .font(.caption)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .navigationBarTitle(loc.tr("logs.title"), displayMode: .inline)
        .navigationBarItems(trailing: Button(loc.tr("logs.clear")) {
            showingClearConfirm = true
        })
        .onAppear {
            lines = SharedLog.read()
        }
        .alert(isPresented: $showingClearConfirm) {
            Alert(title: Text(loc.tr("logs.clear_title")),
                  message: Text(loc.tr("logs.clear_message")),
                  primaryButton: .destructive(Text(loc.tr("logs.clear"))) {
                    SharedLog.clear()
                    lines = []
                  },
                  secondaryButton: .cancel(Text(loc.tr("common.cancel"))))
        }
    }
}
