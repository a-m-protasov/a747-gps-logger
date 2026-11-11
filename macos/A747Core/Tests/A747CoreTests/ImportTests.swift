import Foundation
import Testing
@testable import A747Core

/// Same cases as ImportTests in the Kotlin core.
struct ImportTests {
    func file(_ path: String) throws -> Data { try Data(contentsOf: FixtureTests.repo.appending(path: path)) }

    /// This app's own exports come back as the same tracks and waypoints.
    @Test(arguments: ["basic", "wrapped"])
    func roundTrip(_ name: String) throws {
        let ex = Exporter(tz: FixtureTests.tz)
        for f in ["tracks.gpx", "tracks.csv"] {
            let gold = String(decoding: try file("fixtures/\(name)/\(f)"), as: UTF8.self)
            let r = try Importer.parse(try file("fixtures/\(name)/\(f)"), fileName: f)
            #expect((f.hasSuffix("gpx") ? ex.gpx(tracks: r.tracks) : ex.csv(tracks: r.tracks)) == gold)
            #expect(r.names.values.allSatisfy { n in r.tracks.contains { ex.name($0[0]) == n } })
        }
        for f in ["waypoints.gpx", "waypoints.csv"] {
            let gold = String(decoding: try file("fixtures/\(name)/\(f)"), as: UTF8.self)
            let r = try Importer.parse(try file("fixtures/\(name)/\(f)"), fileName: f)
            #expect((f.hasSuffix("gpx") ? ex.gpx(waypoints: r.waypoints) : ex.csv(waypoints: r.waypoints)) == gold)
        }
    }

    @Test func otherGPX() throws {
        let r = try Importer.parse(try file("fixtures/import/other.gpx"))
        #expect(r.tracks.map(\.count) == [2, 1])
        #expect(r.tracks[0][0] == LogPoint(utc: 1780300800, lat: 44.8, lon: 20.45, height: 117.5))
        #expect(r.tracks[0][1].speed == 18)
        #expect(r.tracks[1][0].utc == 1780304400)
        #expect(r.waypoints.map(\.utc) == [1780308000])
        #expect(r.names == [1780300800: "Morning ride", 1780304400: "Morning ride", 1780308000: "Café & Co"])
    }

    @Test func otherKML() throws {
        let r = try Importer.parse(try file("fixtures/import/other.kml"))
        #expect(r.tracks == [[LogPoint(utc: 1780394400, lat: 44.80, lon: 20.45, height: 120),
                              LogPoint(utc: 1780394410, lat: 44.81, lon: 20.46, height: 121)]])
        #expect(r.waypoints == [LogPoint(utc: 1780394700, lat: 44.82, lon: 20.47, height: 0)])
        #expect(r.names == [1780394400: "Walk", 1780394700: "Bench"])
    }

    @Test func otherNMEA() throws {
        let r = try Importer.parse(try file("fixtures/import/other.nmea"), fileName: "other.nmea")
        #expect(r.tracks.count == 1)
        let t = r.tracks[0]
        #expect(t.count == 2)
        #expect(t[0].utc == 1780394400 && t[0].lat == 44.8 && t[0].lon == 20.45 && t[0].height == 115.2)
        #expect(abs(t[0].speed! - 18.52) < 1e-9)
        #expect(abs(t[1].lat + 44.80016666) < 1e-6 && abs(t[1].lon + 20.45016666) < 1e-6)
    }

    @Test func otherCSV() throws {
        let r = try Importer.parse(try file("fixtures/import/other.csv"))
        #expect(r.tracks == [[LogPoint(utc: 1780470000, lat: 44.80, lon: 20.45, height: 100),
                              LogPoint(utc: 1780470030, lat: 44.81, lon: 20.46)]])
        #expect(r.names.isEmpty)
    }

    @Test func errors() {
        #expect(throws: ImportError.unknownFormat) { try Importer.parse(Data("hello".utf8)) }
        #expect(throws: ImportError.noTimedPoints) {
            try Importer.parse(Data("<kml><Placemark><Point><coordinates>1,2</coordinates></Point></Placemark></kml>".utf8))
        }
    }

    @Test func times() {
        #expect(Importer.time("1970-01-01T00:00:00Z") == 0)
        #expect(Importer.time("2000-02-29T12:00:00-05:30") == 951_845_400)
        #expect(Importer.time("2026-06-01 08:00:00") == 1780300800)
        #expect(Importer.time("01.06.2026") == nil)
    }

    @Test func pointsFile() {
        let t = [LogPoint(utc: 10, lat: 1.5, lon: -2.25, height: 3, speed: 4.5, hdop: 0.9, satsUsed: 7),
                 LogPoint(utc: 11, lat: 1.6, lon: -2.26)]
        let w = [LogPoint(utc: 12, lat: 5, lon: 6)]
        let s = PointsFile.encode(track: t, waypoints: w)
        let d = PointsFile.decode(s + "T,13,1.") // a cut-off last line is skipped
        #expect(d.track == t)
        #expect(d.waypoints == w)
    }
}
