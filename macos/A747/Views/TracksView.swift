import A747Core
import Charts
import SwiftUI

struct TracksList: View {
    @Environment(AppModel.self) private var model
    @Binding var selection: Set<String>

    private var days: [(String, [Track])] {
        var order: [String] = []
        var groups: [String: [Track]] = [:]
        for t in model.tracks {
            if groups[t.day] == nil { order.append(t.day) }
            groups[t.day, default: []].append(t)
        }
        return order.map { ($0, groups[$0]!) }
    }

    var body: some View {
        Group {
            if model.tracks.isEmpty {
                ContentUnavailableView("No tracks yet", systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                                       description: Text("Connect the logger and download its memory."))
            } else {
                List(selection: $selection) {
                    ForEach(days, id: \.0) { day, tracks in
                        Section(UI.day(day, tracks[0].timeZone)) {
                            ForEach(tracks) { t in TrackRow(track: t).tag(t.id) }
                        }
                    }
                }
            }
        }
        .navigationTitle("Tracks")
        .navigationSubtitle(Text("Tracks: \(model.tracks.count)"))
        .toolbar {
            ExportMenu(title: selection.isEmpty ? "Export all" : "Export selected",
                       formats: ExportFormat.allCases) { f in
                let chosen = selection.isEmpty ? model.tracks : model.tracks.filter { selection.contains($0.id) }
                return (try ExportBuilder.content(f, tracks: chosen, model: model), ExportBuilder.baseName(chosen))
            }
            .disabled(model.tracks.isEmpty)
        }
    }
}

struct TrackRow: View {
    let track: Track

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                .foregroundStyle(Color.accentTrack)
                .frame(width: 28, height: 28)
                .background(Color.accentTrack.opacity(0.15), in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(UI.time(track.stats.startUTC, track.timeZone)) – \(UI.time(track.stats.endUTC, track.timeZone))")
                    .font(.body.weight(.medium))
                Text("\(UI.distance(track.stats.distanceM)) · \(UI.duration(track.stats.durationS))")
                    .font(.callout).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

struct TrackDetail: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]
    @AppStorage("showWaypointsOnTrack") private var showWaypoints = false

    private var waypoints: [Waypoint] {
        guard showWaypoints, let a = tracks.map(\.stats.startUTC).min(), let b = tracks.map(\.stats.endUTC).max()
        else { return [] }
        return model.waypoints.filter { (a...b).contains($0.point.utc) }
    }

    var body: some View {
        VStack(spacing: 0) {
            MapView(tracks: tracks.map(\.points), waypoints: waypoints)
                .frame(maxWidth: .infinity, minHeight: 280, maxHeight: .infinity)
            if tracks.count == 1, let t = tracks.first {
                ScrollView { TrackStatsView(track: t).padding(20) }
                    .frame(height: 300)
            } else {
                Text("Selected tracks: \(tracks.count)").foregroundStyle(.secondary).padding()
            }
        }
        .navigationTitle(tracks.count == 1 ? tracks[0].name : String(localized: "Tracks: \(tracks.count)"))
        .toolbar {
            Toggle(isOn: $showWaypoints) { Label("Waypoints", systemImage: "mappin") }
                .help("Show waypoints made during this track")
        }
    }
}

struct TrackStatsView: View {
    let track: Track

    struct Sample: Identifiable { let id: Int; let t: Date; let v: Double }

    private var samples: (speed: [Sample], height: [Sample]) {
        let pts = track.points
        let step = max(1, pts.count / 600)
        var s: [Sample] = [], h: [Sample] = []
        for i in stride(from: 0, to: pts.count, by: step) {
            let d = Date(timeIntervalSince1970: TimeInterval(pts[i].utc))
            if let v = pts[i].speed { s.append(Sample(id: i, t: d, v: UI.units.isMetric ? v : v / 1.609344)) }
            if let v = pts[i].height { h.append(Sample(id: i, t: d, v: UI.units.isMetric ? v : v * 3.28084)) }
        }
        return (s, h)
    }

    var body: some View {
        let st = track.stats
        VStack(alignment: .leading, spacing: 16) {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), alignment: .leading)], spacing: 12) {
                Stat("Distance", UI.distance(st.distanceM))
                Stat("Duration", UI.duration(st.durationS))
                Stat("Moving", UI.duration(st.movingS))
                Stat("Average speed", UI.speed(st.avgSpeedKmh))
                Stat("Max speed", UI.speed(st.maxSpeedKmh))
                Stat("Ascent", UI.height(st.ascentM))
                Stat("Descent", UI.height(st.descentM))
                Stat("Points", "\(st.points)")
            }
            let (speed, height) = samples
            if !speed.isEmpty {
                Text("Speed").font(.headline)
                Chart(speed) { AreaMark(x: .value("Time", $0.t), y: .value("Speed", $0.v)).foregroundStyle(Color.accentTrack.gradient) }
                    .frame(height: 110)
            }
            if !height.isEmpty {
                Text("Elevation").font(.headline)
                Chart(height) { LineMark(x: .value("Time", $0.t), y: .value("Elevation", $0.v)).foregroundStyle(Color.accentWaypoint) }
                    .chartYScale(domain: .automatic(includesZero: false))
                    .frame(height: 110)
            }
        }
    }
}

struct Stat: View {
    let title: LocalizedStringKey
    let value: String
    init(_ title: LocalizedStringKey, _ value: String) { self.title = title; self.value = value }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title3.weight(.semibold)).monospacedDigit()
        }
    }
}
