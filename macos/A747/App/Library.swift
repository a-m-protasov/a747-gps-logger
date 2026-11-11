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

    // MARK: Track files

    /// Tracks imported from files or recorded on a phone: one track (or a set of waypoints) per
    /// file in `tracks/`, in the PointsFile format. Names start with `import-` or `phone-`.
    var tracksDir: URL { dir.appending(path: "tracks", directoryHint: .isDirectory) }

    struct TrackFile {
        let url: URL
        let track: [LogPoint]
        let waypoints: [LogPoint]
        var isPhone: Bool { url.lastPathComponent.hasPrefix("phone-") }
    }

    func loadFiles() -> [TrackFile] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: tracksDir, includingPropertiesForKeys: nil)) ?? []
        return urls.filter { $0.pathExtension == "csv" }.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap { u in
            guard let text = try? String(contentsOf: u, encoding: .utf8) else { return nil }
            let (t, w) = PointsFile.decode(text)
            return TrackFile(url: u, track: t, waypoints: w)
        }
    }

    /// Writes one file; returns its URL.
    @discardableResult
    func addFile(track: [LogPoint] = [], waypoints: [LogPoint] = []) throws -> URL {
        try FileManager.default.createDirectory(at: tracksDir, withIntermediateDirectories: true)
        let first = (track.first ?? waypoints.first)?.utc ?? 0
        let tag = UUID().uuidString.prefix(8).lowercased()
        let url = tracksDir.appending(path: "import-\(first)-\(tag).csv")
        try Data(PointsFile.encode(track: track, waypoints: waypoints).utf8).write(to: url, options: .atomic)
        return url
    }

    static func dedupe(_ pts: [LogPoint]) -> [LogPoint] {
        struct Key: Hashable { let t: Int64; let lat: Double; let lon: Double }
        var seen = Set<Key>()
        return pts.sorted { $0.utc < $1.utc }.filter { seen.insert(Key(t: $0.utc, lat: $0.lat, lon: $0.lon)).inserted }
    }
}

/// What the user changed on top of the raw downloads: names, and deleted tracks and waypoints.
/// Deletions are time ranges, so a deleted track stays hidden after later downloads too.
struct LibraryEdits: Codable {
    /// Names keyed by the UTC time of a track's first point or of a waypoint.
    var names: [Int64: String] = [:]
    /// Deleted tracks as [first, last] UTC times.
    var deletedTracks: [[Int64]] = []
    var deletedWaypoints: Set<Int64> = []

    static func load(_ dir: URL) -> LibraryEdits {
        guard let d = try? Data(contentsOf: dir.appending(path: "edits.json")),
              let e = try? JSONDecoder().decode(LibraryEdits.self, from: d) else { return LibraryEdits() }
        return e
    }

    func save(_ dir: URL) {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? enc.encode(self).write(to: dir.appending(path: "edits.json"), options: .atomic)
    }

    func isDeleted(point utc: Int64) -> Bool { deletedTracks.contains { $0[0] <= utc && utc <= $0[1] } }
}
