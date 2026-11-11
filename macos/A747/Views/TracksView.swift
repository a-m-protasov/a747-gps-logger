import A747Core
import Charts
import SwiftUI

struct TrackDetail: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]
    /// Waypoints selected together with the tracks.
    var selectedWaypoints: [Waypoint] = []
    @AppStorage("showWaypointsOnTrack") private var showWaypoints = false

    private var waypoints: [Waypoint] {
        var out = selectedWaypoints
        if showWaypoints, let a = tracks.map(\.stats.startUTC).min(), let b = tracks.map(\.stats.endUTC).max() {
            out += model.waypoints.filter { (a...b).contains($0.point.utc) && !out.contains($0) }
        }
        return out
    }

    var body: some View {
        VStack(spacing: 0) {
            MapView(tracks: tracks.map(\.points), waypoints: waypoints)
                .frame(maxWidth: .infinity, minHeight: 280, maxHeight: .infinity)
            if tracks.count == 1, let t = tracks.first {
                ScrollView { TrackStatsView(track: t).padding(20) }
                    .frame(height: 300)
            } else {
                Text("Selected: \(tracks.count + selectedWaypoints.count)").foregroundStyle(.secondary).padding()
            }
        }
        .navigationTitle(tracks.count == 1 && selectedWaypoints.isEmpty ? tracks[0].title
                         : String(localized: "Selected: \(tracks.count + selectedWaypoints.count)"))
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
