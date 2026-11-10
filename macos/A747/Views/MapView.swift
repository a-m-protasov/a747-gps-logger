import A747Core
import SwiftUI
import WebKit

/// The shared MapLibre page (shared/map/map.html) with OpenFreeMap tiles.
struct MapView: NSViewRepresentable {
    var tracks: [[LogPoint]]
    var waypoints: [Waypoint] = []

    final class Coordinator: NSObject, WKNavigationDelegate {
        var loaded = false
        var pendingJS: String?

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
        if let url = Bundle.main.url(forResource: "map", withExtension: "html") {
            web.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }
        return web
    }

    func updateNSView(_ web: WKWebView, context: Context) {
        // Thin very long tracks for display; the full data stays in exports.
        let lines = tracks.map { t -> [[Double]] in
            let step = max(1, t.count / 5000)
            return stride(from: 0, to: t.count, by: step).map { [t[$0].lon, t[$0].lat] } + [[t[t.count - 1].lon, t[t.count - 1].lat]]
        }
        let wpts = waypoints.map { ["lon": $0.point.lon, "lat": $0.point.lat, "name": $0.name] as [String: Any] }
        guard let json = try? JSONSerialization.data(withJSONObject: ["tracks": lines, "waypoints": wpts]),
              let s = String(data: json, encoding: .utf8) else { return }
        let js = "window.setData(\(s))"
        if context.coordinator.loaded { web.evaluateJavaScript(js) } else { context.coordinator.pendingJS = js }
    }
}
