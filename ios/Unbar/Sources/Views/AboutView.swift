// AboutView.swift — (1.0.3) About redesign, the user-approved final
// mockup ("F"), matching Android's AboutScreen:
//
//  1. Blue hero card (UnbarRadius.hero = 24, like the Home status
//     card): app-icon tile | thin divider | Break The Barriers tile
//     (white tile in light; #101722 tile with the dark BTB logo variant
//     in dark — asset-catalog appearances), the "Unbar" wordmark (white
//     "Un", orange "bar"), "by Break The Barriers", the tagline, and two
//     chips: "App v<CFBundleShortVersionString>" (read at runtime) and
//     "Soar core v0.2.5".
//  2. Links card: Website, Telegram channel, Source code — icon tile +
//     title + subtitle + chevron, with dividers.
//  3. Donation card, collapsed by default (tutorial-accordion style):
//     orange heart + bold title + muted chevron; expanded: the note,
//     then USDT · BEP20 and TON in full, monospace, in 8pt inner boxes
//     with a neutral square copy button. Only the heart is orange.
//
// Nested radius: 24 outer / 16 padding / 8 inner; capsule pills 11.
// The old license line ("about.license") and the Soar / btb.onl rows
// are no longer shown here.

import SwiftUI

public struct AboutView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @State private var donationExpanded = false
    @State private var copiedToast = false

    private let websiteURL = "https://unbar.cc"
    private let telegramURL = "https://t.me/Unbarapp"
    private let sourceURL = "https://github.com/viahamed/unbar"
    private let usdtAddress = "0x46f872b46BFa471607ad0F0042bdFFCB2B9c702b"
    private let tonAddress = "UQBdKaKX61k56IaJMbjTTMvXd7fskpbJnzGUPlQAyZT6RJn5"

    public init() {}

    public var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(spacing: 14) {
                    heroCard
                    linksCard
                    donationCard
                    Spacer(minLength: 96)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
            }
            if copiedToast {
                Text(loc.tr("common.copied"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color.black.opacity(0.75))
                    .clipShape(Capsule())
                    .padding(.bottom, 110)
                    .transition(.opacity)
            }
        }
        // Page background (Cloud: light #F2F6FB, dark #101722).
        .background(Color("AppBackground").edgesIgnoringSafeArea(.all))
        .navigationBarTitle(loc.tr("about.title"), displayMode: .inline)
    }

    // MARK: Hero

    private var heroCard: some View {
        VStack(spacing: 0) {
            // Logos keep their order in RTL too (app first, then BTB).
            HStack(spacing: 14) {
                heroTile {
                    Image("AboutAppIcon")
                        .resizable()
                        .scaledToFit()
                }
                Rectangle()
                    .fill(Color.white.opacity(0.25))
                    .frame(width: 1, height: 44)
                heroTile {
                    Image("ChannelLogo")
                        .resizable()
                        .scaledToFit()
                        .frame(height: 58)
                }
            }
            .environment(\.layoutDirection, .leftToRight)
            Image("WordmarkHero")
                .resizable()
                .scaledToFit()
                .frame(height: 30)
                .padding(.top, 10)
                .accessibility(label: Text("Unbar"))
            Text(loc.tr("about.by_btb"))
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(Color.white.opacity(0.85))
            Text(loc.tr("about.line2"))
                .font(.system(size: 14, weight: .medium))
                .lineSpacing(4)
                .foregroundColor(Color.white.opacity(0.95))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
            HStack(spacing: 6) {
                heroChip(loc.tr("about.chip_app", appVersion()))
                heroChip(loc.tr("about.chip_core"))
                heroChip(loc.tr("about.chip_license"))
            }
            .environment(\.layoutDirection, .leftToRight)
            .padding(.top, 14)
        }
        .padding(.horizontal, 18)
        .padding(.top, 22)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity)
        .background(
            ZStack(alignment: .bottomTrailing) {
                Color("HeroBlue")
                // Soft decorative circle, bottom-trailing (200pt, white 6%).
                Circle()
                    .fill(Color.white.opacity(0.06))
                    .frame(width: 200, height: 200)
                    .offset(x: 40, y: 70)
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.hero, style: .continuous))
    }

    /// 72pt logo tile, radius 20: white in light, #101722 in dark
    /// ("AppBackground" dark is #101722).
    private func heroTile<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ZStack {
            HeroTileBackground()
            content()
        }
        .frame(width: 72, height: 72)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: Color.black.opacity(0.25), radius: 9, x: 0, y: 6)
    }

    /// Capsule pill, radius 11, centred text.
    private func heroChip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.14))
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
    }

    // MARK: Links

    private var linksCard: some View {
        VStack(spacing: 0) {
            Button(action: { openURL(websiteURL) }) {
                AboutLinkRow(icon: Image(systemName: "globe"),
                             title: loc.tr("about.link_website"),
                             detail: "unbar.cc")
            }
            .buttonStyle(PlainButtonStyle())
            Rectangle().fill(Color("Outline")).frame(height: 1).padding(.vertical, 10)
            Button(action: { openURL(telegramURL) }) {
                AboutLinkRow(icon: Image(systemName: "paperplane.fill"),
                             title: loc.tr("about.link_telegram"),
                             detail: "@Unbarapp")
            }
            .buttonStyle(PlainButtonStyle())
            Rectangle().fill(Color("Outline")).frame(height: 1).padding(.vertical, 10)
            Button(action: { openURL(sourceURL) }) {
                AboutLinkRow(icon: Image(systemName: "chevron.left.slash.chevron.right"),
                             title: loc.tr("about.source"),
                             detail: "github.com/viahamed/unbar")
            }
            .buttonStyle(PlainButtonStyle())
        }
        .padding(UnbarRadius.cardPadding)
        .background(Color("Surface"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.outer, style: .continuous))
    }

    // MARK: Donation

    private var donationCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: {
                withAnimation(.easeInOut(duration: 0.2)) { donationExpanded.toggle() }
            }) {
                HStack(alignment: .center, spacing: 8) {
                    Image(systemName: "heart.fill")
                        .font(.system(size: 20))
                        .foregroundColor(Color("BrandOrange"))
                    Text(loc.tr("about.donate_title"))
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(Color("Ink"))
                    Spacer()
                    Image(systemName: donationExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(Color("Muted"))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PlainButtonStyle())
            if donationExpanded {
                Text(loc.tr("about.donate_text"))
                    .font(.system(size: 13))
                    .lineSpacing(3)
                    .foregroundColor(Color("Muted"))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 12)
                donationAddress(label: loc.tr("about.donate_usdt"), address: usdtAddress)
                donationAddress(label: loc.tr("about.donate_ton"), address: tonAddress)
            }
        }
        .padding(UnbarRadius.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color("Surface"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.outer, style: .continuous))
    }

    private func donationAddress(label: String, address: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(Color("Muted"))
            // Address + copy button stay LTR in Persian too.
            HStack(alignment: .center, spacing: 8) {
                Text(address)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(Color("Ink"))
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color("SurfaceVariant"))
                    .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.inner, style: .continuous))
                Button(action: { copy(address) }) {
                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 15))
                        .foregroundColor(Color("Ink"))
                        .frame(width: 44, height: 44)
                        .background(Color("SurfaceVariant"))
                        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.inner, style: .continuous))
                }
                .buttonStyle(PlainButtonStyle())
                .accessibility(label: Text(loc.tr("common.copy")))
            }
            .environment(\.layoutDirection, .leftToRight)
        }
        .padding(.top, 12)
    }

    private func copy(_ address: String) {
        UIPasteboard.general.string = address
        withAnimation { copiedToast = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { copiedToast = false }
        }
    }

    private func openURL(_ string: String) {
        if let url = URL(string: string) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    private func appVersion() -> String {
        return Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }
}

/// The logo tile background: white in light, #101722 in dark.
private struct HeroTileBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        colorScheme == .dark
            ? Color(red: 16.0 / 255.0, green: 23.0 / 255.0, blue: 34.0 / 255.0)
            : Color.white
    }
}

private struct AboutLinkRow: View {
    var icon: Image
    var title: String
    var detail: String
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            icon
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(Color("BrandBlue"))
                .frame(width: 36, height: 36)
                .background(Color("SurfaceVariant"))
                .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.inner, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(Color("Ink"))
                // Handles/URLs are Latin: wrapped in LRI…PDI so they
                // read LTR, start-aligned in the screen's direction.
                Text("\u{2066}\(detail)\u{2069}")
                    .font(.system(size: 12))
                    .foregroundColor(Color("Muted"))
            }
            Spacer()
            Image(systemName: layoutDirection == .rightToLeft ? "chevron.left" : "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(Color("Muted"))
        }
        .contentShape(Rectangle())
    }
}
