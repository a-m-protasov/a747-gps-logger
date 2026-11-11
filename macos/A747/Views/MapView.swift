import A747Core
import SwiftUI
import WebKit

/// The shared MapLibre page (shared/map/map.html) with OpenFreeMap tiles.
struct MapView: NSViewRepresentable {
    var tracks: [[LogPoint]]
    var waypoints: [Waypoint] = []
    /// When set, the map shows a cross at its centre and the picker reads that place.
    var picker: MapPicker?
    // observed so the map redraws when the setting changes
    @AppStorage("trackColorBySpeed") private var bySpeed = true
    @AppStorage("trackColor") private var trackColor = "#1F8A70"

    final class Coordinator: NSObject, WKNavigationDelegate {
        var loaded = false
        var pendingJS: String?
        /// The data last sent: setData fits the view to it, so sending it again would undo the user's pan and zoom.
        var sentJS: String?

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            if let js = pendingJS { webView.evaluateJavaScript(js); pendingJS = nil }
        }

        /// Links in the attribution open in the browser.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            if action.navigationType == .linkActivated, let url = action.request.url {
                NSWorkspace.shared.open(url)
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeNSView(context: Context) -> WKWebView {
        let web = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        web.navigationDelegate = context.coordinator
        web.setValue(false, forKey: "drawsBackground")
        picker?.web = web
        if let url = Bundle.main.url(forResource: "map", withExtension: "html") {
            web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        return web
    }

    func updateNSView(_ web: WKWebView, context: Context) {
        // Thin very long tracks for display; the full data stays in exports.
        let lines = tracks.map { t -> [String: Any] in
            let speeds = Self.speeds(t)
            let step = max(1, t.count / 5000)
            var idx = Array(stride(from: 0, to: t.count, by: step))
            if idx.last != t.count - 1 { idx.append(t.count - 1) }
            return ["c": idx.map { [t[$0].lon, t[$0].lat] }, "s": idx.map { (speeds[$0] * 10).rounded() / 10 }]
        }
        let wpts = waypoints.map { w -> [String: Any] in
            var o: [String: Any] = ["lon": w.point.lon, "lat": w.point.lat, "name": w.title]
            if let i = w.icon { o["icon"] = i }
            return o
        }
        let metric = UI.units.isMetric
        // the unit as the stats write it next to a number ("km/h"; the formatter's unit names are "km/hr")
        let unit = Measurement(value: 0, unit: metric ? UnitSpeed.kilometersPerHour : UnitSpeed.milesPerHour)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided))
            .components(separatedBy: .decimalDigits).joined().trimmingCharacters(in: .whitespaces)
        let style: [String: Any] = ["mode": bySpeed ? "speed" : "solid",
                                    "color": trackColor,
                                    "unit": unit, "factor": metric ? 1 : 1 / 1.609344]
        guard let json = try? JSONSerialization.data(withJSONObject: ["tracks": lines, "waypoints": wpts, "style": style]),
              let s = String(data: json, encoding: .utf8) else { return }
        let js = "window.setData(\(s)); window.setPicking(\(picker != nil))"
        let c = context.coordinator
        guard js != c.sentJS else { return }
        c.sentJS = js
        if c.loaded { web.evaluateJavaScript(js) } else { c.pendingJS = js }
    }

    /// Speed at each point in km/h: the logged value, or derived from the previous point.
    static func speeds(_ t: [LogPoint]) -> [Double] {
        t.indices.map { i in
            if let v = t[i].speed { return v }
            guard i > 0, t[i].utc > t[i - 1].utc else { return 0 }
            return Tracks.haversine(t[i - 1], t[i]) / Double(t[i].utc - t[i - 1].utc) * 3.6
        }
    }
}

/// Reads the centre of a map that shows the picking cross.
@MainActor
final class MapPicker {
    weak var web: WKWebView?

    func center() async -> (lat: Double, lon: Double)? {
        guard let web, let r = try? await web.evaluateJavaScript("window.center()") as? [Double], r.count == 2 else { return nil }
        return (r[0], r[1])
    }
}
