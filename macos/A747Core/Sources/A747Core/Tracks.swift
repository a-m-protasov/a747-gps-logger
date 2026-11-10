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
        return tracks
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
