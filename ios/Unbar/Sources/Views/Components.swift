// Components.swift — small reusable pieces for the Model B dashboard.

import SwiftUI

/// (1.0.2) Nested corner radius rule, app-wide (mirrors Android
/// ui/theme/Theme.kt): inner radius = outer radius - padding.
/// Top-level cards are 24pt with 16pt padding, so panels, list boxes,
/// text fields and inner cards inside them are 8pt. The capsule menu
/// is 16pt with a 5pt inset, so its selected pill is 11pt. Fully
/// round pills and badges that do not hug a container corner keep
/// their own shape.
public enum UnbarRadius {
    /// Top-level cards (Home stat cards, server / subscription cards,
    /// About rows, the Add button).
    public static let outer: CGFloat = 24
    /// Panels, list boxes, text fields and inner cards inside a
    /// top-level card (24 - 16 padding).
    public static let inner: CGFloat = 8
    /// The Home status (hero) card. Android's is 30 because its
    /// VPN/Proxy switch (radius 10) sits 20 from the corner; iOS has
    /// no switch on the card, so it is a plain top-level card.
    public static let hero: CGFloat = 24
    /// Floating capsule menu and its selected pill (16 - 5 inset).
    public static let capsule: CGFloat = 16
    public static let capsulePill: CGFloat = 11
    /// Standard padding inside a top-level card.
    public static let cardPadding: CGFloat = 16
}

/// Shared fixed height for every Home stat card — the simple
/// cards' natural height — so all rows stay even. The richer
/// Quality and Test cards tighten their inner spacing to fit it.
public let statCardHeight: CGFloat = 78

/// One live-stat card (speed up/down, latency, data usage).
/// `icon` is an SF Symbol name; `customIcon` is a two-tone custom
/// icon (StatIcons.swift) — when set it replaces the SF Symbol.
public struct StatCard: View {
    public var title: String
    public var value: String
    public var icon: String
    public var customIcon: StatIconKind?

    public init(title: String, value: String, icon: String) {
        self.title = title
        self.value = value
        self.icon = icon
        self.customIcon = nil
    }

    public init(title: String, value: String, customIcon: StatIconKind) {
        self.title = title
        self.value = value
        self.icon = ""
        self.customIcon = customIcon
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 6) {
                if let customIcon = customIcon {
                    StatIconView(kind: customIcon, size: 14)
                } else {
                    Image(systemName: icon)
                        .font(.system(size: 14))
                        .foregroundColor(Color("BrandBlue"))
                }
                Text(title)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Text(value)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: statCardHeight)
        .background(Color("Surface"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.outer, style: .continuous))
    }
}

/// A labelled row used in forms where a plain Text label is not enough.
public struct NoteText: View {
    public var text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundColor(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The TCP-Mode-style pill switch (Android 1.0.19): a blue outline, a
/// light-blue track and a white knob. OFF is knob-left, ON is
/// knob-right with a blue-filled track. The switch is blue in BOTH
/// states — it never goes grey. Used for TCP Mode and for the
/// subscription auto-update switch.
public struct TCPModeSwitch: View {
    @Binding public var isOn: Bool

    public init(isOn: Binding<Bool>) {
        self._isOn = isOn
    }

    public var body: some View {
        Button(action: { isOn.toggle() }) {
            ZStack(alignment: isOn ? .trailing : .leading) {
                RoundedRectangle(cornerRadius: 16)
                    .fill(isOn ? Color("BrandBlue")
                          : Color("BrandBlue").opacity(0.25))
                    .frame(width: 51, height: 31)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(Color("BrandBlue"), lineWidth: 2)
                    )
                Circle()
                    .fill(Color.white)
                    .frame(width: 27, height: 27)
                    .shadow(radius: 1)
                    .padding(2)
            }
        }
        .buttonStyle(PlainButtonStyle())
        .accessibility(label: Text(isOn ? "On" : "Off"))
    }
}

/// A full settings row: label on the left, TCP-Mode-style switch on
/// the right. The switch itself carries the state; the row never uses
/// the standard grey toggle.
public struct TCPModeSwitchRow: View {
    public var label: String
    @Binding public var isOn: Bool

    public init(label: String, isOn: Binding<Bool>) {
        self.label = label
        self._isOn = isOn
    }

    public var body: some View {
        HStack(alignment: .center) {
            Text(label)
            Spacer()
            TCPModeSwitch(isOn: $isOn)
        }
    }
}
