// ResolverImportView.swift — (1.0.2) Import mode of Settings > Resolvers.
//
// Port of Android ui/screens/ResolverImportBlock.kt: pick a plain-text
// file with the system file picker (.fileImporter, security-scoped
// access, no extra permission), clean it with the same rules as the
// Custom field plus de-duplication and sorting (ResolverFileImport),
// store the result in its own list (Custom's list is never touched)
// and show what was fixed. Scanning is the unchanged shared scan row
// below it in the Resolvers section (the same 3-stage stepper as
// Custom).
//
// The block is one Form row (a VStack), so the single .fileImporter
// is attached to exactly one view; its buttons are borderless so each
// takes its own tap inside the row.

import SwiftUI
import UniformTypeIdentifiers

struct ResolverImportView: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var vpn: VPNManager
    @EnvironmentObject private var loc: LocalizationManager

    @State private var showPicker = false
    @State private var reading = false
    // What the last import fixed (this session only).
    @State private var report: ResolverImportResult?
    @State private var importError: ImportError?

    private enum ImportError {
        case tooLarge, unreadable, empty
    }
    @State private var showList = false

    /// Only the first 200 entries are drawn; the count of the rest is
    /// shown below them.
    private let listPreviewLimit = 200

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if settings.importedResolvers.isEmpty {
                NoteText(loc.tr("import.note"))
                NoteText(loc.tr("import.note_custom_kept"))
                Button(action: { showPicker = true }) {
                    Text(loc.tr("import.choose_file"))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .frame(height: 40, alignment: .center)
                        .background(Capsule().fill(Color("BrandBlue")))
                }
                .buttonStyle(BorderlessButtonStyle())
                .disabled(reading)
            } else {
                fileCard
                actionRow
                if showList {
                    listBox
                }
                warnings
            }
            if reading {
                HStack(alignment: .center, spacing: 8) {
                    ActivityIndicator(color: UIColor(named: "BrandBlue"))
                        .scaleEffect(0.7)
                        .frame(width: 16, height: 16)
                    Text(loc.tr("import.reading"))
                        .font(.footnote)
                }
            }
            if let importError = importError {
                Text(errorText(importError))
                    .font(.footnote)
                    .foregroundColor(Color("BrandOrange"))
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let report = report {
                reportView(report)
            }
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .fileImporter(isPresented: $showPicker,
                      allowedContentTypes: [.plainText],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let url):
                readFile(url)
            case .failure:
                importError = .unreadable
            }
        }
    }

    // MARK: - Pieces

    private var addresses: Int {
        ResolverFileImport.addressCount(settings.importedResolvers)
    }

    /// File card: icon, file name, entry/address count and date.
    /// Inner panel inside the Settings card: 8pt radius.
    private var fileCard: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: "doc.text")
                .font(.system(size: 22))
                .foregroundColor(Color("BrandBlue"))
                .frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(settings.importFileName.isEmpty ? "resolvers.txt" : settings.importFileName)
                    .font(.system(size: 16, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(fileMeta)
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Color("SurfaceVariant"))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.inner, style: .continuous))
    }

    private var fileMeta: String {
        var meta = loc.tr("import.file_meta", settings.importedResolvers.count, addresses)
        if settings.importedAtMs > 0 {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: loc.effectiveCode)
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            let date = Date(timeIntervalSince1970: TimeInterval(settings.importedAtMs) / 1000)
            meta += " · " + formatter.string(from: date)
        }
        return meta
    }

    /// Replace / View list / Clear. Borderless buttons, so each one
    /// takes its own tap inside the single Form row.
    private var actionRow: some View {
        HStack(alignment: .center, spacing: 8) {
            outlinedButton(loc.tr("import.replace_file"), color: Color("BrandBlue")) {
                showPicker = true
            }
            .disabled(reading)
            outlinedButton(showList ? loc.tr("import.hide_list") : loc.tr("import.view_list"),
                           color: Color("BrandBlue")) {
                showList.toggle()
            }
            outlinedButton(loc.tr("import.clear"), color: Color("BrandOrange")) {
                report = nil
                importError = nil
                showList = false
                vpn.clearScanResults()
                settings.clearImportedResolvers()
            }
            Spacer(minLength: 0)
        }
    }

    private func outlinedButton(_ title: String, color: Color,
                                action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .lineLimit(1)
                .foregroundColor(color)
                .padding(.horizontal, 12)
                .frame(height: 34, alignment: .center)
                .overlay(
                    Capsule().stroke(Color("BrandBlue").opacity(0.4), lineWidth: 1)
                )
        }
        .buttonStyle(BorderlessButtonStyle())
    }

    /// The first 200 entries, monospaced, scrolling in an 8pt panel.
    private var listBox: some View {
        let entries = settings.importedResolvers
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 2) {
                ForEach(Array(entries.prefix(listPreviewLimit)), id: \.self) { entry in
                    Text(entry)
                        .font(.system(size: 13, design: .monospaced))
                }
                if entries.count > listPreviewLimit {
                    Text(loc.tr("import.list_more", entries.count - listPreviewLimit))
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .frame(maxHeight: 180)
        .background(Color("SurfaceVariant").opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: UnbarRadius.inner, style: .continuous))
        // IPs and ranges read left-to-right in the Persian locale too.
        .environment(\.layoutDirection, .leftToRight)
    }

    private var warnings: some View {
        Group {
            if addresses > ResolverFileImport.scanCap {
                warningText(loc.tr("import.over_cap", addresses, ResolverFileImport.scanCap))
            }
            if !oversize.isEmpty {
                warningText(loc.tr("settings.custom_resolvers_range_too_large",
                                   oversize.joined(separator: ", ")))
            }
        }
    }

    private var oversize: [String] {
        ResolverNormalization.oversizeResolverRanges(settings.importedResolvers.joined(separator: ","))
    }

    private func warningText(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundColor(Color("BrandOrange"))
            .fixedSize(horizontal: false, vertical: true)
    }

    private func reportView(_ r: ResolverImportResult) -> some View {
        reportBody(r, lines: reportLines(r))
    }

    @ViewBuilder
    private func reportBody(_ r: ResolverImportResult, lines: [String]) -> some View {
        if !lines.isEmpty || !r.ignored.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                if !lines.isEmpty {
                    Text(loc.tr("import.fixed_title", r.fixedCount))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(Color("BrandBlue"))
                    ForEach(lines, id: \.self) { line in
                        Text("• " + line)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !r.ignored.isEmpty {
                    warningText(loc.tr("import.ignored", r.ignored.count,
                                       r.ignored.prefix(5).joined(separator: ", ")))
                }
            }
        }
    }

    private func reportLines(_ r: ResolverImportResult) -> [String] {
        var lines: [String] = []
        if r.cleaned > 0 { lines.append(loc.tr("import.fix_cleaned", r.cleaned)) }
        if r.duplicates > 0 { lines.append(loc.tr("import.fix_duplicates", r.duplicates)) }
        if r.insideRange > 0 { lines.append(loc.tr("import.fix_inside_range", r.insideRange)) }
        if r.rangesCorrected > 0 { lines.append(loc.tr("import.fix_ranges", r.rangesCorrected)) }
        if r.dashConverted > 0 { lines.append(loc.tr("import.fix_dash", r.dashConverted)) }
        return lines
    }

    private func errorText(_ error: ImportError) -> String {
        switch error {
        case .tooLarge: return loc.tr("import.error_too_large")
        case .unreadable: return loc.tr("import.error_unreadable")
        case .empty: return loc.tr("import.error_empty")
        }
    }

    // MARK: - Reading

    private func readFile(_ url: URL) {
        reading = true
        importError = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let read = ResolverFileImport.read(url: url)
            DispatchQueue.main.async {
                reading = false
                switch read {
                case .failure(.tooLarge):
                    importError = .tooLarge
                case .failure(.unreadable):
                    importError = .unreadable
                case .success(let ok):
                    report = ok.result
                    if ok.result.entries.isEmpty {
                        // Nothing usable: keep the previous list.
                        importError = .empty
                    } else {
                        showList = false
                        // Scan results belong to the previous list.
                        vpn.clearScanResults()
                        settings.setImportedResolvers(ok.result.entries, fileName: ok.fileName)
                    }
                }
            }
        }
    }
}
