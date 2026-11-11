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
    /// The files for an export: one per track, and the waypoints together in one more file
    /// (BIN is always the one raw image).
    static func files(_ format: ExportFormat, items: [LibraryItem], model: AppModel) throws -> [(data: Data, name: String)] {
        let ex = model.exporter
        if format == .bin {
            guard let e = model.library.entries.max(by: { $0.downloadedAt < $1.downloadedAt }) else {
                throw CocoaError(.fileNoSuchFile)
            }
            return [(try Data(contentsOf: model.library.imageURL(e)), e.file.replacingOccurrences(of: ".bin", with: ""))]
        }
        let tracks = items.compactMap { if case .track(let t) = $0 { t } else { nil } }.sorted { $0.stats.startUTC < $1.stats.startUTC }
        let wpts = items.compactMap { if case .waypoint(let w) = $0 { w } else { nil } }.sorted { $0.point.utc < $1.point.utc }
        var out: [(Data, String)] = tracks.map { t in
            let text = switch format {
            case .gpx: ex.gpx(tracks: [t.points])
            case .kml: ex.kml(tracks: [t.points], waypoints: [])
            default: ex.csv(tracks: [t.points])
            }
            return (Data(text.utf8), fileName(t.name, t.customName))
        }
        if !wpts.isEmpty {
            let pts = wpts.map(\.point)
            let text = switch format {
            case .gpx: ex.gpx(waypoints: pts)
            case .kml: ex.kml(tracks: [], waypoints: pts)
            default: ex.csv(waypoints: pts)
            }
            let name: String
            if wpts.count == 1 {
                name = fileName(wpts[0].name, wpts[0].customName)
            } else {
                let a = wpts.first!.name.prefix(10), b = wpts.last!.name.prefix(10)
                name = a == b ? "a747-waypoints-\(a)" : "a747-waypoints-\(a)_\(b)"
            }
            out.append((Data(text.utf8), name))
        }
        return out
    }

    /// "a747-2026-11-08_17-33" plus the user's name, without characters file systems reject.
    static func fileName(_ defaultName: String, _ custom: String?) -> String {
        var n = "a747-" + defaultName.replacingOccurrences(of: " ", with: "_").replacingOccurrences(of: ":", with: "-")
        if let c = custom {
            let clean = c.map { "/\\:*?\"<>|".contains($0) ? "-" : $0 }
            n += " " + String(clean).trimmingCharacters(in: .whitespaces)
        }
        return n
    }

    static func temporaryFile(_ data: Data, name: String, ext: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appending(path: "a747-share", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appending(path: "\(name).\(ext)")
        try data.write(to: url, options: .atomic)
        return url
    }

    /// One file: the save panel. Several: a folder to put them in (iCloud Drive or anywhere);
    /// existing files are not overwritten, the new ones get a number.
    static func save(_ files: [(data: Data, name: String)], format: ExportFormat) {
        if files.count == 1, let f = files.first {
            let panel = NSSavePanel()
            panel.nameFieldStringValue = "\(f.name).\(format.rawValue)"
            panel.allowedContentTypes = [format.type]
            panel.canCreateDirectories = true
            guard panel.runModal() == .OK, let url = panel.url else { return }
            do { try f.data.write(to: url, options: .atomic) } catch { NSAlert(error: error).runModal() }
            return
        }
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = String(localized: "Save")
        guard panel.runModal() == .OK, let dir = panel.url else { return }
        do {
            for f in files {
                var url = dir.appending(path: "\(f.name).\(format.rawValue)")
                var n = 2
                while FileManager.default.fileExists(atPath: url.path) {
                    url = dir.appending(path: "\(f.name) \(n).\(format.rawValue)")
                    n += 1
                }
                try f.data.write(to: url, options: .atomic)
            }
        } catch {
            NSAlert(error: error).runModal()
        }
    }

    /// System share menu (Mail, Messages, AirDrop…), anchored to the given view.
    static func share(_ urls: [URL], from view: NSView?) {
        let picker = NSSharingServicePicker(items: urls)
        if let v = view ?? NSApp.keyWindow?.contentView {
            picker.show(relativeTo: .zero, of: v, preferredEdge: .minY)
        }
    }
}

/// "Export" menu used by the track and waypoint lists.
struct ExportMenu: View {
    let title: LocalizedStringKey
    let formats: [ExportFormat]
    let make: (ExportFormat) throws -> [(data: Data, name: String)]

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
            let files = try make(f)
            if share {
                ExportBuilder.share(try files.map { try ExportBuilder.temporaryFile($0.data, name: $0.name, ext: f.rawValue) }, from: nil)
            } else {
                ExportBuilder.save(files, format: f)
            }
        } catch {
            NSAlert(error: error).runModal()
        }
    }
}
