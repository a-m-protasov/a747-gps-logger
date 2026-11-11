import Foundation

/// Tracks and waypoints read from a file made by another program (or exported by this app).
public struct ImportResult: Equatable, Sendable {
    public var tracks: [[LogPoint]] = []
    public var waypoints: [LogPoint] = []
    /// Names found in the file, keyed by the UTC time of a track's first point or of a waypoint.
    public var names: [Int64: String] = [:]
    public init() {}
}

public enum ImportError: Error, Equatable {
    /// The file is none of the formats below.
    case unknownFormat
    /// The file has no points with a time (the library is ordered by time).
    case noTimedPoints
}

/// Reads GPX, KML (gx:Track and timed placemarks), CSV (this app's columns or common ones) and
/// NMEA (RMC with GGA altitude). Points without a time are skipped. Same rules as the Kotlin core.
public enum Importer {
    public static func parse(_ data: Data, fileName: String = "") throws -> ImportResult {
        let text = String(decoding: data, as: UTF8.self).replacingOccurrences(of: "\u{FEFF}", with: "")
        let head = text.prefix(4096).lowercased()
        let ext = (fileName as NSString).pathExtension.lowercased()
        var r: ImportResult
        if head.range(of: "<([a-z0-9_]+:)?gpx[\\s>]", options: .regularExpression) != nil { r = try xml(data, kml: false) }
        else if head.range(of: "<([a-z0-9_]+:)?kml[\\s>]", options: .regularExpression) != nil { r = try xml(data, kml: true) }
        else if ext == "nmea" || head.hasPrefix("$") || head.contains("\n$") { r = nmea(text) }
        else if head.contains(",") { r = csv(text) }
        else { throw ImportError.unknownFormat }
        r.tracks = r.tracks.map(clean).filter { !$0.isEmpty }
        r.waypoints = clean(r.waypoints)
        if r.tracks.isEmpty && r.waypoints.isEmpty { throw ImportError.noTimedPoints }
        return r
    }

    /// Sorted by time, one point per second.
    static func clean(_ pts: [LogPoint]) -> [LogPoint] {
        var seen = Set<Int64>()
        return pts.sorted { $0.utc < $1.utc }.filter { seen.insert($0.utc).inserted }
    }

    // MARK: Time

    /// `2026-05-01T07:12:00Z`, with optional fractional seconds and offset; no offset means UTC.
    public static func time(_ s: String) -> Int64? {
        let c = Array(s.trimmingCharacters(in: .whitespaces).utf8)
        func num(_ from: Int, _ len: Int) -> Int? {
            guard from + len <= c.count else { return nil }
            var v = 0
            for b in c[from..<from + len] { guard b >= 48 && b <= 57 else { return nil }; v = v * 10 + Int(b - 48) }
            return v
        }
        guard let y = num(0, 4), c.count > 4, c[4] == 45, let mo = num(5, 2), c.count > 7, c[7] == 45, let d = num(8, 2),
              c.count > 10, c[10] == 84 || c[10] == 116 || c[10] == 32,
              let h = num(11, 2), c.count > 13, c[13] == 58, let mi = num(14, 2), c.count > 16, c[16] == 58, let se = num(17, 2)
        else { return nil }
        var i = 19
        if i < c.count && (c[i] == 46 || c[i] == 44) { i += 1; while i < c.count && c[i] >= 48 && c[i] <= 57 { i += 1 } }
        var offset = 0
        if i < c.count {
            if c[i] == 90 || c[i] == 122 { i += 1 }
            else if c[i] == 43 || c[i] == 45 {
                let sign = c[i] == 45 ? -1 : 1
                guard let oh = num(i + 1, 2) else { return nil }
                var j = i + 3
                if j < c.count && c[j] == 58 { j += 1 }
                let om = num(j, 2) ?? 0
                offset = sign * (oh * 3600 + om * 60)
            }
        }
        return epoch(y, mo, d) * 86400 + Int64(h * 3600 + mi * 60 + se - offset)
    }

