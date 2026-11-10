import Foundation

/// A byte stream to the logger: USB bulk endpoints or Bluetooth SPP.
public protocol LoggerTransport: Sendable {
    func write(_ data: Data) async throws
    /// Returns whatever arrived within `timeout` (may be empty).
    func read(timeout: Duration) async throws -> Data
    func close() async
}

public enum LoggerError: Error, Equatable, Sendable {
    case noReply(String)
    case badReply(String)
    case commandFailed(String, flag: Int)
    case eraseNotConfirmed(status: UInt32, writeAddr: UInt32)
    case cancelled
}

/// Settings and state read from the device (protocol §2.2).
public struct DeviceInfo: Codable, Equatable, Sendable {
    public var firmware: String
    public var model: String
    public var flashID: UInt32
    public var format: UInt32
    public var periodTenthsS: UInt32
    public var distanceTenthsM: UInt32
    public var speedTenthsKmh: UInt32
    public var mode: UInt32
    public var status: UInt32
    public var writeAddr: UInt32
    public var recordCount: UInt32

    public var flashSize: Int { LogFormat.flashSize(flashID: flashID) }
    public var loggingOn: Bool { status & 0x2 != 0 }
    public var stopWhenFull: Bool { mode == 2 }
    /// Bytes of real flash in use; above the flash size the log has wrapped.
    public var usedBytes: Int { min(Int(writeAddr), flashSize) }
    public var usedFraction: Double { Double(usedBytes) / Double(flashSize) }
    public var wrapped: Bool { Int(writeAddr) > flashSize }
    /// Hours of logging left at the current time interval, if records keep their size.
    public var remainingHours: Double? {
        guard periodTenthsS > 0, let size = try? LogFormat.recordSize(mask: format) else { return nil }
        let perSector = (LogFormat.sector - LogFormat.header) / size
        let sectorsLeft = Double(flashSize - usedBytes) / Double(LogFormat.sector)
        return sectorsLeft * Double(perSector) * Double(periodTenthsS) / 10 / 3600
    }
}

