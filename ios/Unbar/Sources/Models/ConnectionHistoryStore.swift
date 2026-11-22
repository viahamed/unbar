// ConnectionHistoryStore.swift — connection history (1.0.15).
//
// A lightweight local record of connect/disconnect events: server
// name + zone, start/end time, duration and bytes moved. No history
// store existed on iOS, so this is kept in the App Group
// UserDefaults (same store the rest of the app state uses), capped
// at the last 30 sessions. The monthly total shown above the list
// is the bytes of sessions that started this month.

import Foundation

public struct HistorySession: Identifiable, Codable, Equatable {
    public var id: UUID
    public var serverName: String
    public var serverZone: String
    public var startedAt: Date
    public var endedAt: Date?
    public var bytesUp: Int64
    public var bytesDown: Int64

    public init(id: UUID = UUID(), serverName: String, serverZone: String,
                startedAt: Date, endedAt: Date? = nil,
                bytesUp: Int64 = 0, bytesDown: Int64 = 0) {
        self.id = id
        self.serverName = serverName
        self.serverZone = serverZone
        self.startedAt = startedAt
        self.endedAt = endedAt
        self.bytesUp = bytesUp
        self.bytesDown = bytesDown
    }

    public var duration: TimeInterval {
        return (endedAt ?? startedAt).timeIntervalSince(startedAt)
    }

    public var totalBytes: Int64 {
        return bytesUp + bytesDown
    }
}

public final class ConnectionHistoryStore {
    public static let shared = ConnectionHistoryStore()

    private static let maxSessions = 30
    private let key = "connectionHistory"

    /// The open session, if a tunnel is currently up.
    private var openSession: HistorySession?
    private var openStartUp: Int64 = 0
    private var openStartDown: Int64 = 0

    /// Start time of the currently open session, if any (for the
    /// Home connection-time card, matching Android).
    public var currentSessionStartedAt: Date? {
        return openSession?.startedAt
    }

    private init() {}

    public func sessions() -> [HistorySession] {
        guard let defaults = AppGroup.defaults,
              let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([HistorySession].self, from: data) else {
            return []
        }
        return decoded.sorted { $0.startedAt > $1.startedAt }
    }

    /// Bytes moved by sessions that started in the current month.
    public func monthBytes() -> Int64 {
        let calendar = Calendar.current
        let now = Date()
        let month = calendar.component(.month, from: now)
        let year = calendar.component(.year, from: now)
        return sessions()
            .filter {
                calendar.component(.month, from: $0.startedAt) == month
                    && calendar.component(.year, from: $0.startedAt) == year
            }
            .reduce(0) { $0 + $1.totalBytes }
    }

    /// Called when a tunnel comes up. A still-open session (the app
    /// was killed mid-session) is closed first so the list never
    /// shows a half-open session.
    public func sessionStarted(serverName: String, serverZone: String,
                               upBytes: Int64, downBytes: Int64) {
        if openSession != nil {
            sessionEnded(upBytes: upBytes, downBytes: downBytes)
        }
        openSession = HistorySession(serverName: serverName,
                                     serverZone: serverZone,
                                     startedAt: Date())
        openStartUp = upBytes
        openStartDown = downBytes
    }

    /// Called when a tunnel goes down. A no-op when nothing is open.
    public func sessionEnded(upBytes: Int64, downBytes: Int64) {
        guard var open = openSession else { return }
        open.endedAt = Date()
        open.bytesUp = max(0, upBytes - openStartUp)
        open.bytesDown = max(0, downBytes - openStartDown)
        openSession = nil
        var all = sessions()
        all.append(open)
        all.sort { $0.startedAt > $1.startedAt }
        if all.count > ConnectionHistoryStore.maxSessions {
            all = Array(all.prefix(ConnectionHistoryStore.maxSessions))
        }
        if let data = try? JSONEncoder().encode(all) {
            AppGroup.defaults?.set(data, forKey: key)
        }
    }
}