    /// Days since 1970-01-01 (proleptic Gregorian).
    static func epoch(_ y0: Int, _ m: Int, _ d: Int) -> Int64 {
        let y = m <= 2 ? y0 - 1 : y0
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return Int64(era * 146097 + doe - 719468)
    }

    // MARK: GPX and KML

    private final class XMLReader: NSObject, XMLParserDelegate {
        let kml: Bool
        var r = ImportResult()
        var text = ""
        var path: [String] = []
        // GPX point in progress
        var lat: Double?, lon: Double?, ele: Double?, time: Int64?, speed: Double?, hdop: Double?, sat: Int?, pointName: String?
        var current: [LogPoint] = []
        var trackName: String?
        // KML
        var whens: [Int64?] = [], coords: [[Double]] = [], placemarkTime: Int64?, placemarkName: String?, pointCoord: [Double]?

        init(kml: Bool) { self.kml = kml }

        static func local(_ n: String) -> String { String(n.split(separator: ":").last ?? "").lowercased() }

        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                    attributes: [String: String] = [:]) {
            let n = Self.local(name)
            path.append(n)
            text = ""
            if kml {
                if n == "placemark" { placemarkTime = nil; placemarkName = nil; pointCoord = nil; whens = []; coords = [] }
                return
            }
            switch n {
            case "trkpt", "rtept", "wpt":
                lat = attributes["lat"].flatMap { Double($0.trimmingCharacters(in: .whitespaces)) }
                lon = attributes["lon"].flatMap { Double($0.trimmingCharacters(in: .whitespaces)) }
                ele = nil; time = nil; speed = nil; hdop = nil; sat = nil; pointName = nil
            case "trk", "rte": current = []; trackName = nil
            case "trkseg": if !current.isEmpty && path.dropLast().last == "trk" { flush() }
            default: break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters s: String) { text += s }
        func parser(_ parser: XMLParser, foundCDATA d: Data) { text += String(decoding: d, as: UTF8.self) }

        func flush() {
            if !current.isEmpty {
                if let n = trackName, !n.isEmpty { r.names[current.map(\.utc).min()!] = n }
                r.tracks.append(current)
            }
            current = []
        }

        func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
            let n = Self.local(name)
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            path.removeLast()
            let parent = path.last ?? ""
            if kml { endKML(n, t, parent); return }
            switch n {
            case "ele": ele = Double(t)
            case "time": if ["trkpt", "rtept", "wpt"].contains(parent) { time = Importer.time(t) }
            case "speed": speed = Double(t).map { $0 * 3.6 }
            case "hdop": hdop = Double(t)
            case "sat": sat = Int(t)
            case "name":
                if parent == "wpt" { pointName = t } else if parent == "trk" || parent == "rte" { trackName = t }
            case "trkpt", "rtept", "wpt":
                guard let la = lat, let lo = lon, let tm = time, abs(la) <= 90, abs(lo) <= 180 else { break }
                let p = LogPoint(utc: tm, lat: la, lon: lo, height: ele, speed: speed, hdop: hdop, satsUsed: sat)
                if n == "wpt" {
                    r.waypoints.append(p)
                    if let nm = pointName, !nm.isEmpty { r.names[tm] = nm }
                } else { current.append(p) }
            case "trkseg": break
            case "trk", "rte": flush()
            default: break
            }
            text = ""
        }

        static func coord(_ s: Substring) -> [Double]? {
            let v = s.split(whereSeparator: { $0 == "," || $0 == " " }).compactMap { Double($0) }
            return v.count >= 2 ? v : nil
        }

