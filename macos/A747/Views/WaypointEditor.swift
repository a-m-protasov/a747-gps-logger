import A747Core
import SwiftUI

/// Adds a waypoint (placed by moving the map under the cross), or edits a waypoint's name and icon.
struct WaypointEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// The waypoint to edit; nil adds a new one.
    var editing: Waypoint?
    /// What the map shows around the new waypoint.
    var tracks: [[LogPoint]] = []
    var waypoints: [Waypoint] = []
    /// Called with the list id of the new waypoint.
    var onAdded: (String) -> Void = { _ in }

    @State private var name = ""
    @State private var icon: String?
    @State private var picker = MapPicker()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(editing == nil ? "Add waypoint" : "Edit waypoint").font(.headline)
            if editing == nil {
                MapView(tracks: tracks, waypoints: waypoints, picker: picker)
                    .frame(height: 220)
                    .clipShape(.rect(cornerRadius: 8))
                Text("Move the map so that the cross is on the place.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            TextField("Name", text: $name, prompt: Text(editing?.name ?? String(localized: "Name")))
            Text("Icon").font(.subheadline.weight(.medium))
            IconGrid(selection: $icon)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button(editing == nil ? "Add" : "Save") { Task { await save() } }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 560)
        .onAppear {
            name = editing?.customName ?? ""
            icon = editing?.icon
        }
    }

    private func save() async {
        if let w = editing {
            model.edit(w, name: name, icon: icon)
        } else {
            guard let c = await picker.center() else { return }
            if let id = model.addWaypoint(lat: c.lat, lon: c.lon, name: name, icon: icon) { onAdded(id) }
        }
        dismiss()
    }
}

/// The icons a waypoint can have; the first cell is the plain marker.
struct IconGrid: View {
    @Binding var selection: String?

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(34), spacing: 6), count: 13), spacing: 6) {
            cell(nil) {
                Circle().fill(Color.accentWaypoint).frame(width: 12, height: 12)
                    .overlay(Circle().stroke(.white, lineWidth: 2))
            }
            .help("No icon")
            ForEach(WaypointIcons.all, id: \.self) { e in
                cell(e) { Text(e).font(.system(size: 18)) }
            }
        }
    }

    private func cell(_ value: String?, @ViewBuilder _ content: () -> some View) -> some View {
        let on = selection == value
        return Button { selection = value } label: {
            content()
                .frame(width: 34, height: 34)
                .background(on ? Color.accentWaypoint.opacity(0.2) : Color.secondary.opacity(0.08), in: .rect(cornerRadius: 7))
                .overlay(RoundedRectangle(cornerRadius: 7).stroke(on ? Color.accentWaypoint : .clear, lineWidth: 2))
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
