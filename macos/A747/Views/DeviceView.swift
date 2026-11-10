import A747Core
import SwiftUI

struct DeviceView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmErase = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                switch model.device {
                case .disconnected: disconnected
                case .connecting: ProgressView("Connecting…").frame(maxWidth: .infinity)
                case .failed(let e):
                    Card(title: "Can't talk to the logger", systemImage: "exclamationmark.triangle") {
                        Text(e).foregroundStyle(.secondary)
                        Button("Try again") { Task { await model.connect() } }
                    }
                case .ready(let info): details(info, busy: nil)
                case .busy(let what, let p):
                    if let info = model.lastInfo { details(info, busy: (what, p)) }
                }
                if let m = model.message {
                    Label(m, systemImage: "info.circle").foregroundStyle(.secondary).textSelection(.enabled)
                }
            }
            .padding(24)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Device")
        .confirmationDialog("Erase the logger's memory?", isPresented: step(1)) {
            Button("Continue", role: .destructive) { confirmErase = 2 }
            Button("Cancel", role: .cancel) { confirmErase = 0 }
        } message: {
            Text("The log is downloaded and saved first. Erasing happens only if the download succeeds.")
        }
        .confirmationDialog("This cannot be undone.", isPresented: step(2)) {
            Button("Download and erase", role: .destructive) {
                confirmErase = 0
                Task { await model.download(thenErase: true) }
            }
            Button("Cancel", role: .cancel) { confirmErase = 0 }
        } message: {
            Text("All tracks on the logger will be erased after they are saved on this Mac.")
        }
    }

    private func step(_ n: Int) -> Binding<Bool> {
        Binding(get: { confirmErase == n }, set: { if !$0 && confirmErase == n { confirmErase = 0 } })
    }

    private var header: some View {
        HStack(spacing: 16) {
            Image("LoggerGlyph")
                .resizable().scaledToFit().frame(width: 56, height: 56)
            VStack(alignment: .leading) {
                Text("i-Blue 747A+").font(.title.bold())
                Text(statusLine).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private var statusLine: LocalizedStringKey {
        switch model.device {
        case .disconnected: "Not connected"
        case .connecting: "Connecting…"
        case .failed: "Connection problem"
        case .ready, .busy: "Connected by USB"
        }
    }

    private var disconnected: some View {
        Card(title: "Connect the logger", systemImage: "cable.connector") {
            Text("Turn the logger on and connect it to this Mac with a USB data cable. It will appear here automatically.")
                .foregroundStyle(.secondary)
            Text("If nothing happens, try another cable: many cables only charge.")
                .font(.callout).foregroundStyle(.tertiary)
        }
    }

    @ViewBuilder
    private func details(_ info: DeviceInfo, busy: (String, Double?)?) -> some View {
        warnings(info)
        actions(busy: busy)
        Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 16) {
            GridRow {
                memory(info)
                liveCard
            }
            GridRow {
                settings(info)
                firmware(info)
            }
        }
    }

    @ViewBuilder
    private func warnings(_ info: DeviceInfo) -> some View {
        if info.wrapped {
            Banner(systemImage: "exclamationmark.triangle.fill", tint: .red,
                   text: "The log has passed the real memory size. The oldest points were overwritten.")
        } else if !info.loggingOn {
            Banner(systemImage: "pause.circle.fill", tint: .secondary,
                   text: "Logging is off. Move the logger's switch to LOG to record tracks.")
        }
        if !info.wrapped && info.usedFraction > 0.75 {
            Banner(systemImage: "exclamationmark.triangle.fill", tint: .orange,
                   text: "Memory is \(Int(info.usedFraction * 100))% full. This logger has 4 MB, not the 8 MB its firmware assumes: past that, the start of the log is silently overwritten. Download and erase soon.")
        }
    }

    private func actions(busy: (String, Double?)?) -> some View {
        HStack(spacing: 12) {
            if let (what, p) = busy {
                ProgressView(value: p) { Text(what) }
                    .frame(maxWidth: 360)
            } else {
                Button { Task { await model.download(thenErase: false) } } label: {
                    Label("Download", systemImage: "arrow.down.circle")
                }
                .buttonStyle(.borderedProminent).controlSize(.large)
                Button { confirmErase = 1 } label: {
                    Label("Download and erase…", systemImage: "trash")
                }
                .controlSize(.large)
            }
        }
    }

    private func memory(_ info: DeviceInfo) -> some View {
        Card(title: "Memory", systemImage: "memorychip") {
            Gauge(value: min(1, info.usedFraction)) {
                Text("Used: \(UI.bytes(info.usedBytes)) of \(UI.bytes(info.flashSize))")
            }
            .tint(info.usedFraction > 0.75 ? .orange : .accentTrack)
            Row("Records", "\(info.recordCount)")
            if let h = info.remainingHours {
                Row("Room left", String(localized: "≈ \(Int(h.rounded())) h of logging"))
            }
            Row("When full", info.stopWhenFull ? String(localized: "Stop") : String(localized: "Overwrite"))
            Row("Logging", info.loggingOn ? String(localized: "On") : String(localized: "Off"))
        }
    }

    private var liveCard: some View {
        Card(title: "Satellites", systemImage: "location.north.circle") {
            if let l = model.live {
                Row("Position fix", l.hasFix ? String(localized: "Yes") : String(localized: "Searching…"))
                Row("Satellites in use", "\(l.satellites)")
                if let h = l.hdop { Row("HDOP", String(format: "%.1f", h)) }
            } else {
                Text("Waiting for data…").foregroundStyle(.secondary)
            }
            Text("Battery level is not reported by this logger.")
                .font(.footnote).foregroundStyle(.tertiary)
        }
    }

    private func settings(_ info: DeviceInfo) -> some View {
        Card(title: "Log settings", systemImage: "slider.horizontal.3") {
            Row("Every", info.periodTenthsS > 0 ? UI.seconds(Double(info.periodTenthsS) / 10) : "—")
            Row("Or every", info.distanceTenthsM > 0 ? UI.distance(Double(info.distanceTenthsM) / 10) : "—")
            Row("Above speed", info.speedTenthsKmh > 0 ? UI.speed(Double(info.speedTenthsKmh) / 10) : "—")
            Row("Fields", Self.fieldNames(info.format))
            Text("Settings are read-only in this version.").font(.footnote).foregroundStyle(.tertiary)
        }
    }

    private func firmware(_ info: DeviceInfo) -> some View {
        Card(title: "About the device", systemImage: "info.circle") {
            Row("Model", info.model)
            Row("Firmware", info.firmware)
            Row("Flash chip", String(format: "%08X", info.flashID))
            Row("Clock", String(localized: "1024 weeks behind (corrected automatically)"))
        }
    }

    static func fieldNames(_ mask: UInt32) -> String {
        let names = [0: "UTC", 1: "VALID", 2: "LAT", 3: "LON", 4: "HEIGHT", 5: "SPEED", 6: "HEADING",
                     7: "DSTA", 8: "DAGE", 9: "PDOP", 10: "HDOP", 11: "VDOP", 12: "NSAT", 13: "SID",
                     14: "ELEV", 15: "AZIM", 16: "SNR", 17: "RCR", 18: "MS", 19: "DIST"]
        return names.keys.sorted().filter { mask & (1 << UInt32($0)) != 0 }.map { names[$0]! }.joined(separator: " · ")
    }
}

struct Card<Content: View>: View {
    let title: LocalizedStringKey
    let systemImage: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: systemImage).font(.headline)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background.secondary, in: .rect(cornerRadius: 16))
    }
}

struct Row: View {
    let title: LocalizedStringKey
    let value: String
    init(_ title: LocalizedStringKey, _ value: String) { self.title = title; self.value = value }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value).multilineTextAlignment(.trailing).textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct Banner: View {
    let systemImage: String
    let tint: Color
    let text: LocalizedStringKey

    var body: some View {
        Label { Text(text) } icon: { Image(systemName: systemImage).foregroundStyle(tint) }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint.opacity(0.12), in: .rect(cornerRadius: 12))
    }
}
