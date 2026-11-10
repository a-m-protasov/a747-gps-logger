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

    public init(tz: TimeZoneDB) { self.tz = tz }

    public func name(_ p: LogPoint) -> String {
        Fmt.localName(p.utc, tz.timeZone(lat: p.lat, lon: p.lon))
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

    public func gpx(tracks: [[LogPoint]]) -> String {
        var out = Self.gpxHead
        for t in tracks {
            out += "<trk><name>\(name(t[0]))</name>\n<trkseg>\n"
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

    public func gpx(waypoints: [LogPoint]) -> String {
        var out = Self.gpxHead
        for p in waypoints {
            out += "<wpt \(coord(p))>\(ele(p))<time>\(Fmt.iso(p.utc))</time><name>\(name(p))</name></wpt>\n"
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
            out += "<Placemark><name>\(name(t[0]))</name><LineString><coordinates>\(coords)</coordinates></LineString></Placemark>\n"
        }
        for p in waypoints {
            out += "<Placemark><name>\(name(p))</name><Point><coordinates>\(kmlCoord(p))</coordinates></Point></Placemark>\n"
        }
        return out + "</Document>\n</kml>\n"
    }

    private func opt(_ v: Double?, _ d: Int) -> String { v.map { Fmt.fixed($0, d) } ?? "" }

    public func csv(tracks: [[LogPoint]]) -> String {
        var out = "track,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used\n"
        for t in tracks {
            let n = name(t[0])
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
            out += "\(name(p)),\(Fmt.iso(p.utc)),\(Fmt.fixed(p.lat, 7)),\(Fmt.fixed(p.lon, 7)),\(opt(p.height, 1))\n"
        }
        return out
    }
}
