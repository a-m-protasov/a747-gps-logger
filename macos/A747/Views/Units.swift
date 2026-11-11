import A747Core
import Foundation
import SwiftUI

enum UnitSystem: String, CaseIterable, Identifiable {
    case system, metric, imperial
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .system: "As in system settings"
        case .metric: "Metric (km, m)"
        case .imperial: "Imperial (mi, ft)"
        }
    }

    var isMetric: Bool {
        switch self {
        case .metric: true
        case .imperial: false
        case .system: Locale.current.measurementSystem != .us && Locale.current.measurementSystem != .uk
        }
    }
}

/// Formatting for the UI (localized, unlike the exports).
enum UI {
    static var units: UnitSystem {
        UnitSystem(rawValue: UserDefaults.standard.string(forKey: "units") ?? "") ?? .system
    }

    static func distance(_ m: Double) -> String {
        let measure = Measurement(value: m, unit: UnitLength.meters)
        let target: UnitLength = units.isMetric ? (m >= 1000 ? .kilometers : .meters) : (m >= 1609 ? .miles : .feet)
        return measure.converted(to: target).formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                                                    numberFormatStyle: .number.precision(.fractionLength(target == .meters || target == .feet ? 0 : 1))))
    }

    static func height(_ m: Double) -> String {
        let measure = Measurement(value: m, unit: UnitLength.meters).converted(to: units.isMetric ? .meters : .feet)
        return measure.formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(0))))
    }

    static func speed(_ kmh: Double) -> String {
        let measure = Measurement(value: kmh, unit: UnitSpeed.kilometersPerHour)
            .converted(to: units.isMetric ? .kilometersPerHour : .milesPerHour)
        return measure.formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(1))))
    }

    static func seconds(_ s: Double) -> String {
        Measurement(value: s, unit: UnitDuration.seconds)
            .formatted(.measurement(width: .abbreviated, numberFormatStyle: .number.precision(.fractionLength(0...1))))
    }

    static func duration(_ s: Int64) -> String {
        Duration.seconds(s).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
    }

    static func time(_ utc: Int64, _ tz: TimeZone, date: Bool = false) -> String {
        var style = Date.FormatStyle(date: date ? .abbreviated : .omitted, time: .shortened)
        style.timeZone = tz
        return Date(timeIntervalSince1970: TimeInterval(utc)).formatted(style)
    }

    static func coordinates(_ p: LogPoint) -> String { String(format: "%.5f, %.5f", p.lat, p.lon) }

    static func day(_ isoDay: String, _ tz: TimeZone) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = tz
        f.dateFormat = "yyyy-MM-dd"
        guard let d = f.date(from: isoDay) else { return isoDay }
        var style = Date.FormatStyle(date: .complete, time: .omitted)
        style.timeZone = tz
        return d.formatted(style)
    }

    static func bytes(_ n: Int) -> String {
        Int64(n).formatted(.byteCount(style: .memory))
    }
}

extension Color {
    static let accentTrack = Color(red: 0x1F / 255, green: 0x8A / 255, blue: 0x70 / 255)
    static let accentWaypoint = Color(red: 0xF2 / 255, green: 0x99 / 255, blue: 0x4A / 255)
}
