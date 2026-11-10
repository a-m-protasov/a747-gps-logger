import A747Core
import Foundation

/// The local library: raw images of every download, kept on disk. Tracks and waypoints are
/// derived from all images together, so re-downloading never creates duplicates.
struct LibraryStore {
    struct Entry: Codable, Identifiable, Hashable {
        var id: UUID
        var file: String
        var downloadedAt: Date
        var flashID: UInt32
        var model: String
        var writeAddr: UInt32
    }

    let dir: URL
    private(set) var entries: [Entry] = []

    init(dir: URL? = nil) {
        let base = dir ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "A747 GPS Logger", directoryHint: .isDirectory)
        self.dir = base
        try? FileManager.default.createDirectory(at: base.appending(path: "images"), withIntermediateDirectories: true)
        if let d = try? Data(contentsOf: indexURL), let e = try? JSONDecoder().decode([Entry].self, from: d) {
            entries = e
        }
    }

    var indexURL: URL { dir.appending(path: "library.json") }
    func imageURL(_ e: Entry) -> URL { dir.appending(path: "images/\(e.file)") }

    /// The state the downloader needs for an incremental read: the newest download.
    var lastState: DownloadState? {
        guard let e = entries.max(by: { $0.downloadedAt < $1.downloadedAt }),
              let img = try? Data(contentsOf: imageURL(e)) else { return nil }
        return DownloadState(flashID: e.flashID, model: e.model, writeAddr: e.writeAddr, image: img)
    }

    /// Stores a download. An incremental download replaces the image it continued.
    mutating func add(_ state: DownloadState, replacingLast: Bool) throws {
        if replacingLast, let last = entries.max(by: { $0.downloadedAt < $1.downloadedAt }) {
            try? FileManager.default.removeItem(at: imageURL(last))
            entries.removeAll { $0.id == last.id }
        }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'_'HHmmss"
        let e = Entry(id: UUID(), file: "747-\(f.string(from: .now)).bin", downloadedAt: .now,
                      flashID: state.flashID, model: state.model, writeAddr: state.writeAddr)
        try state.image.write(to: imageURL(e), options: .atomic)
        entries.append(e)
        try save()
    }

    mutating func remove(_ e: Entry) throws {
        try? FileManager.default.removeItem(at: imageURL(e))
        entries.removeAll { $0.id == e.id }
        try save()
    }

    private func save() throws {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try enc.encode(entries).write(to: indexURL, options: .atomic)
    }

    /// Parses all images and merges them (protocol §4.3 across images).
    func load(now: Int64) -> (points: [LogPoint], waypoints: [LogPoint], problems: Int) {
        var points: [LogPoint] = [], wpts: [LogPoint] = []
        var problems = 0
        for e in entries {
            guard let img = try? Data(contentsOf: imageURL(e)),
                  let r = try? LogParser.parse(image: img, flashID: e.flashID, writeAddr: Int(e.writeAddr), now: now)
            else { problems += 1; continue }
            points += r.points
            wpts += r.waypoints
        }
        return (Self.dedupe(points), Self.dedupe(wpts), problems)
    }

    static func dedupe(_ pts: [LogPoint]) -> [LogPoint] {
        struct Key: Hashable { let t: Int64; let lat: Double; let lon: Double }
        var seen = Set<Key>()
        return pts.sorted { $0.utc < $1.utc }.filter { seen.insert(Key(t: $0.utc, lat: $0.lat, lon: $0.lon)).inserted }
    }
}
