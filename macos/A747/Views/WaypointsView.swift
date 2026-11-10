import A747Core
import SwiftUI

struct WaypointsView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: Set<String> = []

    private var chosen: [Waypoint] {
        selection.isEmpty ? model.waypoints : model.waypoints.filter { selection.contains($0.id) }
    }

    var body: some View {
        HSplitView {
            Group {
                if model.waypoints.isEmpty {
                    ContentUnavailableView("No waypoints", systemImage: "mappin.slash",
                                           description: Text("Press the button on the logger to mark a place."))
                } else {
                    List(model.waypoints, selection: $selection) { w in
                        HStack(spacing: 12) {
                            Image(systemName: "mappin").foregroundStyle(Color.accentWaypoint)
                                .frame(width: 28, height: 28)
                                .background(Color.accentWaypoint.opacity(0.15), in: .circle)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(UI.time(w.point.utc, w.timeZone, date: true)).font(.body.weight(.medium))
                                Text(String(format: "%.5f, %.5f", w.point.lat, w.point.lon))
                                    .font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                        .tag(w.id)
                    }
                }
            }
            .frame(minWidth: 260, idealWidth: 320)
            MapView(tracks: [], waypoints: chosen)
                .frame(minWidth: 360, maxWidth: .infinity, minHeight: 300, maxHeight: .infinity)
        }
        .navigationTitle("Waypoints")
        .navigationSubtitle(Text("Waypoints: \(model.waypoints.count)"))
        .toolbar {
            ExportMenu(title: selection.isEmpty ? "Export all" : "Export selected", formats: [.gpx, .kml, .csv]) { f in
                (ExportBuilder.waypointContent(f, chosen, model: model), "a747-waypoints")
            }
            .disabled(model.waypoints.isEmpty)
        }
    }
}
