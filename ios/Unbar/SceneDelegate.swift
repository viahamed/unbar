// SceneDelegate.swift — hosts the SwiftUI root view and handles
// `unbar://add?c=...` config links.

import UIKit
import SwiftUI

public class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    public var window: UIWindow?

    public func scene(_ scene: UIScene,
                      willConnectTo session: UISceneSession,
                      options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let contentView = ContentView()
            .environmentObject(AppSettings.shared)
            .environmentObject(VPNManager.shared)
            .environmentObject(StatsStore.shared)
            .environmentObject(LocalizationManager.shared)
            .environmentObject(ThemeManager.shared)
            .environmentObject(TabRouter.shared)
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = UIHostingController(rootView: contentView)
        self.window = window
        window.makeKeyAndVisible()
        // Page background for List/Form screens (before-release: light
        // #EAF0F8, dark unchanged). iOS 14 has no per-list SwiftUI
        // background API, so the table background is set once here.
        // Dark keeps systemGroupedBackground (#1C1C1E) exactly as now;
        // light uses AppBackground (#EAF0F8). ScrollView screens set
        // it directly instead.
        UITableView.appearance().backgroundColor = UIColor { traitCollection in
            traitCollection.userInterfaceStyle == .dark
                ? UIColor.systemGroupedBackground
                : UIColor(named: "AppBackground") ?? UIColor.systemBackground
        }
        ThemeManager.shared.apply()
        for context in connectionOptions.urlContexts {
            openURL(context.url)
        }
    }

    public func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            openURL(context.url)
        }
    }

    /// Handles an `unbar://add?c=...` link: imports the server and tells
    /// the UI to confirm it.
    private func openURL(_ url: URL) {
        guard url.scheme == ConfigCodec.scheme else { return }
        guard let server = ConfigCodec.importServer(from: url.absoluteString) else { return }
        AppSettings.shared.addServer(server)
        SharedLog.append("Imported server \"\(server.name)\" from a link")
        NotificationCenter.default.post(name: Notification.Name("UnbarDidImportServer"),
                                        object: server.name)
    }
}
