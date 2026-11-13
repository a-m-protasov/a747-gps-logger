import SwiftUI

@main
struct A747App: App {
    @State private var model = AppModel.shared
    @AppStorage("theme") private var theme = AppTheme.system.rawValue

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .frame(minWidth: 900, minHeight: 600)
                .onChange(of: theme, initial: true) { AppTheme(rawValue: theme)?.apply() }
        }
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Import files…") { model.importWithPanel() }
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
    @AppStorage("theme") private var theme = AppTheme.system.rawValue
    /// "" follows the system; otherwise one of the app's localizations.
    @AppStorage("language") private var language = ""
    @State private var languageChanged = false

    var body: some View {
        Form {
            Picker("Appearance", selection: $theme) {
                Text("As in system settings").tag(AppTheme.system.rawValue)
                Text("Light").tag(AppTheme.light.rawValue)
                Text("Dark").tag(AppTheme.dark.rawValue)
            }
            Picker("Language", selection: $language) {
                Text("As in system settings").tag("")
                ForEach(AppLanguage.all, id: \.self) { Text(AppLanguage.name($0)).tag($0) }
            }
            .onChange(of: language) {
                AppLanguage.set(language)
                languageChanged = true
            }
            if languageChanged {
                LabeledContent("The new language is used after a restart.") {
                    Button("Restart now") { AppLanguage.restart() }
                }
            }
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

enum AppTheme: String {
    case system, light, dark

    @MainActor func apply() {
        NSApp.appearance = switch self {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
    }
}

/// The app's language, chosen in Settings (macOS reads it at launch from AppleLanguages).
@MainActor
enum AppLanguage {
    static var all: [String] {
        Bundle.main.localizations.filter { $0 != "Base" }.sorted { name($0).localizedCompare(name($1)) == .orderedAscending }
    }

    /// The language's own name for itself.
    static func name(_ code: String) -> String {
        let l = Locale(identifier: code)
        return l.localizedString(forIdentifier: code)?.capitalized(with: l) ?? code
    }

    static func set(_ code: String) {
        if code.isEmpty { UserDefaults.standard.removeObject(forKey: "AppleLanguages") }
        else { UserDefaults.standard.set([code], forKey: "AppleLanguages") }
    }

    static func restart() {
        let cfg = NSWorkspace.OpenConfiguration()
        cfg.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: Bundle.main.bundleURL, configuration: cfg) { _, _ in
            Task { @MainActor in NSApp.terminate(nil) }
        }
    }
}
