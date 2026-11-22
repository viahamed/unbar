// ContentView.swift — the root view: floating capsule navigation (Home, Servers, Settings, About) with RTL layout when Persian is active.
//
// On launch an in-app splash shows first: the static Unbar logo, centred, with a small spinning circle below it, for at least 2000ms from the moment it first appears (matching Android). The system LaunchScreen.storyboard still shows before this.

import SwiftUI

public struct ContentView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var router: TabRouter

@State private var importedName: String?
@State private var showImportAlert = false
@State private var showSplash = true
@State private var didAutoUpdate = false

public init() {}

public var body: some View {
    ZStack {
        ZStack(alignment: .bottom) {
            Group {
                switch router.selectedTab {
                case 1:
                    ServersView()
                case 2:
                    SettingsView()
                case 3:
                    NavigationView {
                        AboutView()
                    }
                default:
                    HomeView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationViewStyle(StackNavigationViewStyle())
            .accentColor(Color("AccentColor"))

            FloatingCapsuleMenu(selectedTab: $router.selectedTab, loc: loc)
                .padding(.bottom, 16)
        }
        .environment(\.layoutDirection,
                     loc.isRTL ? .rightToLeft : .leftToRight)

        if showSplash {
            SplashView {
                showSplash = false
            }
        }
    }
    .onAppear {
        if !didAutoUpdate {
            didAutoUpdate = true
            SubscriptionStore.shared.refreshAllSubscriptionsIfNeeded()
        }
    }
    .onReceive(settings.$appearance) { appearance in
        ThemeManager.shared.appearance = appearance
    }
    .onReceive(NotificationCenter.default.publisher(
        for: Notification.Name("UnbarDidImportServer"))) { note in
        if let name = note.object as? String {
            importedName = name
            showImportAlert = true
        }
    }
    .alert(isPresented: $showImportAlert) {
        Alert(title: Text(loc.tr("servers.imported_title")),
              message: Text(loc.tr("servers.imported_message",
                                   importedName ?? "")),
              dismissButton: .default(Text(loc.tr("common.ok"))))
    }
}

}

// Capsule menu icon: white hamburger (3 lines; middle line shorter +
// shifted left), turning into an X when expanded — the approved Model C icon.
private struct CapsuleMenuIcon: View {
    var isExpanded: Bool
    var body: some View {
        ZStack {
            if !isExpanded {
                VStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(Color.white)
                        .frame(width: 22, height: 2.5)
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(Color.white)
                        .frame(width: 15, height: 2.5)
                        .offset(x: -3.5)
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(Color.white)
                        .frame(width: 22, height: 2.5)
                }
            } else {
                Image(systemName: "xmark")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(.white)
            }
        }
        .frame(width: 28, height: 28)
    }
}

private struct FloatingCapsuleMenu: View {
    @Binding var selectedTab: Int
    @ObservedObject var loc: LocalizationManager
    @State private var isExpanded = false

    private let items: [(tag: Int, titleKey: String, icon: String)] = [
        (0, "tab.home", "house.fill"),
        (1, "tab.servers", "server.rack"),
        (2, "tab.settings", "gearshape.fill"),
        (3, "settings.about", "info.circle.fill")
    ]

    // Capsule dark blue (#10367C) — the approved Model C color.
    private let capsuleBlue = Color(red: 0x10 / 255.0, green: 0x36 / 255.0, blue: 0x7C / 255.0)

    var body: some View {
        HStack(spacing: 0) {
            if isExpanded {
                HStack(spacing: 0) {
                    ForEach(items, id: \.tag) { item in
                        let selected = selectedTab == item.tag
                        Button(action: {
                            selectedTab = item.tag
                            withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                                isExpanded = false
                            }
                        }) {
                            VStack(spacing: 2) {
                                Image(systemName: item.icon)
                                    .font(.system(size: 24, weight: .semibold))
                                Text(loc.tr(item.titleKey))
                                    .font(.system(size: 13, weight: .regular))
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.75)
                            }
                            .foregroundColor(.white)
                            // (1.0.33): 6pt insets, not 8pt — with the
                            // wider capsule the longest label
                            // ("Settings" / "تنظیمات") now fits without
                            // shrinking or ellipsis.
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(
                                Group {
                                    if selected {
                                        // Orange rounded-rect pill behind
                                        // icon+label. Sized to the item's
                                        // content with insets, so it never
                                        // extends outside the blue capsule —
                                        // not even on Home, the leftmost item.
                                        RoundedRectangle(cornerRadius: 12)
                                            .fill(Color("BrandOrange"))
                                    }
                                }
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                        .buttonStyle(PlainButtonStyle())
                    }
                }
                .padding(.leading, 10)
                .transition(.opacity)
            }

            Button(action: {
                withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                    isExpanded.toggle()
                }
            }) {
                CapsuleMenuIcon(isExpanded: isExpanded)
                    .frame(width: 56, height: 56)
            }
            .buttonStyle(PlainButtonStyle())
        }
        // (1.0.33): 340pt, not 304pt — the 4 items need 71pt each
        // (284pt of items) so the longest label ("Settings" /
        // "تنظیمات") fits inside its orange pill without shrinking
        // or ellipsis. The 56pt close button is unchanged, so
        // items = 340-56.
        .frame(width: isExpanded ? 340 : 56, height: 56)
        .background(capsuleBlue)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .shadow(color: Color.black.opacity(0.18), radius: 10, x: 0, y: 4)
    }
}

/// The launch splash: the Unbar logo, static and centred (it never rotates), with a small loading circle spinning below it. Stays visible for at least 2000ms measured from the first drawn frame: the timer starts in onAppear, which fires when the splash is first presented for drawing.
private struct SplashView: View { var onDone: () -> Void

var body: some View {
    ZStack {
        Color(UIColor.systemBackground)
            .edgesIgnoringSafeArea(.all)
        VStack(spacing: 28) {
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 180, height: 180)
                .cornerRadius(24)
            ActivityIndicator()
        }
    }
    .onAppear {
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            onDone()
        }
    }
}

}
