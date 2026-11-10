import Foundation

/// One logged position (docs/protocol.md §3.3).
public struct LogPoint: Hashable, Codable, Sendable {
    public var utc: Int64
    public var lat: Double
    public var lon: Double
    public var height: Double?
    public var speed: Double?
    public var hdop: Double?
    public var satsUsed: Int?
    public var valid: Int?
    public var rcr: Int?

    public init(utc: Int64, lat: Double, lon: Double, height: Double? = nil, speed: Double? = nil,
                hdop: Double? = nil, satsUsed: Int? = nil, valid: Int? = nil, rcr: Int? = nil) {
        self.utc = utc; self.lat = lat; self.lon = lon; self.height = height; self.speed = speed
        self.hdop = hdop; self.satsUsed = satsUsed; self.valid = valid; self.rcr = rcr
    }
}

public struct ParseResult: Sendable {
    public var points: [LogPoint] = []
    public var waypoints: [LogPoint] = []
    public var corrupt = 0
    public var droppedNoFix = 0
    public var droppedBadTime = 0
    public var duplicates = 0
}

public enum LogError: Error, Equatable, Sendable {
    /// The log format contains satellite data (SID) or lacks UTC/LAT/LON.
    case unsupportedFormat(UInt32)
}

public enum LogFormat {
    public static let sector = 0x10000
    public static let header = 0x200
    public static let rollover: Int64 = 1024 * 7 * 86400
    /// 2008-01-01T00:00:00Z
    public static let rolloverFloor: Int64 = 1_199_145_600
    public static let rcrButton = 0x8
    public static let validNoFix = 0x0001

    /// bit → size in bytes
    static let fieldSizes: [(bit: Int, size: Int)] = [
        (0, 4), (1, 2), (2, 8), (3, 8), (4, 4), (5, 4), (6, 4), (7, 2), (8, 4), (9, 2),
        (10, 2), (11, 2), (12, 2), (17, 2), (18, 2), (19, 8),
    ]
    static let sidBits: UInt32 = (1 << 13) | (1 << 14) | (1 << 15) | (1 << 16)

    /// Real flash size from the JEDEC ID (§2.4).
    public static func flashSize(flashID: UInt32) -> Int {
        let code = Int((flashID >> 8) & 0xFF)
        return (0x10...0x18).contains(code) ? 1 << code : 8 * 1024 * 1024
    }

    public static func recordSize(mask: UInt32) throws -> Int {
        if mask & sidBits != 0 { throw LogError.unsupportedFormat(mask) }
        let required: UInt32 = 0b1101  // UTC, LAT, LON
        if mask & required != required { throw LogError.unsupportedFormat(mask) }
        return 2 + fieldSizes.filter { mask & (1 << UInt32($0.bit)) != 0 }.reduce(0) { $0 + $1.size }
    }

    /// (sector start, data end) in chronological order (§4.1).
    public static func sectorOrder(flash f: Int, writeAddr w: Int) -> [(start: Int, end: Int)] {
        let n = f / sector
        if w <= f {
            let last = max(0, (w + sector - 1) / sector - 1)
            return (0...last).map { ($0 * sector, min(($0 + 1) * sector, w)) }
        }
        let wm = w % f
        let newest = wm / sector
        let order = (0..<(n - 1)).map { (newest + 1 + $0) % n } + [newest]
        return order.map { s in (s * sector, s == newest ? wm : (s + 1) * sector) }
    }

    /// Adds 1024-week periods until the time is plausible (§4.2). Nil when in the future.
    public static func fixTime(_ t: Int64, now: Int64) -> Int64? {
        var t = t
        while t < rolloverFloor { t += rollover }
        return t > now + 86400 ? nil : t
    }
}

private extension Data {
    func u16(_ o: Int) -> UInt16 { UInt16(self[startIndex + o]) | UInt16(self[startIndex + o + 1]) << 8 }
    func u32(_ o: Int) -> UInt32 {
        (0..<4).reduce(UInt32(0)) { $0 | UInt32(self[startIndex + o + $1]) << (8 * UInt32($1)) }
    }
    func u64(_ o: Int) -> UInt64 {
        (0..<8).reduce(UInt64(0)) { $0 | UInt64(self[startIndex + o + $1]) << (8 * UInt64($1)) }
    }
    func f32(_ o: Int) -> Double { Double(Float(bitPattern: u32(o))) }
    func f64(_ o: Int) -> Double { Double(bitPattern: u64(o)) }
    func byte(_ o: Int) -> UInt8 { self[startIndex + o] }
}

