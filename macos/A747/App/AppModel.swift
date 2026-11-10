import A747Core
import Foundation
import Observation
import SwiftUI

struct Track: Identifiable, Hashable {
    let points: [LogPoint]
    let stats: TrackStats
    let name: String
    let day: String
    let timeZone: TimeZone
    var id: String { stats.id }

    static func == (a: Track, b: Track) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

struct Waypoint: Identifiable, Hashable {
    let point: LogPoint
    let name: String
    let timeZone: TimeZone
    var id: String { "\(point.utc)-\(point.lat)-\(point.lon)" }
}

enum DeviceState: Equatable {
    case disconnected
    case connecting
    case ready(DeviceInfo)
    case busy(String, progress: Double?)
    case failed(String)
}

@MainActor @Observable
final class AppModel {
    static let shared = AppModel()

    let tz: TimeZoneDB
    var library = LibraryStore()
    var tracks: [Track] = []
    var waypoints: [Waypoint] = []
    var device: DeviceState = .disconnected
    var live: LiveStatus?
    var lastInfo: DeviceInfo?
    var message: String?

    @ObservationIgnored @AppStorage("splitGapMinutes") var splitGapMinutes = 30
    @ObservationIgnored private var client: LoggerClient?
    @ObservationIgnored private var watcher: Task<Void, Never>?

    var exporter: Exporter { Exporter(tz: tz) }

    init() {
        let url = Bundle.main.url(forResource: "timezones", withExtension: "bin")!
        tz = try! TimeZoneDB(data: Data(contentsOf: url))
        reload()
        watch()
    }

    func reload() {
        let now = Int64(Date().timeIntervalSince1970)
        let (points, wpts, _) = library.load(now: now)
        let ex = exporter
        let dayFmt = DateFormatter()
        dayFmt.locale = Locale(identifier: "en_US_POSIX")
        dayFmt.dateFormat = "yyyy-MM-dd"
        tracks = Tracks.split(points, gapSeconds: Int64(splitGapMinutes) * 60).map { t in
            let zone = tz.timeZone(lat: t[0].lat, lon: t[0].lon)
            dayFmt.timeZone = zone
            return Track(points: t, stats: Tracks.stats(t), name: ex.name(t[0]),
                         day: dayFmt.string(from: Date(timeIntervalSince1970: TimeInterval(t[0].utc))), timeZone: zone)
        }.reversed()
        waypoints = wpts.map { Waypoint(point: $0, name: ex.name($0), timeZone: tz.timeZone(lat: $0.lat, lon: $0.lon)) }.reversed()
    }

    // MARK: Device

    /// Polls for the USB device and keeps the live status fresh.
    private func watch() {
        watcher = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let present = USBTransport.isConnected
                switch (present, self.device) {
                case (true, .disconnected), (true, .failed):
                    await self.connect()
                case (false, .disconnected):
                    break
                case (false, _):
                    await self.client?.close()
                    self.client = nil
                    self.device = .disconnected
                    self.live = nil
                case (true, .ready):
                    if let c = self.client { self.live = try? await c.pollLive() }
                default: break
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
    }

    func connect() async {
        device = .connecting
        do {
            let c = LoggerClient(transport: try USBTransport())
            client = c
            let info = try await c.readInfo()
            lastInfo = info
            device = .ready(info)
        } catch {
            device = .failed(error.localizedDescription)
        }
    }

    func download(thenErase erase: Bool) async {
        guard let c = client else { return }
        device = .busy(String(localized: "Downloading…"), progress: 0)
        do {
            let previous = library.lastState
            let (info, state, plan) = try await Downloader.run(client: c, previous: previous) { p in
                Task { @MainActor in self.device = .busy(String(localized: "Downloading…"), progress: p) }
            }
            // verify before storing: the image must parse
            let parsed = try LogParser.parse(image: state.image, flashID: state.flashID,
                                             writeAddr: Int(state.writeAddr), now: Int64(Date().timeIntervalSince1970))
            if case .incremental = plan {
                try library.add(state, replacingLast: true)
            } else {
                try library.add(state, replacingLast: false)
            }
            reload()
            var msg = String(localized: "Download complete. Points: \(parsed.points.count), waypoints: \(parsed.waypoints.count).")
            if erase {
                device = .busy(String(localized: "Erasing memory…"), progress: nil)
                let after = try await c.eraseAndEnable()
                lastInfo = after
                msg += " " + String(localized: "Memory erased, logging on.")
                device = .ready(after)
            } else {
                lastInfo = info
                device = .ready(info)
            }
            message = msg
        } catch {
            message = error.localizedDescription
            if let info = lastInfo { device = .ready(info) } else { device = .failed(error.localizedDescription) }
        }
    }

    /// Adds a raw .bin (e.g. exported earlier or read by another tool) to the library.
    /// Without device info the whole file is parsed; a mirrored 8 MB dump is de-duplicated.
    func importDump() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.data]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let data = try Data(contentsOf: url)
            _ = try LogParser.parse(image: data, flashID: 0, writeAddr: data.count, now: Int64(Date().timeIntervalSince1970))
            try library.add(DownloadState(flashID: 0, model: "import", writeAddr: UInt32(data.count), image: data),
                            replacingLast: false)
            reload()
        } catch {
            NSAlert(error: error).runModal()
        }
    }

    func refreshInfo() async {
        guard let c = client else { return }
        if let info = try? await c.readInfo() { lastInfo = info; device = .ready(info) }
    }
}

extension LoggerError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .noReply(let c): String(localized: "The logger did not answer (\(c)).")
        case .badReply(let r): String(localized: "Unexpected reply from the logger: \(r)")
        case .commandFailed(let c, let f): String(localized: "The logger rejected \(c) (code \(f)).")
        case .eraseNotConfirmed(let s, let w):
            String(localized: "Erase not confirmed: status \(String(s, radix: 16)), write address \(String(w, radix: 16)).")
        case .cancelled: String(localized: "Cancelled.")
        }
    }
}

extension LogError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unsupportedFormat(let m):
            String(localized: "Unsupported log format \(String(format: "%08X", m)). Satellite details (SID) are not supported.")
        }
    }
}