        func endKML(_ n: String, _ t: String, _ parent: String) {
            switch n {
            case "when":
                if parent == "track" { whens.append(Importer.time(t)) } else { placemarkTime = Importer.time(t) }
            case "coord": coords.append(Self.coord(Substring(t)) ?? [])
            case "name": if parent == "placemark" { placemarkName = t }
            case "coordinates": if parent == "point" { pointCoord = Self.coord(Substring(t)) }
            case "track":
                var pts: [LogPoint] = []
                for (w, c) in zip(whens, coords) {
                    guard let w, c.count >= 2 else { continue }
                    pts.append(LogPoint(utc: w, lat: c[1], lon: c[0], height: c.count > 2 ? c[2] : nil))
                }
                if !pts.isEmpty {
                    if let nm = placemarkName, !nm.isEmpty { r.names[pts.map(\.utc).min()!] = nm }
                    r.tracks.append(pts)
                }
                whens = []; coords = []
            case "placemark":
                if let c = pointCoord, let tm = placemarkTime {
                    r.waypoints.append(LogPoint(utc: tm, lat: c[1], lon: c[0], height: c.count > 2 ? c[2] : nil))
                    if let nm = placemarkName, !nm.isEmpty { r.names[tm] = nm }
                }
            default: break
            }
            text = ""
        }
    }

    static func xml(_ data: Data, kml: Bool) throws -> ImportResult {
        let reader = XMLReader(kml: kml)
        let p = XMLParser(data: data)
        p.delegate = reader
        p.shouldProcessNamespaces = false
        if !p.parse() && reader.r.tracks.isEmpty && reader.r.waypoints.isEmpty { throw ImportError.unknownFormat }
        reader.flush()
        return reader.r
    }

    // MARK: CSV

    static func fields(_ line: String) -> [String] {
        var out: [String] = [], cur = "", quoted = false
        var it = Array(line).makeIterator()
        var pending: Character? = nil
        while let ch = pending ?? it.next() {
            pending = nil
            if quoted {
                if ch == "\"" {
                    if let nx = it.next() { if nx == "\"" { cur.append("\"") } else { quoted = false; pending = nx } }
                    else { quoted = false }
                } else { cur.append(ch) }
            } else if ch == "\"" { quoted = true }
            else if ch == "," { out.append(cur); cur = "" }
            else { cur.append(ch) }
        }
        out.append(cur)
        return out.map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// Splits CSV text into records; quoted fields may contain line breaks.
    static func records(_ text: String) -> [String] {
        var out: [String] = [], cur = "", quoted = false
        for ch in text {
            if ch == "\"" { quoted.toggle() }
            if (ch == "\n" || ch == "\r\n" || ch == "\r") && !quoted {
                if !cur.trimmingCharacters(in: .whitespaces).isEmpty { out.append(cur) }
                cur = ""
            } else { cur.append(ch) }
        }
        if !cur.trimmingCharacters(in: .whitespaces).isEmpty { out.append(cur) }
        return out
    }

    static func csv(_ text: String) -> ImportResult {
        var r = ImportResult()
        let rows = records(text)
        guard let header = rows.first.map(fields)?.map({ $0.lowercased() }) else { return r }
        func col(_ names: [String]) -> Int? { header.firstIndex { names.contains($0) } }
        guard let ti = col(["time_utc", "time", "timestamp", "date_time", "datetime", "utc"]),
              let la = col(["lat", "latitude"]), let lo = col(["lon", "lng", "long", "longitude"]) else { return r }
        let ei = col(["ele_m", "ele", "elevation", "alt", "altitude", "height"])
        let si = col(["speed_kmh"]), hi = col(["hdop"]), ni = col(["sats_used", "sats", "satellites"])
        let track = col(["track", "track_name", "segment"])
        // this app's waypoints file: a "name" column and no track column
        let wptName = track == nil ? col(["name"]) : nil
        var groups: [String: [LogPoint]] = [:], order: [String] = []
        for row in rows.dropFirst() {
            let f = fields(row)
            func v(_ i: Int?) -> String? { i.flatMap { $0 < f.count && !f[$0].isEmpty ? f[$0] : nil } }
            guard let tm = v(ti).flatMap(time), let lat = v(la).flatMap(Double.init), let lon = v(lo).flatMap(Double.init),
                  abs(lat) <= 90, abs(lon) <= 180 else { continue }
            let p = LogPoint(utc: tm, lat: lat, lon: lon, height: v(ei).flatMap(Double.init), speed: v(si).flatMap(Double.init),
                             hdop: v(hi).flatMap(Double.init), satsUsed: v(ni).flatMap { Int($0) })
            if let wptName {
                r.waypoints.append(p)
                if let n = v(wptName) { r.names[tm] = n }
            } else {
                let key = v(track) ?? ""
                if groups[key] == nil { order.append(key) }
                groups[key, default: []].append(p)
            }
        }
        for key in order {
            let pts = groups[key]!
            if !key.isEmpty { r.names[pts.map(\.utc).min()!] = key }
            r.tracks.append(pts)
        }
        return r
    }

    // MARK: NMEA

    /// `ddmm.mmmm` with hemisphere.
    static func nmeaDeg(_ v: String, _ hemi: String) -> Double? {
        guard let x = Double(v) else { return nil }
        let deg = (x / 100).rounded(.towardZero)
        let d = deg + (x - deg * 100) / 60
        return hemi == "S" || hemi == "W" ? -d : d
    }

    static func nmea(_ text: String) -> ImportResult {
        var pts: [LogPoint] = []
        var alt: [String: Double] = [:] // GGA altitude by hhmmss
        let lines = text.split(whereSeparator: \.isNewline)
        for line in lines {
            guard let body = NMEA.validate(String(line)) else { continue }
            let f = body.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            if f[0].hasSuffix("GGA"), f.count > 9, let a = Double(f[9]) { alt[String(f[1].prefix(6))] = a }
        }
        for line in lines {
            guard let body = NMEA.validate(String(line)) else { continue }
            let f = body.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard f[0].hasSuffix("RMC"), f.count > 9, f[2] == "A", f[1].count >= 6, f[9].count == 6,
                  let lat = nmeaDeg(f[3], f[4]), let lon = nmeaDeg(f[5], f[6]),
                  let hh = Int(f[1].prefix(2)), let mm = Int(f[1].dropFirst(2).prefix(2)), let ss = Int(f[1].dropFirst(4).prefix(2)),
                  let dd = Int(f[9].prefix(2)), let mo = Int(f[9].dropFirst(2).prefix(2)), let yy = Int(f[9].suffix(2))
            else { continue }
            let utc = epoch(2000 + yy, mo, dd) * 86400 + Int64(hh * 3600 + mm * 60 + ss)
            pts.append(LogPoint(utc: utc, lat: lat, lon: lon, height: alt[String(f[1].prefix(6))],
                                speed: Double(f[7]).map { $0 * 1.852 }))
        }
        var r = ImportResult()
        if !pts.isEmpty { r.tracks = [pts] }
        return r
    }
}

