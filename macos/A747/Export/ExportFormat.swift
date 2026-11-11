import A747Core
import SwiftUI
import UniformTypeIdentifiers

enum ExportFormat: String, CaseIterable, Identifiable {
    case gpx, kml, csv, bin
    var id: String { rawValue }
    var title: String { rawValue.uppercased() }

    var type: UTType {
        switch self {
        case .gpx: UTType(filenameExtension: "gpx") ?? .xml
        case .kml: UTType(filenameExtension: "kml") ?? .xml
        case .csv: .commaSeparatedText
        case .bin: .data
        }
    }
}

/// Builds export files into a temporary folder (for sharing) or returns their content.
@MainActor
enum ExportBuilder {
    /// "a747-<first day>_<last day>" (local dates of the items).
    static func baseName(_ items: [LibraryItem], model: AppModel) -> String {
        guard let first = items.min(by: { $0.utc < $1.utc }), let last = items.max(by: { $0.utc < $1.utc }) else { return "a747" }
        let ex = model.exporter
        func day(_ i: LibraryItem) -> Substring {
            switch i {
            case .track(let t): ex.name(t.points[0]).prefix(10)
            case .waypoint(let w): ex.name(w.point).prefix(10)
            }
        }
        let a = day(first), b = day(last)
        return a == b ? "a747-\(a)" : "a747-\(a)_\(b)"
    }

    /// Tracks and waypoints in one file. CSV has no place for waypoints next to tracks, so there
    /// they become one-point tracks named after the waypoint.
    static func content(_ format: ExportFormat, items: [LibraryItem], model: AppModel) throws -> Data {
        let ex = model.exporter
        let tracks = items.compactMap { if case .track(let t) = $0 { t.points } else { nil } }.sorted { $0[0].utc < $1[0].utc }
        let wpts = items.compactMap { if case .waypoint(let w) = $0 { w.point } else { nil } }.sorted { $0.utc < $1.utc }
        switch format {
        case .gpx: return Data(ex.gpx(tracks: tracks, waypoints: wpts).utf8)
        case .kml: return Data(ex.kml(tracks: tracks, waypoints: wpts).utf8)
        case .csv:
            return Data((tracks.isEmpty ? ex.csv(waypoints: wpts) : ex.csv(tracks: tracks + wpts.map { [$0] })).utf8)
        case .bin:
            guard let e = model.library.entries.max(by: { $0.downloadedAt < $1.downloadedAt }) else {
                throw CocoaError(.fileNoSuchFile)
            }
            return try Data(contentsOf: model.library.imageURL(e))
        }
    }

    static func temporaryFile(_ data: Data, name: String, ext: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appending(path: "a747-share", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appending(path: "\(name).\(ext)")
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Save panel (iCloud Drive and any other location).
    static func save(_ data: Data, name: String, format: ExportFormat) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "\(name).\(format.rawValue)"
        panel.allowedContentTypes = [format.type]
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { try data.write(to: url, options: .atomic) } catch { NSAlert(error: error).runModal() }
    }

    /// System share menu (Mail, Messages, AirDrop…), anchored to the given view.
    static func share(_ url: URL, from view: NSView?) {
        let picker = NSSharingServicePicker(items: [url])
        if let v = view ?? NSApp.keyWindow?.contentView {
            picker.show(relativeTo: .zero, of: v, preferredEdge: .minY)
        }
    }
}

/// "Export" menu used by the track and waypoint lists.
struct ExportMenu: View {
    let title: LocalizedStringKey
    let formats: [ExportFormat]
    let make: (ExportFormat) throws -> (Data, String)

    var body: some View {
        Menu {
            Section("Save as") {
                ForEach(formats) { f in
                    Button(f.title) { run(f, share: false) }
                }
            }
            Section("Share") {
                ForEach(formats) { f in
                    Button(f.title) { run(f, share: true) }
                }
            }
        } label: {
            Label(title, systemImage: "square.and.arrow.up")
        }
    }

    private func run(_ f: ExportFormat, share: Bool) {
        do {
            let (data, name) = try make(f)
            if share {
                ExportBuilder.share(try ExportBuilder.temporaryFile(data, name: name, ext: f.rawValue), from: nil)
            } else {
                ExportBuilder.save(data, name: name, format: f)
            }
        } catch {
            NSAlert(error: error).runModal()
        }
    }
}
