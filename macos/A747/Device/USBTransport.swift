import A747Core
import Foundation
import IOKit
import IOUSBHost

/// USB transport for the 747A+ (protocol §1.1): talks to the bulk endpoints directly
/// because macOS attaches no CDC driver to this device.
final class USBTransport: LoggerTransport, @unchecked Sendable {
    static let vendorID = 0x0E8D
    static let productID = 0x3329

    enum USBError: LocalizedError {
        case notFound, noEndpoints, open(Int)
        var errorDescription: String? {
            switch self {
            case .open(let code):
                String(localized: "Can't open the logger's USB interface (IOKit error \(String(UInt32(truncatingIfNeeded: code), radix: 16))).")
            case .notFound: String(localized: "Logger not found. Check that the cable carries data, not only power.")
            case .noEndpoints: String(localized: "The logger's USB interface has an unexpected layout.")
            }
        }
    }

    private let data: IOUSBHostInterface
    private let comm: IOUSBHostInterface?
    private let pipeIn: IOUSBHostPipe
    private let pipeOut: IOUSBHostPipe
    private let queue = DispatchQueue(label: "a747.usb")

    static func service(interface: Int) -> io_service_t? {
        let dict = IOUSBHostInterface.__createMatchingDictionary(
            withVendorID: NSNumber(value: vendorID), productID: NSNumber(value: productID), bcdDevice: nil,
            interfaceNumber: NSNumber(value: interface), configurationValue: 1, interfaceClass: nil,
            interfaceSubclass: nil, interfaceProtocol: nil, speed: nil, productIDArray: nil)
        let s = IOServiceGetMatchingService(kIOMainPortDefault, dict.takeRetainedValue())
        return s == 0 ? nil : s
    }

    static var isConnected: Bool {
        guard let s = service(interface: 0) else { return false }
        IOObjectRelease(s)
        return true
    }

    init() throws {
        guard let ds = Self.service(interface: 0) else { throw USBError.notFound }
        defer { IOObjectRelease(ds) }
        do {
            data = try IOUSBHostInterface(__ioService: ds, options: [], queue: queue, interestHandler: nil)
        } catch let e as NSError {
            throw USBError.open(e.code)
        }
        if let cs = Self.service(interface: 1) {
            comm = try? IOUSBHostInterface(__ioService: cs, options: [], queue: queue, interestHandler: nil)
            IOObjectRelease(cs)
        } else {
            comm = nil
        }
        guard let pin = try? data.copyPipe(withAddress: 0x81), let pout = try? data.copyPipe(withAddress: 0x01) else {
            throw USBError.noEndpoints
        }
        pipeIn = pin
        pipeOut = pout
        // SET_LINE_CODING 115200 8N1, SET_CONTROL_LINE_STATE DTR|RTS, addressed to the comm interface
        let control = comm ?? data
        var coding = IOUSBDeviceRequest(bmRequestType: 0x21, bRequest: 0x20, wValue: 0, wIndex: 1, wLength: 7)
        let payload = NSMutableData(bytes: [0x00, 0xC2, 0x01, 0x00, 0x00, 0x00, 0x08] as [UInt8], length: 7)
        var n = 0
        try control.__send(coding, data: payload, bytesTransferred: &n, completionTimeout: 1)
        coding = IOUSBDeviceRequest(bmRequestType: 0x21, bRequest: 0x22, wValue: 3, wIndex: 1, wLength: 0)
        try control.__send(coding, data: nil, bytesTransferred: &n, completionTimeout: 1)
    }

    func write(_ bytes: Data) async throws {
        _ = try await run {
            let buf = NSMutableData(data: bytes)
            var n = 0
            try self.pipeOut.__sendIORequest(with: buf, bytesTransferred: &n, completionTimeout: 2)
            return Data()
        }
    }

    func read(timeout: Duration) async throws -> Data {
        let secs = max(0.01, Double(timeout.components.seconds) + Double(timeout.components.attoseconds) / 1e18)
        return try await run {
            let buf = NSMutableData(length: 4096)!
            var n = 0
            do {
                try self.pipeIn.__sendIORequest(with: buf, bytesTransferred: &n, completionTimeout: secs)
            } catch let e as NSError where Self.isTimeout(e) {
                return Data()
            }
            return Data(bytes: buf.bytes, count: n)
        }
    }

    /// kIOReturnTimeout or kIOUSBTransactionTimeout (iokit_usb_err(0x51)).
    static func isTimeout(_ e: NSError) -> Bool {
        let code = UInt32(truncatingIfNeeded: e.code)
        return code == UInt32(bitPattern: kIOReturnTimeout) || code == 0xE000_4051
    }

    private func run<T: Sendable>(_ body: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { cont in
            queue.async { cont.resume(with: Result { try body() }) }
        }
    }

    func close() async {
        data.destroy()
        comm?.destroy()
    }
}
