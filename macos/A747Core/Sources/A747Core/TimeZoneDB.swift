import Foundation

/// Offline time zone lookup over shared/timezones.bin (protocol §4.6).
public final class TimeZoneDB: @unchecked Sendable {
    struct Zone {
        let name: String
        let bbox: (Int64, Int64, Int64, Int64)
        let rings: [[Int32]]  // flattened x, y pairs
    }

    let zones: [Zone]
    private var cache: [String: TimeZone] = [:]
    private let lock = NSLock()

    public init(data: Data) throws {
        let b = [UInt8](data)
        guard b.count > 8, b[0..<4].elementsEqual("A7TZ".utf8) else { throw CocoaError(.fileReadCorruptFile) }
        func u16(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
        func u32(_ o: Int) -> Int { (0..<4).reduce(0) { $0 | Int(b[o + $1]) << (8 * $1) } }
        func i32(_ o: Int) -> Int32 { Int32(bitPattern: UInt32(u32(o))) }
        let count = u16(6)
        var o = 8
        var zones: [Zone] = []
        zones.reserveCapacity(count)
        for _ in 0..<count {
            let n = Int(b[o])
            let name = String(decoding: b[(o + 1)..<(o + 1 + n)], as: UTF8.self)
            o += 1 + n
            let bbox = (Int64(i32(o)), Int64(i32(o + 4)), Int64(i32(o + 8)), Int64(i32(o + 12)))
            o += 16
            let rc = u32(o); o += 4
            var rings: [[Int32]] = []
            for _ in 0..<rc {
                let pc = u32(o); o += 4
                var r = [Int32](repeating: 0, count: 2 * pc)
                for k in 0..<(2 * pc) { r[k] = i32(o + 4 * k) }
                o += 8 * pc
                rings.append(r)
            }
            zones.append(Zone(name: name, bbox: bbox, rings: rings))
        }
        self.zones = zones
    }

    static func inside(_ x: Int64, _ y: Int64, _ rings: [[Int32]]) -> Bool {
        var inside = false
        for r in rings {
            let n = r.count / 2
            var j = n - 1
            for i in 0..<n {
                let xi = Int64(r[2 * i]), yi = Int64(r[2 * i + 1])
                let xj = Int64(r[2 * j]), yj = Int64(r[2 * j + 1])
                if (yi > y) != (yj > y) {
                    let lhs = (x - xi) * (yj - yi)
                    let rhs = (xj - xi) * (y - yi)
                    if yj > yi ? lhs < rhs : lhs > rhs { inside.toggle() }
                }
                j = i
            }
        }
        return inside
    }

    /// Python's round(): half to even.
    static func scaled(_ v: Double) -> Int64 { Int64((v * 100_000).rounded(.toNearestOrEven)) }

    public func zoneName(lat: Double, lon: Double) -> String {
        let x = Self.scaled(lon), y = Self.scaled(lat)
        for z in zones where z.bbox.0 <= x && x <= z.bbox.2 && z.bbox.1 <= y && y <= z.bbox.3 {
            if Self.inside(x, y, z.rings) { return z.name }
        }
        return "UTC"
    }

    public func timeZone(lat: Double, lon: Double) -> TimeZone {
        let name = zoneName(lat: lat, lon: lon)
        lock.lock(); defer { lock.unlock() }
        if let tz = cache[name] { return tz }
        let tz = TimeZone(identifier: name) ?? TimeZone(identifier: "UTC")!
        cache[name] = tz
        return tz
    }
}