public enum LogParser {
    static func isSeparator(_ b: Data, _ i: Int) -> Bool {
        for k in 0..<7 where b.byte(i + k) != 0xAA { return false }
        for k in 12..<16 where b.byte(i + k) != 0xBB { return false }
        return true
    }

    static func parseRecord(_ b: Data, _ i: Int, mask: UInt32, size: Int) -> LogPoint? {
        guard i + size <= b.count, b.byte(i + size - 2) == 0x2A else { return nil }
        var crc: UInt8 = 0
        for k in 0..<(size - 2) { crc ^= b.byte(i + k) }
        guard crc == b.byte(i + size - 1) else { return nil }
        var p = LogPoint(utc: 0, lat: 0, lon: 0)
        var o = i
        for (bit, sz) in LogFormat.fieldSizes where mask & (1 << UInt32(bit)) != 0 {
            switch bit {
            case 0: p.utc = Int64(b.u32(o))
            case 1: p.valid = Int(b.u16(o))
            case 2: p.lat = b.f64(o)
            case 3: p.lon = b.f64(o)
            case 4: p.height = b.f32(o)
            case 5: p.speed = b.f32(o)
            case 10: p.hdop = Double(b.u16(o)) / 100
            case 12: p.satsUsed = Int(b.byte(o + 1))
            case 17: p.rcr = Int(b.u16(o))
            default: break
            }
            o += sz
        }
        return p
    }

    /// Parses a raw image (§4.1–4.3).
    public static func parse(image full: Data, flashID: UInt32, writeAddr: Int, now: Int64) throws -> ParseResult {
        let f = LogFormat.flashSize(flashID: flashID)
        let image = full.prefix(f)
        var res = ParseResult()
        var raw: [LogPoint] = []
        for (start, sectorEnd) in LogFormat.sectorOrder(flash: f, writeAddr: writeAddr) {
            let end = min(sectorEnd, image.count)
            if start + LogFormat.header > end { continue }
            var mask = image.u32(start + 2)
            if mask == 0xFFFF_FFFF { continue }
            var size = try LogFormat.recordSize(mask: mask)
            var i = start + LogFormat.header
            var resyncing = false
            while i + 4 <= end {
                if image.u32(i) == 0xFFFF_FFFF { break }
                if i + 16 <= end && isSeparator(image, i) {
                    if image.byte(i + 7) == 0x02 {
                        mask = image.u32(i + 8)
                        size = try LogFormat.recordSize(mask: mask)
                    }
                    i += 16
                    resyncing = false
                    continue
                }
                if i + size > end { break }
                guard let p = parseRecord(image, i, mask: mask, size: size) else {
                    if !resyncing { res.corrupt += 1; resyncing = true }
                    i += 1
                    continue
                }
                resyncing = false
                raw.append(p)
                i += size
            }
        }
        struct Key: Hashable { let t: Int64; let lat: Double; let lon: Double }
        var seenT = Set<Key>(), seenW = Set<Key>()
        for var p in raw {
            if p.valid == LogFormat.validNoFix || (p.lat == 0 && p.lon == 0) {
                res.droppedNoFix += 1; continue
            }
            guard let t = LogFormat.fixTime(p.utc, now: now) else { res.droppedBadTime += 1; continue }
            p.utc = t
            let key = Key(t: p.utc, lat: p.lat, lon: p.lon)
            let isWpt = (p.rcr ?? 0) & LogFormat.rcrButton != 0
            if isWpt {
                if !seenW.insert(key).inserted { res.duplicates += 1; continue }
                res.waypoints.append(p)
            } else {
                if !seenT.insert(key).inserted { res.duplicates += 1; continue }
                res.points.append(p)
            }
        }
        // stable sort by time
        res.points = res.points.enumerated().sorted { ($0.element.utc, $0.offset) < ($1.element.utc, $1.offset) }.map(\.element)
        res.waypoints = res.waypoints.enumerated().sorted { ($0.element.utc, $0.offset) < ($1.element.utc, $1.offset) }.map(\.element)
        return res
    }
}
