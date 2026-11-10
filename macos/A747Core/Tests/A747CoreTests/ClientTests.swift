import Foundation
import Testing
@testable import A747Core

/// A simulated 747A+: answers PMTK commands from a flash image and streams NMEA noise.
final class FakeLogger: LoggerTransport, @unchecked Sendable {
    let lock = NSLock()
    var flash: Data
    var writeAddr: UInt32
    var status: UInt32 = 262
    var format: UInt32 = 0x0002143F
    let flashID: UInt32
    var pending = Data()
    var dropEvery = 0  // drop every n-th read reply to exercise retries
    var reads = 0
    var log: [String] = []

    init(flash: Data, flashID: UInt32 = 0x1C31161C, writeAddr: UInt32) {
        self.flash = flash; self.flashID = flashID; self.writeAddr = writeAddr
    }

    func reply(_ body: String) { pending.append(NMEA.sentence(body)) }

    func write(_ data: Data) async throws { lock.withLock { handle(data) } }

    private func handle(_ data: Data) {
        let line = String(decoding: data, as: UTF8.self)
        guard let body = NMEA.validate(line) else { return }
        log.append(body)
        // noise before every reply, including a corrupted sentence
        pending.append(Data("$GPGGA,120000.000,3842.426,N,00908.130,W,1,07,1.2,117.0,M,,,,0000*3A\r\n".utf8))
        pending.append(NMEA.sentence("GPGGA,120000.000,3842.426,N,00908.130,W,1,07,1.2,117.0,M,,,,0000"))
        let f = body.split(separator: ",").map(String.init)
        switch (f[0], f.count > 1 ? f[1] : "") {
        case ("PMTK605", _): reply("PMTK705,AXN_1.30-B_1.3_C01,0003,TSI_747A+,1.0")
        case ("PMTK182", "2"):
            let n = Int(f[2])!
            let v: UInt32 = switch n {
            case 2: format; case 3: 30; case 4: 100; case 5: 0; case 6: 2; case 7: status
            case 8: writeAddr; case 9: flashID; case 10: 1234; default: 0
            }
            // like the real device: 3–7 decimal, the rest hex (8 and 10 zero-padded)
            let text = switch n {
            case 3...7: String(v)
            case 8, 10: String(format: "%08X", v)
            default: String(format: "%X", v)
            }
            reply("PMTK182,3,\(n),\(text)")
        case ("PMTK182", "7"):
            reads += 1
            if dropEvery > 0 && reads % dropEvery == 0 { return }
            let addr = Int(f[2], radix: 16)!, size = Int(f[3], radix: 16)!
            let real = LogFormat.flashSize(flashID: flashID)
            let bytes = (0..<size).map { flash[(addr + $0) % real] }
            reply("PMTK182,8,\(f[2]),\(bytes.map { String(format: "%02X", $0) }.joined())")
            reply("PMTK001,182,7,3")
        case ("PMTK182", "6"):
            flash = Data(repeating: 0xFF, count: flash.count)
            writeAddr = 0x200; status = 260
            reply("PMTK001,182,6,3")
        case ("PMTK182", "4"):
            status |= 0x2
            reply("PMTK001,182,4,3")
        default: reply("PMTK001,\(f[0].dropFirst(4)),1")
        }
    }

    func read(timeout: Duration) async throws -> Data {
        lock.withLock {
            // deliver in small pieces, like USB packets
            let n = min(64, pending.count)
            let out = Data(pending.prefix(n))
            pending.removeFirst(n)
            return out
        }
    }

    func close() async {}
}

struct ClientTests {
    static let basic = try! Data(contentsOf: FixtureTests.repo.appending(path: "fixtures/basic/image.bin"))

    func flashImage(_ image: Data) -> Data {
        var d = Data(repeating: 0xFF, count: 4 * 1024 * 1024)
        d.replaceSubrange(0..<image.count, with: image)
        return d
    }

    @Test func fullDownload() async throws {
        let fake = FakeLogger(flash: flashImage(Self.basic), writeAddr: UInt32(Self.basic.count))
        fake.dropEvery = 7
        let client = LoggerClient(transport: fake, replyTimeout: .milliseconds(150))
        let (info, state, _) = try await Downloader.run(client: client, previous: nil) { _ in }
        #expect(info.model == "TSI_747A+")
        #expect(info.flashSize == 4 * 1024 * 1024)
        #expect(state.image == Self.basic)
        #expect(await client.live?.satellites == 7)
    }

    @Test func incrementalDownload() async throws {
        let half = 0x9000 + 0x123
        let fake = FakeLogger(flash: flashImage(Self.basic.prefix(half)), writeAddr: UInt32(half))
        let client = LoggerClient(transport: fake, replyTimeout: .milliseconds(150))
        let (_, first, _) = try await Downloader.run(client: client, previous: nil) { _ in }
        #expect(first.image == Self.basic.prefix(half))
        // the logger keeps writing
        fake.flash = flashImage(Self.basic)
        fake.writeAddr = UInt32(Self.basic.count)
        fake.reads = 0
        let (info, second, plan) = try await Downloader.run(client: client, previous: first) { _ in }
        #expect(second.image == Self.basic)
        #expect(info.writeAddr == UInt32(Self.basic.count))
        #expect(plan == .incremental(from: 0x9000, to: Self.basic.count))
        #expect(fake.reads < 40)  // ~0x10000 bytes instead of ~0x17C00
    }

    @Test func erasedLogMeansFullDownload() async throws {
        let fake = FakeLogger(flash: flashImage(Self.basic), writeAddr: UInt32(Self.basic.count))
        let client = LoggerClient(transport: fake, replyTimeout: .milliseconds(150))
        let (_, first, _) = try await Downloader.run(client: client, previous: nil) { _ in }
        _ = try await client.eraseAndEnable()
        var other = Self.basic
        other[0x200] ^= 0xFF  // a different first record
        fake.flash = flashImage(other.prefix(0x4000))
        fake.writeAddr = 0x4000
        let info = try await client.readInfo()
        let head = try await client.read(addr: 0, size: 0x800)
        #expect(Downloader.plan(info: info, head: head, previous: first) == .full(to: 0x4000))
    }

    @Test func eraseVerifies() async throws {
        let fake = FakeLogger(flash: flashImage(Self.basic), writeAddr: UInt32(Self.basic.count))
        let info = try await LoggerClient(transport: fake, replyTimeout: .milliseconds(150)).eraseAndEnable()
        #expect(info.writeAddr == 0x200)
        #expect(info.loggingOn)
        #expect(fake.log.contains("PMTK182,6,1"))
        #expect(fake.log.contains("PMTK182,4"))
    }

    @Test func sentences() {
        #expect(NMEA.sentence("PMTK182,2,8") == Data("$PMTK182,2,8*33\r\n".utf8))
        #expect(NMEA.validate("$PMTK182,2,8*34") == nil)
        #expect(NMEA.validate(String(decoding: NMEA.sentence("PMTK605"), as: UTF8.self)) == "PMTK605")
    }
}
