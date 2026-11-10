import Foundation

/// NMEA/PMTK sentences (protocol §2).
public enum NMEA {
    public static func checksum(_ body: String) -> UInt8 {
        body.utf8.reduce(0) { $0 ^ $1 }
    }

    /// `$<body>*XX\r\n`
    public static func sentence(_ body: String) -> Data {
        Data(String(format: "$%@*%02X\r\n", body, checksum(body)).utf8)
    }

    /// Returns the body (without `$` and checksum) if the line is a valid sentence.
    public static func validate(_ line: String) -> String? {
        let line = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard line.hasPrefix("$"), let star = line.lastIndex(of: "*") else { return nil }
        let body = String(line[line.index(after: line.startIndex)..<star])
        let cs = line[line.index(after: star)...]
        guard cs.count == 2, let v = UInt8(cs, radix: 16), v == checksum(body) else { return nil }
        return body
    }
}

/// Live receiver status from GGA (shown on the Device screen).
public struct LiveStatus: Equatable, Sendable {
    public var hasFix: Bool
    public var satellites: Int
    public var hdop: Double?

    /// Parses a GGA body: `GPGGA,time,lat,N,lon,E,quality,sats,hdop,...`
    public static func parse(gga body: String) -> LiveStatus? {
        let f = body.split(separator: ",", omittingEmptySubsequences: false)
        guard f.count > 8, f[0].hasSuffix("GGA") else { return nil }
        return LiveStatus(hasFix: (Int(f[6]) ?? 0) > 0, satellites: Int(f[7]) ?? 0, hdop: Double(f[8]))
    }
}
