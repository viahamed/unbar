// AppDelegate.swift — app entry point (delegate lifecycle).
//
// Unbar uses UIApplicationDelegate + UISceneDelegate with a
// UIHostingController (the delegate lifecycle, kept for the shared
// scene handling).

import UIKit

@UIApplicationMain
public class AppDelegate: UIResponder, UIApplicationDelegate {

    public func application(_ application: UIApplication,
                            didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Apply the saved language and appearance before the first frame.
        let settings = AppSettings.shared
        LocalizationManager.shared.language = settings.language
        ThemeManager.shared.appearance = settings.appearance
        // Subscriptions auto-update in the background when the switch
        // is on; manual servers are never touched by a refresh.
        SubscriptionStore.shared.refreshAllSubscriptionsIfNeeded()
        return true
    }

    public func application(_ application: UIApplication,
                            configurationForConnecting connectingSceneSession: UISceneSession,
                            options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Default Configuration",
                                                 sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}
