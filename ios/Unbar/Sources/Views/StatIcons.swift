// StatIcons.swift — two-tone Home stat icons (blue + orange).
// The same artwork as the Android vectors (res/drawable/ic_stat_*.xml),
// drawn from the same 24x24 path data so both platforms match exactly.
// Palette is only BrandBlue and BrandOrange (1.0.2 Cloud: #1A5BAA /
// dark #6E9FDB and #F7853A / dark #F7954A, from the asset catalog).
// The timer, dns and storage icons are OUTLINE style, matching the new
// Android outline vectors: 1.8pt blue strokes, 2.2pt orange accent,
// round caps (same language as the magnifier-check icon). The other
// three (speed, dataUsage, swapHoriz) stay filled, as on Android.

import SwiftUI

/// The six two-tone Home stat icons.
public enum StatIconKind {
    case speed
    case dataUsage
    case storage
    case swapHoriz
    case dns
    case timer
}

/// Draws one two-tone Home stat icon at the given size (points).
/// The 24x24 artwork is scaled to `size`; the frame stays square.
public struct StatIconView: View {
    public var kind: StatIconKind
    public var size: CGFloat

    public init(kind: StatIconKind, size: CGFloat = 14) {
        self.kind = kind
        self.size = size
    }

    public var body: some View {
        switch kind {
        case .timer, .dns, .storage:
            StatIconOutline(kind: kind, size: size)
        default:
            ZStack {
                statIconPath(kind: kind, orange: false, size: size).fill(Color("BrandBlue"))
                statIconPath(kind: kind, orange: true, size: size).fill(Color("BrandOrange"))
            }
            .frame(width: size, height: size)
        }
    }
}

/// Outline Home stat icons (timer, dns, storage) — the same artwork as the
/// new Android outline vectors (ic_stat_timer.xml, ic_stat_dns.xml,
/// ic_stat_storage.xml): 1.8pt blue strokes, 2.2pt orange accent, round
/// caps, on the same 24x24 grid. Drawn as strokes (not fills).
private struct StatIconOutline: View {
    var kind: StatIconKind
    var size: CGFloat

    var body: some View {
        let s = size / 24
        let blue = Color("BrandBlue")
        let orange = Color("BrandOrange")
        ZStack {
            switch kind {
            case .timer:
                // Circle, centre (12, 13.5), r 7.5.
                Circle()
                    .stroke(blue, lineWidth: 1.8 * s)
                    .frame(width: 15 * s, height: 15 * s)
                    .position(x: 12 * s, y: 13.5 * s)
                // Stem + crown bar (orange accent).
                strokeLine(from: (12, 2.5), to: (12, 6), color: blue, width: 1.8 * s)
                strokeLine(from: (9.5, 2.5), to: (14.5, 2.5), color: orange, width: 2.2 * s)
                // Hands: minute up, hour down-right; centre dot.
                strokeLine(from: (12, 13.5), to: (12, 8.5), color: blue, width: 1.8 * s)
                strokeLine(from: (12, 13.5), to: (15.8, 15.8), color: blue, width: 1.8 * s)
                Circle().fill(blue)
                    .frame(width: 2.2 * s, height: 2.2 * s)
                    .position(x: 12 * s, y: 13.5 * s)
            case .dns:
                // Two server bars: blue top, orange bottom; status dots.
                RoundedRectangle(cornerRadius: 2 * s)
                    .stroke(blue, lineWidth: 1.8 * s)
                    .frame(width: 18 * s, height: 8 * s)
                    .position(x: 12 * s, y: 7 * s)
                RoundedRectangle(cornerRadius: 2 * s)
                    .stroke(orange, lineWidth: 2.2 * s)
                    .frame(width: 18 * s, height: 8 * s)
                    .position(x: 12 * s, y: 17 * s)
                Circle().fill(blue)
                    .frame(width: 2.8 * s, height: 2.8 * s)
                    .position(x: 7 * s, y: 7 * s)
                Circle().fill(orange)
                    .frame(width: 2.8 * s, height: 2.8 * s)
                    .position(x: 7 * s, y: 17 * s)
            case .storage:
                // Three rows: blue, orange, blue; dots at the left.
                strokeLine(from: (8, 6), to: (21, 6), color: blue, width: 1.8 * s)
                strokeLine(from: (8, 12), to: (21, 12), color: orange, width: 2.2 * s)
                strokeLine(from: (8, 18), to: (21, 18), color: blue, width: 1.8 * s)
                Circle().fill(blue)
                    .frame(width: 2.8 * s, height: 2.8 * s)
                    .position(x: 4.5 * s, y: 6 * s)
                Circle().fill(orange)
                    .frame(width: 2.8 * s, height: 2.8 * s)
                    .position(x: 4.5 * s, y: 12 * s)
                Circle().fill(blue)
                    .frame(width: 2.8 * s, height: 2.8 * s)
                    .position(x: 4.5 * s, y: 18 * s)
            default:
                EmptyView()
            }
        }
        .frame(width: size, height: size)
    }

