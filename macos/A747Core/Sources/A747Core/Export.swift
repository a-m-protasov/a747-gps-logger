import Foundation

/// Number and time formatting shared by exports (protocol §7).
public enum Fmt {
    /// printf rounding: exact binary value, ties to even.
    public static func fixed(_ x: Double, _ decimals: Int) -> String {
        String(format: "%.\(decimals)f", x)
    }

    private static let isoFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return f
    }()

    public static func iso(_ t: Int64) -> String {
        isoFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(t)))
    }

    public static func localName(_ t: Int64, _ tz: TimeZone) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = tz
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(t)))
    }
}

public struct Exporter: Sendable {
    public let tz: TimeZoneDB
    /// Names given by the user, keyed by the UTC time of a track's first point or of a waypoint.
    public var names: [Int64: String]

    public init(tz: TimeZoneDB, names: [Int64: String] = [:]) { self.tz = tz; self.names = names }

    /// The default name: local date and time of the point.
    public func name(_ p: LogPoint) -> String {
        Fmt.localName(p.utc, tz.timeZone(lat: p.lat, lon: p.lon))
    }

    /// The user's name if there is one, else the default.
    public func title(_ p: LogPoint) -> String {
        if let n = names[p.utc], !n.isEmpty { return n }
        return name(p)
    }

    private func xml(_ p: LogPoint) -> String {
        title(p).replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }

    private func csvField(_ p: LogPoint) -> String {
        let n = title(p)
        guard n.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return n }
        return "\"" + n.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    static let gpxHead = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="A747 GPS Logger" xmlns="http://www.topografix.com/GPX/1/1">

        """

    private func coord(_ p: LogPoint) -> String {
        "lat=\"\(Fmt.fixed(p.lat, 7))\" lon=\"\(Fmt.fixed(p.lon, 7))\""
    }

    private func ele(_ p: LogPoint) -> String {
        p.height.map { "<ele>\(Fmt.fixed($0, 1))</ele>" } ?? ""
    }

    public func gpx(tracks: [[LogPoint]]) -> String { gpx(tracks: tracks, waypoints: []) }
    public func gpx(waypoints: [LogPoint]) -> String { gpx(tracks: [], waypoints: waypoints) }

    /// Waypoints first, then tracks (the order GPX 1.1 requires).
    public func gpx(tracks: [[LogPoint]], waypoints: [LogPoint]) -> String {
        var out = Self.gpxHead
        for p in waypoints {
            out += "<wpt \(coord(p))>\(ele(p))<time>\(Fmt.iso(p.utc))</time><name>\(xml(p))</name></wpt>\n"
        }
        for t in tracks {
            out += "<trk><name>\(xml(t[0]))</name>\n<trkseg>\n"
            for p in t {
                var s = ele(p) + "<time>\(Fmt.iso(p.utc))</time>"
                if let n = p.satsUsed { s += "<sat>\(n)</sat>" }
                if let h = p.hdop { s += "<hdop>\(Fmt.fixed(h, 2))</hdop>" }
                out += "<trkpt \(coord(p))>\(s)</trkpt>\n"
            }
            out += "</trkseg>\n</trk>\n"
        }
        return out + "</gpx>\n"
    }

    private func kmlCoord(_ p: LogPoint) -> String {
        let base = "\(Fmt.fixed(p.lon, 7)),\(Fmt.fixed(p.lat, 7))"
        return p.height.map { base + ",\(Fmt.fixed($0, 1))" } ?? base
    }

    public func kml(tracks: [[LogPoint]], waypoints: [LogPoint]) -> String {
        var out = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n"
        for t in tracks {
            let coords = t.map(kmlCoord).joined(separator: " ")
            out += "<Placemark><name>\(xml(t[0]))</name><LineString><coordinates>\(coords)</coordinates></LineString></Placemark>\n"
        }
        for p in waypoints {
            out += "<Placemark><name>\(xml(p))</name><Point><coordinates>\(kmlCoord(p))</coordinates></Point></Placemark>\n"
        }
        return out + "</Document>\n</kml>\n"
    }

    private func opt(_ v: Double?, _ d: Int) -> String { v.map { Fmt.fixed($0, d) } ?? "" }

    public func csv(tracks: [[LogPoint]]) -> String {
        var out = "track,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used\n"
        for t in tracks {
            let n = csvField(t[0])
            for p in t {
                out += "\(n),\(Fmt.iso(p.utc)),\(Fmt.fixed(p.lat, 7)),\(Fmt.fixed(p.lon, 7)),"
                out += "\(opt(p.height, 1)),\(opt(p.speed, 1)),\(opt(p.hdop, 2)),\(p.satsUsed.map(String.init) ?? "")\n"
            }
        }
        return out
    }

    public func csv(waypoints: [LogPoint]) -> String {
        var out = "name,time_utc,lat,lon,ele_m\n"
        for p in waypoints {
            out += "\(csvField(p)),\(Fmt.iso(p.utc)),\(Fmt.fixed(p.lat, 7)),\(Fmt.fixed(p.lon, 7)),\(opt(p.height, 1))\n"
        }
        return out
    }
}
