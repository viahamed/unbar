// SharedLog.swift — capped log shared by the app and the extension.
//
// Both processes append lines to the shared UserDefaults array
// (SharedKey.logLines). The log is capped at 300 lines so it can never
// grow without bound. Lines are timestamped in the extension/app local
// time. The app's Logs screen reads them; the SOCKS password and the
// server key are never logged.

import Foundation

public enum SharedLog {
    public static let maxLines = 300

    private static let queue = DispatchQueue(label: "org.breakthebarriers.unbar.sharedlog")

    public static func append(_ message: String) {
        queue.async {
            guard let defaults = AppGroup.defaults else { return }
            var lines = defaults.stringArray(forKey: SharedKey.logLines) ?? []
            let stamp = SharedLog.timestamp()
            lines.append("[\(stamp)] \(message)")
            if lines.count > maxLines {
                lines.removeFirst(lines.count - maxLines)
            }
            defaults.set(lines, forKey: SharedKey.logLines)
        }
    }

    public static func read() -> [String] {
        return AppGroup.defaults?.stringArray(forKey: SharedKey.logLines) ?? []
    }

    public static func clear() {
        queue.async {
            AppGroup.defaults?.removeObject(forKey: SharedKey.logLines)
        }
    }

    private static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date())
    }
}
