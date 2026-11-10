import Foundation
import Testing
@testable import A747Core

/// Every implementation must reproduce fixtures/ byte for byte (tools/README.md).
struct FixtureTests {
    static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
    static let tz = try! TimeZoneDB(data: Data(contentsOf: repo.appending(path: "shared/timezones.bin")))

    struct Meta: Decodable {
        let flash_id: String
        let write_addr: String
        let now: Int64
        let split_gap_s: Int64
    }

    struct Summary: Decodable {
        struct T: Decodable {
            let id: String; let points: Int; let distance_m: Double; let duration_s: Int64
            let moving_s: Int64; let avg_speed_kmh: Double; let max_speed_kmh: Double
            let ascent_m: Double; let descent_m: Double; let name: String
        }
        let flash_size: Int; let points: Int; let waypoints: Int; let corrupt: Int
        let dropped_no_fix: Int; let dropped_bad_time: Int; let duplicates: Int; let tracks: [T]
    }

    func golden(_ dir: URL, _ name: String) throws -> String {
        try String(contentsOf: dir.appending(path: name), encoding: .utf8)
    }

    @Test(arguments: ["basic", "wrapped"])
    func fixture(_ name: String) throws {
        let dir = Self.repo.appending(path: "fixtures/\(name)")
        let meta = try JSONDecoder().decode(Meta.self, from: Data(contentsOf: dir.appending(path: "meta.json")))
        let summary = try JSONDecoder().decode(Summary.self, from: Data(contentsOf: dir.appending(path: "summary.json")))
        let flashID = UInt32(meta.flash_id, radix: 16)!
        let r = try LogParser.parse(image: Data(contentsOf: dir.appending(path: "image.bin")), flashID: flashID,
                                    writeAddr: Int(meta.write_addr, radix: 16)!, now: meta.now)
        #expect(LogFormat.flashSize(flashID: flashID) == summary.flash_size)
        #expect(r.points.count == summary.points)
        #expect(r.waypoints.count == summary.waypoints)
        #expect(r.corrupt == summary.corrupt)
        #expect(r.droppedNoFix == summary.dropped_no_fix)
        #expect(r.droppedBadTime == summary.dropped_bad_time)
        #expect(r.duplicates == summary.duplicates)

        let tracks = Tracks.split(r.points, gapSeconds: meta.split_gap_s)
        #expect(tracks.count == summary.tracks.count)
        let ex = Exporter(tz: Self.tz)
        for (t, g) in zip(tracks, summary.tracks) {
            let s = Tracks.stats(t)
            #expect(s.id == g.id)
            #expect(s.points == g.points)
            #expect(s.durationS == g.duration_s)
            #expect(s.movingS == g.moving_s)
            // trig may differ in the last ulp between libms: allow one unit in the last decimal
            #expect(abs(s.distanceM - g.distance_m) <= 0.1)
            #expect(abs(s.avgSpeedKmh - g.avg_speed_kmh) <= 0.1)
            #expect(abs(s.maxSpeedKmh - g.max_speed_kmh) <= 0.1)
            #expect(s.ascentM == g.ascent_m)
            #expect(s.descentM == g.descent_m)
            #expect(ex.name(t[0]) == g.name)
        }
        #expect(ex.gpx(tracks: tracks) == (try golden(dir, "tracks.gpx")))
        #expect(ex.gpx(waypoints: r.waypoints) == (try golden(dir, "waypoints.gpx")))
        #expect(ex.kml(tracks: tracks, waypoints: r.waypoints) == (try golden(dir, "tracks.kml")))
        #expect(ex.csv(tracks: tracks) == (try golden(dir, "tracks.csv")))
        #expect(ex.csv(waypoints: r.waypoints) == (try golden(dir, "waypoints.csv")))
    }

    @Test func flashSize() {
        #expect(LogFormat.flashSize(flashID: 0x1C31161C) == 4 * 1024 * 1024)
        #expect(LogFormat.flashSize(flashID: 0) == 8 * 1024 * 1024)
    }

    @Test func timeZones() {
        #expect(Self.tz.zoneName(lat: 38.7071, lon: -9.1355) == "Europe/Lisbon")
        #expect(Self.tz.zoneName(lat: 41.6938, lon: 44.8015) == "Asia/Tbilisi")
        #expect(Self.tz.zoneName(lat: 54.71, lon: 20.51) == "Europe/Kaliningrad")
        #expect(Self.tz.zoneName(lat: 43.238, lon: 76.945) == "Asia/Almaty")
    }
}
