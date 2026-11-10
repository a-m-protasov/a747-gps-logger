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
    case device, tracks, waypoints
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .device: "Device"
        case .tracks: "Tracks"
        case .waypoints: "Waypoints"
        }
    }

    var icon: String {
        switch self {
        case .device: "antenna.radiowaves.left.and.right"
        case .tracks: "point.topleft.down.to.point.bottomright.curvepath"
        case .waypoints: "mappin.and.ellipse"
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
                    trackSelection = [t.id]
                }
            }
        } detail: {
            switch section ?? .device {
            case .device: DeviceView()
            case .waypoints: WaypointsView()
            case .tracks:
                HSplitView {
                    TracksList(selection: $trackSelection).frame(minWidth: 280, idealWidth: 320)
                    let chosen = model.tracks.filter { trackSelection.contains($0.id) }
                    if chosen.isEmpty {
                        ContentUnavailableView("Select a track", systemImage: "map")
                            .frame(minWidth: 400, maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        TrackDetail(tracks: chosen).frame(minWidth: 400)
                    }
                }
            }
        }
    }

    private func badge(_ s: SidebarSection) -> Int {
        switch s {
        case .device: 0
        case .tracks: model.tracks.count
        case .waypoints: model.waypoints.count
        }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @AppStorage("units") private var units = UnitSystem.system.rawValue
    @AppStorage("splitGapMinutes") private var gap = 30
    @AppStorage("showWaypointsOnTrack") private var showWaypoints = false

    var body: some View {
        Form {
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
