import SwiftUI

@main
struct A747App: App {
    @State private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .frame(minWidth: 900, minHeight: 600)
        }
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Import Raw Dump…") { model.importDump() }
                    .keyboardShortcut("i", modifiers: [.command, .shift])
            }
            CommandGroup(after: .help) {
                Link("Project page", destination: URL(string: "https://github.com/a-m-protasov/a747-gps-logger")!)
            }
        }

        Settings {
            SettingsView().environment(model)
        }
    }
}

/// One window: the list of tracks and waypoints on the left with the Device and Settings
/// buttons under it; the right side shows the selection, or the device when nothing is selected.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: Set<String> = []

    var body: some View {
        NavigationStack {
            LibraryView(selection: $selection)
        }
        .onAppear {
            // `-selectFirstTrack YES` opens the newest track directly (used for screenshots)
            if UserDefaults.standard.bool(forKey: "selectFirstTrack"), let t = model.tracks.first {
                selection = [LibraryItem.track(t).id]
            }
        }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @AppStorage("units") private var units = UnitSystem.system.rawValue
    @AppStorage("splitGapMinutes") private var gap = 30
    @AppStorage("showWaypointsOnTrack") private var showWaypoints = false
    @AppStorage("trackColorBySpeed") private var bySpeed = true
    @AppStorage("trackColor") private var trackColor = "#1F8A70"

    var body: some View {
        Form {
            Toggle("Color by speed", isOn: $bySpeed)
            ColorPicker("Track color", selection: Binding(
                get: { Color(hex: trackColor) },
                set: { trackColor = $0.hex }), supportsOpacity: false)
                .disabled(bySpeed)
            Picker("Units", selection: $units) {
                ForEach(UnitSystem.allCases) { Text($0.title).tag($0.rawValue) }
            }
            Stepper(value: $gap, in: 5...240, step: 5) {
                Text("New track after a pause of: \(UI.duration(Int64(gap) * 60))")
            }
            .onChange(of: gap) { model.reload() }
            Toggle("Show waypoints on track maps", isOn: $showWaypoints)
            LabeledContent("Library") {
                Button("Show in Finder") { NSWorkspace.shared.open(model.library.dir) }
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .padding(.vertical, 8)
    }
}

extension Color {
    init(hex: String) {
        let v = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x1F8A70
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    var hex: String {
        guard let c = NSColor(self).usingColorSpace(.sRGB) else { return "#1F8A70" }
        return String(format: "#%02X%02X%02X", Int(c.redComponent * 255), Int(c.greenComponent * 255), Int(c.blueComponent * 255))
    }
}
