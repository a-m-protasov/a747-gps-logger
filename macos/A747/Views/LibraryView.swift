import A747Core
import SwiftUI

/// Tracks and waypoints in one list (grouped by day), with the map on the right.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Binding var selection: Set<String>
    @State private var renaming: LibraryItem?
    @State private var newName = ""
    @State private var deleting: Set<String> = []

    private var chosen: [LibraryItem] { model.items.filter { selection.contains($0.id) } }

    var body: some View {
        ResizableSplit {
            list
        } detail: {
            detail
        }
        .navigationTitle("Tracks")
        .navigationSubtitle(Text("Tracks: \(model.tracks.count)") + Text(" · ") + Text("Waypoints: \(model.waypoints.count)"))
        .toolbar {
            ExportMenu(title: selection.isEmpty ? "Export all" : "Export selected", formats: ExportFormat.allCases) { f in
                let items = selection.isEmpty ? model.items : chosen
                return (try ExportBuilder.content(f, items: items, model: model), ExportBuilder.baseName(items, model: model))
            }
            .disabled(model.items.isEmpty)
        }
        .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(renaming.map(defaultName) ?? "", text: $newName)
            Button("Save") { if let r = renaming { model.rename(r, to: newName) }; renaming = nil }
            Button("Cancel", role: .cancel) { renaming = nil }
        }
        .confirmationDialog("Delete the selected items?",
                            isPresented: Binding(get: { !deleting.isEmpty }, set: { if !$0 { deleting = [] } })) {
            Button("Delete", role: .destructive) {
                model.delete(deleting)
                selection.subtract(deleting)
                deleting = []
            }
            Button("Cancel", role: .cancel) { deleting = [] }
        } message: {
            Text("Deleted tracks and waypoints won't come back after the next download.")
        }
    }

    private var days: [(String, [LibraryItem])] {
        var order: [String] = []
        var groups: [String: [LibraryItem]] = [:]
        for i in model.items {
            if groups[i.day] == nil { order.append(i.day) }
            groups[i.day, default: []].append(i)
        }
        return order.map { ($0, groups[$0]!) }
    }

    @ViewBuilder private var list: some View {
        if model.items.isEmpty {
            ContentUnavailableView("No tracks yet", systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                                   description: Text("Connect the logger and download its memory."))
        } else {
            List(selection: $selection) {
                ForEach(days, id: \.0) { day, items in
                    Section(UI.day(day, items[0].timeZone)) {
                        ForEach(items) { i in ItemRow(item: i).tag(i.id) }
                    }
                }
            }
            .contextMenu(forSelectionType: String.self) { ids in
                if ids.count == 1, let item = model.items.first(where: { ids.contains($0.id) }) {
                    Button("Rename") { startRename(item) }
                }
                if !ids.isEmpty {
                    Button("Delete", role: .destructive) { deleting = ids }
                }
            }
            .onDeleteCommand { if !selection.isEmpty { deleting = selection } }
        }
    }

    @ViewBuilder private var detail: some View {
        let items = chosen
        let tracks = items.compactMap { if case .track(let t) = $0 { t } else { nil } }
        let wpts = items.compactMap { if case .waypoint(let w) = $0 { w } else { nil } }
        if items.isEmpty {
            ContentUnavailableView("Select a track or waypoint", systemImage: "map")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if !tracks.isEmpty {
            TrackDetail(tracks: tracks, selectedWaypoints: wpts)
        } else if wpts.count == 1, let w = wpts.first {
            WaypointDetail(waypoint: w)
        } else {
            MapView(tracks: [], waypoints: wpts)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .navigationTitle(String(localized: "Selected: \(wpts.count)"))
        }
    }

    private func defaultName(_ i: LibraryItem) -> String {
        switch i {
        case .track(let t): t.name
        case .waypoint(let w): w.name
        }
    }

    private func startRename(_ item: LibraryItem) {
        switch item {
        case .track(let t): newName = t.customName ?? ""
        case .waypoint(let w): newName = w.customName ?? ""
        }
        renaming = item
    }
}

struct ItemRow: View {
    let item: LibraryItem

    var body: some View {
        HStack(spacing: 10) {
            switch item {
            case .track(let t):
                icon("point.topleft.down.to.point.bottomright.curvepath", .accentTrack)
                let span = "\(UI.time(t.stats.startUTC, t.timeZone)) – \(UI.time(t.stats.endUTC, t.timeZone))"
                let size = "\(UI.distance(t.stats.distanceM)) · \(UI.duration(t.stats.durationS))"
                texts(t.customName ?? span, t.customName == nil ? size : "\(span) · \(size)")
            case .waypoint(let w):
                icon("mappin", .accentWaypoint)
                let time = UI.time(w.point.utc, w.timeZone)
                texts(w.customName ?? time, w.customName == nil ? UI.coordinates(w.point) : "\(time) · \(UI.coordinates(w.point))")
            }
        }
        .padding(.vertical, 2)
    }

    private func icon(_ name: String, _ color: Color) -> some View {
        Image(systemName: name)
            .foregroundStyle(color)
            .frame(width: 26, height: 26)
            .background(color.opacity(0.15), in: .circle)
    }

    private func texts(_ title: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.body.weight(.medium)).lineLimit(1)
            Text(subtitle).font(.callout).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

struct WaypointDetail: View {
    let waypoint: Waypoint

    var body: some View {
        VStack(spacing: 0) {
            MapView(tracks: [], waypoints: [waypoint])
                .frame(maxWidth: .infinity, minHeight: 280, maxHeight: .infinity)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), alignment: .leading)], spacing: 12) {
                Stat("Time", UI.time(waypoint.point.utc, waypoint.timeZone, date: true))
                Stat("Coordinates", UI.coordinates(waypoint.point))
                if let h = waypoint.point.height { Stat("Elevation", UI.height(h)) }
            }
            .padding(20)
        }
        .navigationTitle(waypoint.title)
    }
}

/// A list on the left and a detail on the right. The list is narrow by default; the width the
/// user drags it to is remembered across screens and launches.
struct ResizableSplit<List: View, Detail: View>: View {
    @ViewBuilder var list: List
    @ViewBuilder var detail: Detail
    @AppStorage("libraryListWidth") private var width = 260.0
    @State private var dragStart: Double?

    var body: some View {
        GeometryReader { geo in
            let maxWidth = max(200, min(560, geo.size.width - 360))
            HStack(spacing: 0) {
                list.frame(width: min(max(width, 200), maxWidth))
                Rectangle()
                    .fill(Color(nsColor: .separatorColor))
                    .frame(width: 1)
                    .overlay {
                        Color.clear
                            .frame(width: 9)
                            .contentShape(Rectangle())
                            .onHover { inside in
                                if inside { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() }
                            }
                            .gesture(DragGesture(minimumDistance: 1, coordinateSpace: .global)
                                .onChanged { g in
                                    let start = dragStart ?? width
                                    if dragStart == nil { dragStart = width }
                                    width = min(max(start + g.translation.width, 200), maxWidth)
                                }
                                .onEnded { _ in dragStart = nil })
                    }
                    .zIndex(1)
                detail.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}
