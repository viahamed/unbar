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
        // Page background for List/Form screens (1.0.2 Cloud theme:
        // light #F2F6FB, dark #101722; rows on Surface, light #FFFFFF,
        // dark #18212E — Android's background/surface). iOS 14 has no
        // per-list SwiftUI background API, so the table colours are
        // set once here. ScrollView screens set it directly instead.
        // (iOS 16+ draws Form/List with a collection view, which keeps
        // the system grouped colours.)
        UITableView.appearance().backgroundColor = UIColor(named: "AppBackground") ?? UIColor.systemGroupedBackground
        UITableViewCell.appearance().backgroundColor = UIColor(named: "Surface") ?? UIColor.secondarySystemGroupedBackground
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