/// The library's own file for tracks recorded on a phone or imported: one track per file
/// (rows `T`) or waypoints (rows `W`), appendable while recording.
public enum PointsFile {
    public static let header = "kind,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used\n"

    public static func row(_ kind: Character, _ p: LogPoint) -> String {
        func o(_ v: Double?, _ d: Int) -> String { v.map { Fmt.fixed($0, d) } ?? "" }
        return "\(kind),\(p.utc),\(Fmt.fixed(p.lat, 7)),\(Fmt.fixed(p.lon, 7)),\(o(p.height, 1)),\(o(p.speed, 1)),"
            + "\(o(p.hdop, 2)),\(p.satsUsed.map(String.init) ?? "")\n"
    }

    public static func encode(track: [LogPoint] = [], waypoints: [LogPoint] = []) -> String {
        header + track.map { row("T", $0) }.joined() + waypoints.map { row("W", $0) }.joined()
    }

    /// A truncated last line (the app was killed while writing) is skipped.
    public static func decode(_ text: String) -> (track: [LogPoint], waypoints: [LogPoint]) {
        var t: [LogPoint] = [], w: [LogPoint] = []
        for line in text.split(whereSeparator: \.isNewline).dropFirst() {
            let f = line.split(separator: ",", omittingEmptySubsequences: false)
            guard f.count >= 8, let utc = Int64(f[1]), let lat = Double(f[2]), let lon = Double(f[3]) else { continue }
            let p = LogPoint(utc: utc, lat: lat, lon: lon, height: Double(f[4]), speed: Double(f[5]), hdop: Double(f[6]),
                             satsUsed: Int(f[7]))
            if f[0] == "W" { w.append(p) } else { t.append(p) }
        }
        return (Importer.clean(t), Importer.clean(w))
    }
}
