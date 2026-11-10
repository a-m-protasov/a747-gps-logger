import Foundation

/// What the library remembers about the last download from a device (protocol §5).
public struct DownloadState: Codable, Equatable, Sendable {
    public var flashID: UInt32
    public var model: String
    public var writeAddr: UInt32
    /// The stored image (only the part that was read: `[0, min(writeAddr, flash))`).
    public var image: Data

    public init(flashID: UInt32, model: String, writeAddr: UInt32, image: Data) {
        self.flashID = flashID; self.model = model; self.writeAddr = writeAddr; self.image = image
    }
}

public enum DownloadPlan: Equatable, Sendable {
    /// Read `[from, to)` and splice it into the previous image at `from`.
    case incremental(from: Int, to: Int)
    case full(to: Int)
}

public enum Downloader {
    /// Bytes compared to decide whether the log is still the same: header bytes 2..<20
    /// and the first record at 0x200.
    static func signature(_ image: Data, recordSize: Int) -> Data? {
        let end = LogFormat.header + recordSize
        guard image.count >= end else { return nil }
        return image.subdata(in: 2..<20) + image.subdata(in: LogFormat.header..<end)
    }

    /// `head` is the first chunk (0..<0x800) of the current flash, already read.
    public static func plan(info: DeviceInfo, head: Data, previous: DownloadState?) -> DownloadPlan {
        let f = info.flashSize
        let w = Int(info.writeAddr)
        let full = DownloadPlan.full(to: min(w, f) > 0 && w <= f ? w : f)
        guard let prev = previous, prev.flashID == info.flashID, prev.model == info.model,
              w <= f, w >= Int(prev.writeAddr),
              let size = try? LogFormat.recordSize(mask: info.format),
              let a = signature(prev.image, recordSize: size), let b = signature(head, recordSize: size),
              a == b
        else { return full }
        let from = Int(prev.writeAddr) / LoggerClient.chunk * LoggerClient.chunk
        return .incremental(from: min(from, prev.image.count), to: w)
    }

    public static func apply(_ plan: DownloadPlan, previous: Data?, read: Data) -> Data {
        switch plan {
        case .full: return read
        case .incremental(let from, _):
            var img = (previous ?? Data()).prefix(from)
            img.append(read)
            return Data(img)
        }
    }

    /// Full download flow: info, head chunk, plan, read, splice.
    public static func run(client: LoggerClient, previous: DownloadState?,
                           progress: @escaping @Sendable (Double) -> Void) async throws -> (DeviceInfo, DownloadState, DownloadPlan) {
        let info = try await client.readInfo()
        let headSize = min(LoggerClient.chunk, max(Int(info.writeAddr), LogFormat.header))
        let head = try await client.read(addr: 0, size: headSize)
        let plan = plan(info: info, head: head, previous: previous)
        let (from, to): (Int, Int) = switch plan {
        case .incremental(let a, let b): (a, b)
        case .full(let b): (0, b)
        }
        let total = max(1, to - from)
        let data: Data
        if from == 0 && to <= head.count {
            data = head.prefix(to)
        } else {
            data = try await client.read(from: from, to: to) { progress(Double($0) / Double(total)) }
        }
        progress(1)
        let image = apply(plan, previous: previous?.image, read: data)
        return (info, DownloadState(flashID: info.flashID, model: info.model, writeAddr: info.writeAddr, image: image), plan)
    }
}
