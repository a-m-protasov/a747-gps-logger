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
    static func baseName(_ tracks: [Track]) -> String {
        guard let first = tracks.last, let last = tracks.first else { return "a747" }
        let a = first.name.prefix(10), b = last.name.prefix(10)
        return a == b ? "a747-\(a)" : "a747-\(a)_\(b)"
    }

    static func content(_ format: ExportFormat, tracks: [Track], model: AppModel) throws -> Data {
        let ex = model.exporter
        let pts = tracks.sorted { $0.stats.startUTC < $1.stats.startUTC }.map(\.points)
        switch format {
        case .gpx: return Data(ex.gpx(tracks: pts).utf8)
        case .kml: return Data(ex.kml(tracks: pts, waypoints: []).utf8)
        case .csv: return Data(ex.csv(tracks: pts).utf8)
        case .bin:
            guard let e = model.library.entries.max(by: { $0.downloadedAt < $1.downloadedAt }) else {
                throw CocoaError(.fileNoSuchFile)
            }
            return try Data(contentsOf: model.library.imageURL(e))
        }
    }

    static func waypointContent(_ format: ExportFormat, _ wpts: [Waypoint], model: AppModel) -> Data {
        let ex = model.exporter
        let pts = wpts.map(\.point).sorted { $0.utc < $1.utc }
        switch format {
        case .gpx: return Data(ex.gpx(waypoints: pts).utf8)
        case .kml: return Data(ex.kml(tracks: [], waypoints: pts).utf8)
        case .csv, .bin: return Data(ex.csv(waypoints: pts).utf8)
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