public actor LoggerClient {
    public static let chunk = 0x800
    let transport: LoggerTransport
    private var buffer = Data()
    public private(set) var live: LiveStatus?

    let replyTimeout: Duration

    public init(transport: LoggerTransport, replyTimeout: Duration = .seconds(3)) {
        self.transport = transport
        self.replyTimeout = replyTimeout
    }

    // Actor methods can interleave at `await`; a command must own the byte stream until
    // its reply arrives, so commands and live polling take this lock.
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    private func lock() async {
        if !locked { locked = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }

    public func close() async { await transport.close() }

    /// Next valid sentence body, or nil when the deadline passes.
    private func nextLine(until deadline: ContinuousClock.Instant) async throws -> String? {
        while true {
            if let nl = buffer.firstIndex(of: 0x0A) {
                let line = String(decoding: buffer[buffer.startIndex..<nl], as: UTF8.self)
                buffer.removeSubrange(buffer.startIndex...nl)
                guard let body = NMEA.validate(line) else { continue }
                if let s = LiveStatus.parse(gga: body) { live = s }
                return body
            }
            let left = deadline - .now
            if left <= .zero { return nil }
            try Task.checkCancellation()
            buffer.append(try await transport.read(timeout: min(left, .milliseconds(200))))
        }
    }

    /// Sends a command and returns the first reply body starting with `expect`.
    public func command(_ body: String, expect: String, timeout: Duration? = nil,
                        retries: Int = 5) async throws -> String {
        await lock()
        defer { unlock() }
        for _ in 0..<retries {
            buffer.removeAll(keepingCapacity: true)
            try await transport.write(NMEA.sentence(body))
            let deadline = ContinuousClock.now + (timeout ?? replyTimeout)
            while let line = try await nextLine(until: deadline) {
                if line.hasPrefix(expect) { return line }
            }
        }
        throw LoggerError.noReply(body)
    }

    static func ack(_ line: String) -> (cmd: String, flag: Int)? {
        let f = line.split(separator: ",")
        guard f.count >= 3, f[0] == "PMTK001", let flag = Int(f[f.count - 1]) else { return nil }
        return (String(f[1]), flag)
    }

    /// Waits for `PMTK001,<cmd>,...,3`.
    func expectAck(_ body: String, ackPrefix: String, timeout: Duration) async throws {
        let line = try await command(body, expect: ackPrefix, timeout: timeout, retries: 1)
        guard let a = Self.ack(line) else { throw LoggerError.badReply(line) }
        if a.flag != 3 { throw LoggerError.commandFailed(body, flag: a.flag) }
    }

    public func query(_ n: Int) async throws -> UInt32 {
        let line = try await command("PMTK182,2,\(n)", expect: "PMTK182,3,\(n),")
        let f = line.split(separator: ",")
        // settings 3–7 are decimal, the others hex (protocol §2.2, observed on a 747A+)
        let radix = (3...7).contains(n) ? 10 : 16
        guard f.count >= 4, let v = UInt32(f[3], radix: radix) else { throw LoggerError.badReply(line) }
        return v
    }

    public func readInfo() async throws -> DeviceInfo {
        let fw = try await command("PMTK605", expect: "PMTK705,").split(separator: ",").map(String.init)
        return DeviceInfo(
            firmware: fw.count > 1 ? fw[1] : "", model: fw.count > 3 ? fw[3] : "",
            flashID: try await query(9), format: try await query(2),
            periodTenthsS: try await query(3), distanceTenthsM: try await query(4),
            speedTenthsKmh: try await query(5), mode: try await query(6),
            status: try await query(7), writeAddr: try await query(8), recordCount: try await query(10))
    }

    public func read(addr: Int, size: Int) async throws -> Data {
        let body = String(format: "PMTK182,7,%08X,%08X", addr, size)
        for _ in 0..<5 {
            guard let line = try? await command(body, expect: "PMTK182,8,", retries: 1) else { continue }
            let f = line.split(separator: ",", maxSplits: 3)
            guard f.count == 4, Int(f[2], radix: 16) == addr, f[3].utf8.count == size * 2,
                  let data = Data(hex: f[3]) else { continue }
            return data
        }
        throw LoggerError.noReply(body)
    }

    /// Reads `[from, to)` in chunks, reporting progress as bytes done.
    public func read(from: Int, to: Int, progress: @Sendable (Int) -> Void) async throws -> Data {
        var out = Data(capacity: max(0, to - from))
        var addr = from
        while addr < to {
            try Task.checkCancellation()
            let size = min(Self.chunk, to - addr)
            out.append(try await read(addr: addr, size: size))
            addr += size
            progress(addr - from)
        }
        return out
    }

    /// Erase, re-enable logging and verify (protocol §6).
    public func eraseAndEnable() async throws -> DeviceInfo {
        try await expectAck("PMTK182,6,1", ackPrefix: "PMTK001,182,6,", timeout: .seconds(60))
        try await expectAck("PMTK182,4", ackPrefix: "PMTK001,182,4,", timeout: .seconds(5))
        let info = try await readInfo()
        guard info.loggingOn, info.writeAddr == 0x200 else {
            throw LoggerError.eraseNotConfirmed(status: info.status, writeAddr: info.writeAddr)
        }
        return info
    }

    /// Reads NMEA for a moment to refresh `live`.
    public func pollLive(for duration: Duration = .milliseconds(1200)) async throws -> LiveStatus? {
        await lock()
        defer { unlock() }
        let deadline = ContinuousClock.now + duration
        while try await nextLine(until: deadline) != nil {}
        return live
    }
}

extension Data {
    init?(hex: Substring) {
        var d = Data(capacity: hex.utf8.count / 2)
        var hi: UInt8?
        for c in hex.utf8 {
            let v: UInt8
            switch c {
            case 0x30...0x39: v = c - 0x30
            case 0x41...0x46: v = c - 0x37
            case 0x61...0x66: v = c - 0x57
            default: return nil
            }
            if let h = hi { d.append(h << 4 | v); hi = nil } else { hi = v }
        }
        if hi != nil { return nil }
        self = d
    }
}
