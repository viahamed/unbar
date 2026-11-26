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

    // (1.0.2) Cloud theme: the capsule colour follows the theme
    // (CapsuleBlue: light #173A6B, dark #212C3C), like Android's
    // UnbarCapsuleBlue.
    private let capsuleBlue = Color("CapsuleBlue")

    // (1.0.2) Geometry, matching Android UnbarApp.kt: 316pt open
    // (was 340pt), 56pt tall, 16pt corners. 4 items of 63pt after an
    // 8pt start inset = 260pt, plus the 56x56 menu button = 316pt.
    private let openWidth: CGFloat = 316
    private let height: CGFloat = 56
    private let itemWidth: CGFloat = 63
    private let itemsStartInset: CGFloat = 8

    var body: some View {
        HStack(spacing: 0) {
            if isExpanded {
                HStack(spacing: 0) {
                    ForEach(items, id: \.tag) { item in
                        Button(action: {
                            selectedTab = item.tag
                            withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                                isExpanded = false
                            }
                        }) {
                            CapsuleMenuItem(icon: item.icon,
                                            title: loc.tr(item.titleKey),
                                            selected: selectedTab == item.tag)
                                .frame(width: itemWidth, height: height, alignment: .center)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(PlainButtonStyle())
                    }
                }
                .padding(.leading, itemsStartInset)
                .frame(width: openWidth - height, height: height, alignment: .leading)
                .transition(.opacity)
            }

            Button(action: {
                withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                    isExpanded.toggle()
                }
            }) {
                // 28pt icon centred in the 56x56 button: 14pt above
                // and below.
                CapsuleMenuIcon(isExpanded: isExpanded)
                    .frame(width: height, height: height, alignment: .center)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PlainButtonStyle())
        }
        .frame(width: isExpanded ? openWidth : height, height: height)
        .background(capsuleBlue)
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.capsule, style: .continuous))
        .shadow(color: Color.black.opacity(0.18), radius: 10, x: 0, y: 4)
    }
}

/// (1.0.2) One capsule menu item. Every item, selected or not, has
/// the same box — the pill's 5pt horizontal / 4pt vertical insets,
/// a 22pt icon, a 2pt gap and one 14pt label line (12pt text) — so
/// the icons sit on one line, the block (46pt) is centred in the
/// 56pt capsule with 5pt above and below, and nothing moves when the
/// selection moves. The selected item's pill is orange with an 11pt
/// radius (16pt capsule - 5pt inset).
private struct CapsuleMenuItem: View {
    var icon: String
    var title: String
    var selected: Bool

    var body: some View {
        VStack(alignment: .center, spacing: 2) {
            Image(systemName: icon)
                .resizable()
                .scaledToFit()
                .frame(width: 22, height: 22, alignment: .center)
            Text(title)
                .font(.system(size: 12, weight: .regular))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                // One 14pt line box, text centred in it.
                .frame(height: 14, alignment: .center)
        }
        .foregroundColor(.white)
        .padding(.horizontal, 5)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: UnbarRadius.capsulePill, style: .continuous)
                .fill(selected ? Color("BrandOrange") : Color.clear)
        )
    }
}

/// The launch splash: the Unbar logo, static and centred (it never rotates), with a small loading circle spinning below it. Stays visible for at least 2000ms measured from the first drawn frame: the timer starts in onAppear, which fires when the splash is first presented for drawing.
private struct SplashView: View { var onDone: () -> Void

var body: some View {
    ZStack {
        // (1.0.2) Cloud theme splash background (light #F6F9FD,
        // dark #101722), like Android's UnbarLightSplashBg.
        Color("SplashBackground")
            .edgesIgnoringSafeArea(.all)
        VStack(spacing: 28) {
            // Brand kit wordmark S1 + tagline: wide and transparent,
            // sized by width.
            Image("Logo")
                .resizable()
                .scaledToFit()
                .frame(width: 220)
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
