// HistoryView.swift — connection history list (1.0.15).
//
// The monthly total above the list, then the last 30 sessions: when
// it ran, which server it used, how long it lasted and how much
// data it moved.

import SwiftUI

public struct HistoryView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @State private var sessions: [HistorySession] = []

    public init() {}

    public var body: some View {
        List {
            Section {
                Text(loc.tr("history.month_total",
                            StatsStore.formatBytes(ConnectionHistoryStore.shared.monthBytes())))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            Section {
                if sessions.isEmpty {
                    Text(loc.tr("history.empty"))
                        .foregroundColor(.secondary)
                }
                ForEach(sessions) { session in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(session.serverName)
                                .font(.headline)
                            Spacer()
                            Text(formatDate(session.startedAt))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Text(session.serverZone)
                            .font(.caption)
                            .foregroundColor(.secondary)
                        HStack(spacing: 12) {
                            Text(formatDuration(session.duration))
                            Text(StatsStore.formatBytes(session.totalBytes))
                        }
                        .font(.caption)
                        .foregroundColor(.secondary)
                    }
                }
            }
        }
        .navigationBarTitle(loc.tr("history.title"), displayMode: .inline)
        .onAppear {
            sessions = ConnectionHistoryStore.shared.sessions()
        }
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        formatter.locale = Locale(identifier: loc.effectiveCode)
        return formatter.string(from: date)
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let seconds = Int(duration)
        if seconds < 60 {
            return loc.tr("history.duration_seconds", seconds)
        }
        return loc.tr("history.duration_minutes", seconds / 60)
    }
}
