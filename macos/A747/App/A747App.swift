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

enum SidebarSection: String, Hashable, CaseIterable, Identifiable {
    case device, tracks
    // List(data, selection:) tags rows with `id`, so it must have the selection's type
    var id: Self { self }

    var title: LocalizedStringKey {
        switch self {
        case .device: "Device"
        case .tracks: "Tracks"
        }
    }

    var icon: String {
        switch self {
        case .device: "antenna.radiowaves.left.and.right"
        case .tracks: "point.topleft.down.to.point.bottomright.curvepath"
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    // `-initialSection tracks -selectFirstTrack YES` open a screen directly (used for screenshots)
    @State private var section: SidebarSection? =
        SidebarSection(rawValue: UserDefaults.standard.string(forKey: "initialSection") ?? "") ?? .device
    @State private var trackSelection: Set<String> = []

    var body: some View {
        NavigationSplitView {
            List(SidebarSection.allCases, selection: $section) { s in
                Label(s.title, systemImage: s.icon)
                    .badge(badge(s))
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200)
            .onAppear {
                if UserDefaults.standard.bool(forKey: "selectFirstTrack"), let t = model.tracks.first {
                    trackSelection = [LibraryItem.track(t).id]
                }
            }
        } detail: {
            switch section ?? .device {
            case .device: DeviceView()
            case .tracks: LibraryView(selection: $trackSelection)
            }
        }
    }

    private func badge(_ s: SidebarSection) -> Int {
        switch s {
        case .device: 0
        case .tracks: model.items.count
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
