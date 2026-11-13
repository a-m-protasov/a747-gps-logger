import Testing
@testable import A747Core

/// Same cases as JumpTests in the Kotlin core (protocol §4.4, GPS jumps).
struct JumpTests {
    /// A walk north: one point every 3 s, 4 m apart.
    func walk(_ n: Int, from utc: Int64 = 1000, lat: Double = 43.0) -> [LogPoint] {
        (0..<n).map { LogPoint(utc: utc + Int64($0) * 3, lat: lat + Double($0) * 4 / 111_195, lon: 20.0) }
    }

    @Test func coldStartFarOff() {
        let bad = (0..<5).map { LogPoint(utc: 990 + Int64($0) * 2, lat: 42.991, lon: 20.0) }  // ~1 km south
        let good = walk(40)
        #expect(Tracks.dropJumps(bad + good) == good)
        #expect(Tracks.split(bad + good) == [good])
    }

    @Test func singleWildFix() {
        var t = walk(60)
        let wild = LogPoint(utc: t[30].utc, lat: 43.05, lon: 20.0)
        t[30] = wild
        #expect(Tracks.dropJumps(t) == t.filter { $0 != wild })
    }

    @Test func allPiecesShortKeepsTheLongest() {
        let a = walk(5), b = walk(10, from: 1015, lat: 43.01)
        #expect(Tracks.dropJumps(a + b) == b)
    }

    @Test func slowTravelAndCleanTracksStay() {
        let t = walk(50)
        #expect(Tracks.dropJumps(t) == t)
        // 2 km in 10 min is no jump
        let later = walk(5, from: t.last!.utc + 600, lat: 43.02)
        #expect(Tracks.dropJumps(t + later) == t + later)
        #expect(Tracks.dropJumps([]).isEmpty)
    }
}
