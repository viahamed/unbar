// AboutView.swift — the approved About text and brand logo.
//
// "A DNS-tunnel VPN client based on Soar, by Break The Barriers."
// "Built for total blackouts, when DNS is the only way out."
// Each sentence is split over two lines with a comma, centred.
// The logo is the approved wordmark (light/dark variants from the
// asset catalog); it is not redesigned here.

import SwiftUI

public struct AboutView: View {
    @EnvironmentObject private var loc: LocalizationManager

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Unbar logo and the Break The Barriers channel logo side
                // by side, the channel logo to the right of the Unbar logo.
                HStack(alignment: .center, spacing: 16) {
                    // (1.0.2): brand kit wordmark S1 + tagline
                    // (light/dark variants), transparent and wide
                    // (1730x673), so it is sized by width.
                    Image("Logo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 200)
                    Image("ChannelLogo")
                        .resizable()
                        .scaledToFit()
                        .frame(height: 80)
                }
                VStack(spacing: 8) {
                    Text(loc.tr("about.line1"))
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                    Text(loc.tr("about.line2"))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal)
                VStack(spacing: 12) {
                    Button(action: { openURL("https://btb.onl") }) {
                        AboutRow(icon: "globe",
                                 title: "Break The Barriers",
                                 detail: "btb.onl")
                    }
                    Button(action: { openURL("https://t.me/Unbarapp") }) {
                        AboutRow(icon: "paperplane",
                                 title: loc.tr("about.telegram"),
                                 detail: "t.me/Unbarapp")
                    }
                    Button(action: { openURL("https://github.com/viahamed/unbar") }) {
                        AboutRow(icon: "chevron.left.slash.chevron.right",
                                 title: loc.tr("about.source"),
                                 detail: "github.com/viahamed/unbar")
                    }
                    Button(action: { openURL("https://github.com/getlantern/soar") }) {
                        AboutRow(icon: "bolt",
                                 title: loc.tr("about.soar"),
                                 detail: "github.com/getlantern/soar")
                    }
                }
                donationCard
                // Tunnel protocol version + license, matching Android.
                Text(loc.tr("about.soar_version"))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Text(loc.tr("about.license"))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Text(loc.tr("about.version", appVersion()))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding()
        }
        // Page background (1.0.2 Cloud: light #F2F6FB, dark #101722).
        .background(Color("AppBackground"))
        .navigationBarTitle(loc.tr("about.title"), displayMode: .inline)
    }

    /// Donation: the heart is orange (the brand orange), never red.
    private var donationCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 8) {
                Image(systemName: "heart.fill")
                    .foregroundColor(Color("BrandOrange"))
                Text(loc.tr("about.donate_title"))
                    .font(.headline)
            }
            Text(loc.tr("about.donate_text"))
                .font(.subheadline)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Divider()
            DonationAddressRow(label: loc.tr("about.donate_usdt"),
                               address: "0x46f872b46BFa471607ad0F0042bdFFCB2B9c702b")
            DonationAddressRow(label: loc.tr("about.donate_ton"),
                               address: "UQBdKaKX61k56IaJMbjTTMvXd7fskpbJnzGUPlQAyZT6RJn5")
        }
        .padding(UnbarRadius.cardPadding)
        .background(Color("Surface"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.outer, style: .continuous))
    }

    private func openURL(_ string: String) {
        if let url = URL(string: string) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    private func appVersion() -> String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(version) (\(build))"
    }
}

private struct AboutRow: View {
    var icon: String
    var title: String
    var detail: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(Color("BrandBlue"))
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(detail).font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(UnbarRadius.cardPadding)
        .background(Color("Surface"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.outer, style: .continuous))
    }
}

/// One copyable donation address row.
private struct DonationAddressRow: View {
    var label: String
    var address: String
    @EnvironmentObject private var loc: LocalizationManager
    @State private var copied = false

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
                Text(address)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer()
            Button(action: {
                UIPasteboard.general.string = address
                copied = true
            }) {
                Text(copied ? loc.tr("common.copied") : loc.tr("common.copy"))
                    .font(.caption)
                    .foregroundColor(Color("BrandBlue"))
            }
        }
    }
}