    /// A two-point stroked line on the 24x24 grid, with round caps.
    private func strokeLine(from: (CGFloat, CGFloat), to: (CGFloat, CGFloat), color: Color, width: CGFloat) -> some View {
        let s = size / 24
        return Path { p in
            p.move(to: CGPoint(x: from.0 * s, y: from.1 * s))
            p.addLine(to: CGPoint(x: to.0 * s, y: to.1 * s))
        }
        .stroke(color, style: StrokeStyle(lineWidth: width, lineCap: .round))
    }
}

private func statIconPath(kind: StatIconKind, orange: Bool, size: CGFloat = 14) -> Path {
    var path = Path()
    let s = size / 24
    switch kind {
    case .speed:
        if orange {
            path.move(to: CGPoint(x: 10.59 * s, y: 12.59 * s))
            path.addCurve(to: CGPoint(x: 10.59 * s, y: 15.42 * s), control1: CGPoint(x: 9.814 * s, y: 13.366 * s), control2: CGPoint(x: 9.814 * s, y: 14.644 * s))
            path.addCurve(to: CGPoint(x: 13.42 * s, y: 15.42 * s), control1: CGPoint(x: 11.366 * s, y: 16.196 * s), control2: CGPoint(x: 12.644 * s, y: 16.196 * s))
            path.addLine(to: CGPoint(x: 19.08 * s, y: 6.93 * s))
            path.closeSubpath()
        } else {
            path.move(to: CGPoint(x: 19.46 * s, y: 10 * s))
            path.addCurve(to: CGPoint(x: 19.39 * s, y: 11 * s), control1: CGPoint(x: 19.259 * s, y: 10.296 * s), control2: CGPoint(x: 19.233 * s, y: 10.679 * s))
            path.addCurve(to: CGPoint(x: 19.91 * s, y: 12.81 * s), control1: CGPoint(x: 19.638 * s, y: 11.579 * s), control2: CGPoint(x: 19.813 * s, y: 12.187 * s))
            path.addCurve(to: CGPoint(x: 19.22 * s, y: 17.54 * s), control1: CGPoint(x: 20.167 * s, y: 14.42 * s), control2: CGPoint(x: 19.927 * s, y: 16.071 * s))
            path.addCurve(to: CGPoint(x: 18.33 * s, y: 18.07 * s), control1: CGPoint(x: 19.045 * s, y: 17.868 * s), control2: CGPoint(x: 18.702 * s, y: 18.073 * s))
            path.addLine(to: CGPoint(x: 5.68 * s, y: 18.07 * s))
            path.addCurve(to: CGPoint(x: 4.79 * s, y: 17.53 * s), control1: CGPoint(x: 5.306 * s, y: 18.071 * s), control2: CGPoint(x: 4.962 * s, y: 17.862 * s))
            path.addCurve(to: CGPoint(x: 5.46 * s, y: 9.336 * s), control1: CGPoint(x: 3.488 * s, y: 14.888 * s), control2: CGPoint(x: 3.746 * s, y: 11.732 * s))
            path.addCurve(to: CGPoint(x: 13 * s, y: 6.06 * s), control1: CGPoint(x: 7.175 * s, y: 6.941 * s), control2: CGPoint(x: 10.079 * s, y: 5.679 * s))
            path.addCurve(to: CGPoint(x: 15.11 * s, y: 6.62 * s), control1: CGPoint(x: 13.726 * s, y: 6.145 * s), control2: CGPoint(x: 14.437 * s, y: 6.334 * s))
            path.addCurve(to: CGPoint(x: 16.11 * s, y: 6.55 * s), control1: CGPoint(x: 15.431 * s, y: 6.777 * s), control2: CGPoint(x: 15.814 * s, y: 6.751 * s))
            path.addCurve(to: CGPoint(x: 16.555 * s, y: 5.619 * s), control1: CGPoint(x: 16.419 * s, y: 6.346 * s), control2: CGPoint(x: 16.591 * s, y: 5.987 * s))
            path.addCurve(to: CGPoint(x: 15.94 * s, y: 4.79 * s), control1: CGPoint(x: 16.52 * s, y: 5.25 * s), control2: CGPoint(x: 16.282 * s, y: 4.931 * s))
            path.addCurve(to: CGPoint(x: 4.514 * s, y: 7.349 * s), control1: CGPoint(x: 11.988 * s, y: 3.095 * s), control2: CGPoint(x: 7.366 * s, y: 4.131 * s))
            path.addCurve(to: CGPoint(x: 3.35 * s, y: 19 * s), control1: CGPoint(x: 1.663 * s, y: 10.568 * s), control2: CGPoint(x: 1.192 * s, y: 15.281 * s))
            path.addCurve(to: CGPoint(x: 5.07 * s, y: 20 * s), control1: CGPoint(x: 3.705 * s, y: 19.614 * s), control2: CGPoint(x: 4.361 * s, y: 19.996 * s))
            path.addLine(to: CGPoint(x: 18.92 * s, y: 20 * s))
            path.addCurve(to: CGPoint(x: 20.66 * s, y: 19 * s), control1: CGPoint(x: 19.636 * s, y: 20.003 * s), control2: CGPoint(x: 20.302 * s, y: 19.62 * s))
            path.addCurve(to: CGPoint(x: 21.21 * s, y: 10.11 * s), control1: CGPoint(x: 22.225 * s, y: 16.288 * s), control2: CGPoint(x: 22.428 * s, y: 12.994 * s))
            path.addCurve(to: CGPoint(x: 20.368 * s, y: 9.537 * s), control1: CGPoint(x: 21.055 * s, y: 9.78 * s), control2: CGPoint(x: 20.731 * s, y: 9.56 * s))
            path.addCurve(to: CGPoint(x: 19.46 * s, y: 10 * s), control1: CGPoint(x: 20.004 * s, y: 9.514 * s), control2: CGPoint(x: 19.655 * s, y: 9.692 * s))
            path.closeSubpath()
        }
    case .dataUsage:
        if orange {
            path.move(to: CGPoint(x: 13 * s, y: 3.87 * s))
            path.addLine(to: CGPoint(x: 13 * s, y: 3.89 * s))
            path.addCurve(to: CGPoint(x: 14.08 * s, y: 5.32 * s), control1: CGPoint(x: 13 * s, y: 4.56 * s), control2: CGPoint(x: 13.45 * s, y: 5.12 * s))
            path.addCurve(to: CGPoint(x: 19 * s, y: 12 * s), control1: CGPoint(x: 16.93 * s, y: 6.21 * s), control2: CGPoint(x: 19 * s, y: 8.86 * s))
            path.addCurve(to: CGPoint(x: 18.83 * s, y: 13.49 * s), control1: CGPoint(x: 19 * s, y: 12.52 * s), control2: CGPoint(x: 18.94 * s, y: 13.01 * s))
            path.addCurve(to: CGPoint(x: 19.52 * s, y: 15.13 * s), control1: CGPoint(x: 18.69 * s, y: 14.13 * s), control2: CGPoint(x: 18.95 * s, y: 14.79 * s))
            path.addLine(to: CGPoint(x: 19.53 * s, y: 15.14 * s))
            path.addCurve(to: CGPoint(x: 21.74 * s, y: 14.23 * s), control1: CGPoint(x: 20.39 * s, y: 15.64 * s), control2: CGPoint(x: 21.51 * s, y: 15.19 * s))
            path.addCurve(to: CGPoint(x: 22 * s, y: 12 * s), control1: CGPoint(x: 21.91 * s, y: 13.51 * s), control2: CGPoint(x: 22 * s, y: 12.76 * s))
            path.addCurve(to: CGPoint(x: 14.92 * s, y: 2.43 * s), control1: CGPoint(x: 22 * s, y: 7.5 * s), control2: CGPoint(x: 19.02 * s, y: 3.68 * s))
            path.addCurve(to: CGPoint(x: 13 * s, y: 3.87 * s), control1: CGPoint(x: 13.97 * s, y: 2.14 * s), control2: CGPoint(x: 13 * s, y: 2.87 * s))
            path.closeSubpath()
        } else {
            path.move(to: CGPoint(x: 10.94 * s, y: 18.92 * s))
            path.addCurve(to: CGPoint(x: 5.08 * s, y: 13.08 * s), control1: CGPoint(x: 7.95 * s, y: 18.49 * s), control2: CGPoint(x: 5.52 * s, y: 16.06 * s))
            path.addCurve(to: CGPoint(x: 9.91 * s, y: 5.32 * s), control1: CGPoint(x: 4.54 * s, y: 9.48 * s), control2: CGPoint(x: 6.74 * s, y: 6.31 * s))
            path.addCurve(to: CGPoint(x: 11 * s, y: 3.89 * s), control1: CGPoint(x: 10.55 * s, y: 5.13 * s), control2: CGPoint(x: 11 * s, y: 4.56 * s))
            path.addLine(to: CGPoint(x: 11 * s, y: 3.87 * s))
            path.addCurve(to: CGPoint(x: 9.07 * s, y: 2.43 * s), control1: CGPoint(x: 11 * s, y: 2.87 * s), control2: CGPoint(x: 10.03 * s, y: 2.14 * s))
            path.addCurve(to: CGPoint(x: 2.09 * s, y: 13.39 * s), control1: CGPoint(x: 4.56 * s, y: 3.81 * s), control2: CGPoint(x: 1.41 * s, y: 8.29 * s))
            path.addCurve(to: CGPoint(x: 10.6 * s, y: 21.9 * s), control1: CGPoint(x: 2.68 * s, y: 17.77 * s), control2: CGPoint(x: 6.22 * s, y: 21.31 * s))
            path.addCurve(to: CGPoint(x: 18.73 * s, y: 19.37 * s), control1: CGPoint(x: 13.74 * s, y: 22.32 * s), control2: CGPoint(x: 16.64 * s, y: 21.29 * s))
            path.addCurve(to: CGPoint(x: 18.47 * s, y: 16.98 * s), control1: CGPoint(x: 19.47 * s, y: 18.69 * s), control2: CGPoint(x: 19.34 * s, y: 17.48 * s))
            path.addCurve(to: CGPoint(x: 16.67 * s, y: 17.2 * s), control1: CGPoint(x: 17.89 * s, y: 16.64 * s), control2: CGPoint(x: 17.17 * s, y: 16.75 * s))
            path.addCurve(to: CGPoint(x: 10.94 * s, y: 18.92 * s), control1: CGPoint(x: 15.2 * s, y: 18.54 * s), control2: CGPoint(x: 13.16 * s, y: 19.25 * s))
            path.closeSubpath()
        }
    case .swapHoriz:
        if orange {
            path.move(to: CGPoint(x: 6.14 * s, y: 11.86 * s))
            path.addLine(to: CGPoint(x: 3.36 * s, y: 14.65 * s))
            path.addCurve(to: CGPoint(x: 3.36 * s, y: 15.36 * s), control1: CGPoint(x: 3.17 * s, y: 14.85 * s), control2: CGPoint(x: 3.17 * s, y: 15.16 * s))
            path.addLine(to: CGPoint(x: 6.14 * s, y: 18.15 * s))
            path.addCurve(to: CGPoint(x: 6.99 * s, y: 17.8 * s), control1: CGPoint(x: 6.45 * s, y: 18.47 * s), control2: CGPoint(x: 6.99 * s, y: 18.24 * s))
            path.addLine(to: CGPoint(x: 6.99 * s, y: 16 * s))
            path.addLine(to: CGPoint(x: 13 * s, y: 16 * s))
            path.addCurve(to: CGPoint(x: 14 * s, y: 15 * s), control1: CGPoint(x: 13.55 * s, y: 16 * s), control2: CGPoint(x: 14 * s, y: 15.55 * s))
            path.addCurve(to: CGPoint(x: 13 * s, y: 14 * s), control1: CGPoint(x: 14 * s, y: 14.45 * s), control2: CGPoint(x: 13.55 * s, y: 14 * s))
            path.addLine(to: CGPoint(x: 6.99 * s, y: 14 * s))
            path.addLine(to: CGPoint(x: 6.99 * s, y: 12.21 * s))
            path.addCurve(to: CGPoint(x: 6.14 * s, y: 11.86 * s), control1: CGPoint(x: 6.99 * s, y: 11.76 * s), control2: CGPoint(x: 6.45 * s, y: 11.54 * s))
            path.closeSubpath()
        } else {
            path.move(to: CGPoint(x: 20.65 * s, y: 8.65 * s))
            path.addLine(to: CGPoint(x: 17.87 * s, y: 5.86 * s))
            path.addCurve(to: CGPoint(x: 17.02 * s, y: 6.21 * s), control1: CGPoint(x: 17.56 * s, y: 5.54 * s), control2: CGPoint(x: 17.02 * s, y: 5.77 * s))
            path.addLine(to: CGPoint(x: 17.02 * s, y: 8 * s))
            path.addLine(to: CGPoint(x: 11 * s, y: 8 * s))
            path.addCurve(to: CGPoint(x: 10 * s, y: 9 * s), control1: CGPoint(x: 10.45 * s, y: 8 * s), control2: CGPoint(x: 10 * s, y: 8.45 * s))
            path.addCurve(to: CGPoint(x: 11 * s, y: 10 * s), control1: CGPoint(x: 10 * s, y: 9.55 * s), control2: CGPoint(x: 10.45 * s, y: 10 * s))
            path.addLine(to: CGPoint(x: 17.01 * s, y: 10 * s))
            path.addLine(to: CGPoint(x: 17.01 * s, y: 11.79 * s))
            path.addCurve(to: CGPoint(x: 17.86 * s, y: 12.14 * s), control1: CGPoint(x: 17.01 * s, y: 12.24 * s), control2: CGPoint(x: 17.55 * s, y: 12.46 * s))
            path.addLine(to: CGPoint(x: 20.64 * s, y: 9.35 * s))
            path.addCurve(to: CGPoint(x: 20.65 * s, y: 8.65 * s), control1: CGPoint(x: 20.84 * s, y: 9.16 * s), control2: CGPoint(x: 20.84 * s, y: 8.84 * s))
            path.closeSubpath()
        }
    case .timer, .dns, .storage:
        // Outline icons are drawn by StatIconOutline and never reach
        // this filled-path function; empty path by design.
        break
    }
    return path
}

