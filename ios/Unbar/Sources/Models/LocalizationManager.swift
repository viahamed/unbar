// LocalizationManager.swift — in-app language without restarting.
//
// iOS has no supported per-app language switch before iOS 13's per-app
// settings, so Unbar loads the right .lproj bundle itself. The manager
// is an ObservableObject: changing `language` reloads every visible
// string at once. Views read strings through `tr(_:)` and apply RTL
// layout from `isRTL` (see ContentView).

import Foundation
import Combine

public final class LocalizationManager: ObservableObject {
    public static let shared = LocalizationManager()

    @Published public var language: LanguageChoice = .system {
        didSet { reloadBundle() }
    }

    /// The effective language code ("en" or "fa").
    @Published public private(set) var effectiveCode: String = "en"

    private var bundle: Bundle = .main

    private init() {
        reloadBundle()
    }

    public var isRTL: Bool {
        return effectiveCode == "fa"
    }

    /// Resolve the effective code for a choice, following the device.
    public static func effectiveCode(for choice: LanguageChoice) -> String {
        switch choice {
        case .en:
            return "en"
        case .fa:
            return "fa"
        case .system:
            let preferred = Locale.preferredLanguages.first ?? "en"
            return preferred.hasPrefix("fa") ? "fa" : "en"
        }
    }

    private func reloadBundle() {
        effectiveCode = LocalizationManager.effectiveCode(for: language)
        if let path = Bundle.main.path(forResource: effectiveCode, ofType: "lproj"),
           let langBundle = Bundle(path: path) {
            bundle = langBundle
        } else {
            bundle = .main
        }
    }

    /// Localised string for `key`, from the active language bundle.
    public func tr(_ key: String) -> String {
        return bundle.localizedString(forKey: key, value: key, table: "Localizable")
    }

    /// Localised string with one %@ / %d substitution.
    public func tr(_ key: String, _ arg: CVarArg) -> String {
        let format = tr(key)
        return String(format: format, locale: Locale(identifier: effectiveCode), arg)
    }

    /// Localised string with two %@ / %d substitutions.
    public func tr(_ key: String, _ arg1: CVarArg, _ arg2: CVarArg) -> String {
        let format = tr(key)
        return String(format: format, locale: Locale(identifier: effectiveCode), arg1, arg2)
    }
}
