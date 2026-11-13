import Foundation

public struct TrackStats: Codable, Hashable, Sendable {
    public var id: String
    public var startUTC: Int64
    public var endUTC: Int64
    public var points: Int
    public var distanceM: Double
    public var durationS: Int64
    public var movingS: Int64
    public var avgSpeedKmh: Double
    public var maxSpeedKmh: Double
    public var ascentM: Double
    public var descentM: Double
}

public enum Tracks {
    /// §4.4
    public static func split(_ points: [LogPoint], gapSeconds: Int64 = 1800) -> [[LogPoint]] {
        var tracks: [[LogPoint]] = []
        for p in points {
            if let last = tracks.last?.last, p.utc - last.utc <= gapSeconds {
                tracks[tracks.count - 1].append(p)
            } else {
                tracks.append([p])
            }
        }
        return tracks.map(dropJumps).filter { !$0.isEmpty }
    }

    /// A jump: farther than `jumpM` and faster than `jumpKmh` between two consecutive points.
    public static let jumpM = 200.0, jumpKmh = 1000.0
    /// Pieces between jumps shorter than this are dropped.
    public static let jumpMinPoints = 30

    /// §4.4: GPS glitches (a cold start far off, a single wild fix) are cut out at their jumps.
    public static func isJump(_ a: LogPoint, _ b: LogPoint) -> Bool {
        let d = haversine(a, b)
        return d > jumpM && d / Double(max(b.utc - a.utc, 1)) * 3.6 > jumpKmh
    }

    public static func dropJumps(_ track: [LogPoint]) -> [LogPoint] {
        var chains: [[LogPoint]] = []  // pieces joined where no jump separates them
        var chainOf: [Int] = []        // chain index of each point
        for (i, p) in track.enumerated() {
            var c: Int?
            if i > 0 && !isJump(track[i - 1], p) {
                c = chainOf[i - 1]
            } else if i > 0 {
                // after a jump: back to the latest chain it continues, or a new one
                c = chains.indices.reversed().first { !isJump(chains[$0].last!, p) }
            }
            if c == nil { chains.append([]); c = chains.count - 1 }
            chains[c!].append(p)
            chainOf.append(c!)
        }
        if chains.count <= 1 { return track }
        var keep = Set(chains.indices.filter { chains[$0].count >= jumpMinPoints })
        if keep.isEmpty {
            // the first of the longest
            keep = [chains.indices.reduce(0) { chains[$1].count > chains[$0].count ? $1 : $0 }]
        }
        return zip(track, chainOf).filter { keep.contains($0.1) }.map(\.0)
    }

    public static let earthRadius = 6_371_008.8

    public static func haversine(_ a: LogPoint, _ b: LogPoint) -> Double {
        let p1 = a.lat * .pi / 180, p2 = b.lat * .pi / 180
        let dp = p2 - p1, dl = (b.lon - a.lon) * .pi / 180
        let h = pow(sin(dp / 2), 2) + cos(p1) * cos(p2) * pow(sin(dl / 2), 2)
        return 2 * earthRadius * asin(min(1, h.squareRoot()))
    }

    /// Rounds the exact binary value, ties to even (protocol §7).
    public static func round(_ x: Double, _ decimals: Int) -> Double {
        Double(Fmt.fixed(x, decimals))!
    }

    /// §4.5
    public static func stats(_ t: [LogPoint]) -> TrackStats {
        var dist = 0.0, moving = 0.0, segMax = 0.0
        for (a, b) in zip(t, t.dropFirst()) {
            let d = haversine(a, b)
            let dt = Double(b.utc - a.utc)
            dist += d
            if dt > 0 && d / dt * 3.6 > 1 { moving += dt }
            if dt >= 2 { segMax = max(segMax, d / dt * 3.6) }
        }
        let speeds = t.compactMap(\.speed)
        var asc = 0.0, desc = 0.0
        var ref: Double?
        for h in t.compactMap(\.height) {
            guard let r = ref else { ref = h; continue }
            if abs(h - r) > 3 {
                if h > r { asc += h - r } else { desc += r - h }
                ref = h
            }
        }
        return TrackStats(
            id: "\(t[0].utc)-\(t.count)", startUTC: t[0].utc, endUTC: t[t.count - 1].utc,
            points: t.count, distanceM: round(dist, 1), durationS: t[t.count - 1].utc - t[0].utc,
            movingS: Int64(moving.rounded(.toNearestOrEven)),
            avgSpeedKmh: moving > 0 ? round(dist / moving * 3.6, 1) : 0,
            maxSpeedKmh: round(speeds.max() ?? segMax, 1),
            ascentM: round(asc, 1), descentM: round(desc, 1))
    }
}
