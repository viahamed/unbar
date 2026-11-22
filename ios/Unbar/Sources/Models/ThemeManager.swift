// ThemeManager.swift — manual light/dark/system appearance.
//
// Unbar applies the choice through the key window's
// `overrideUserInterfaceStyle` (rather than `.preferredColorScheme`),
// so it applies app-wide. The manager is an ObservableObject;
// SceneDelegate and ContentView call `apply()` whenever the choice
// changes or the scene appears.

import UIKit
import Combine

public final class ThemeManager: ObservableObject {
    public static let shared = ThemeManager()

    @Published public var appearance: AppearanceChoice = .system {
        didSet { apply() }
    }

    private init() {}

    public func apply() {
        let style: UIUserInterfaceStyle
        switch appearance {
        case .system:
            style = .unspecified
        case .light:
            style = .light
        case .dark:
            style = .dark
        }
        DispatchQueue.main.async {
            // connectedScenes (iOS 13+) instead of the deprecated
            // UIApplication.shared.windows.
            let windows = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
            for window in windows {
                window.overrideUserInterfaceStyle = style
            }
        }
    }
}
