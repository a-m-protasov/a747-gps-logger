# A747 GPS Logger — product spec

Companion apps for the **TranSystem i-Blue 747A+** GPS data logger (MediaTek chipset,
USB VID:PID `0E8D:3329`). Unofficial, not affiliated with TranSystem. Other MTK-based
loggers (Holux M-241, Qstarz BT-Q1000, i-Blue 737/747…) are likely to work but are listed
as untested.

## Platforms and stack

| | Android | macOS |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 Expressive | Swift, SwiftUI (Liquid Glass on macOS 26) |
| Minimum OS | Android 8.0 (API 26) | macOS 14 Sonoma |
| Connection | USB OTG (Android USB host API, bulk endpoints directly, no library), Bluetooth SPP | USB via IOKit / IOUSBHost (no libusb). No App Sandbox: IOUSBHost fails inside it (`kIOReturnInternalError`); hardened runtime on |
| Long downloads | Foreground service with progress notification | — |
| App ID | `io.github.a_m_protasov.a747` | `io.github.a_m_protasov.a747` |

Two native apps, logic written twice. To keep them identical:

- `docs/protocol.md` is the single source of truth (PMTK commands, log format, track
  splitting, week-rollover fix, mirror handling).
- `fixtures/` holds synthetic dumps plus golden GPX/KML/CSV. Both test suites must produce
  byte-identical output from the same fixtures.
- A synthetic dump generator and a device emulator are used for tests. Real tracks never
  enter the repository.

Shared design language (palette, icon, typography, screen structure); native controls on
each platform. Android tabs: **Device · Tracks · Settings** (tracks and waypoints share one list). macOS:
one window, the list on the left with Device and Settings buttons under it; with nothing
selected the right side shows the device.

## Device facts that shape the design

- The battery level is **not** exposed by the protocol → not shown (README says so).
- The device stores **one continuous log**, not a list of tracks → the app splits it.
- Flash is 4 MB while the firmware assumes 8 MB: past ~4 MB the start of the log is silently
  overwritten even in "stop when full" mode. The dump reads as two mirrored halves.
- The clock is 1024 weeks behind (GPS week rollover); time of day is correct.
- The USB descriptor lists the data interface before the comm interface, so macOS creates
  no `/dev/cu.*`; the app talks to the bulk endpoints directly (SET_LINE_CODING 115200 8N1,
  SET_CONTROL_LINE_STATE DTR|RTS).
- A full read is ~216 s at 2 KB per `PMTK182,7` request.

## Features (v1)

### Device (read-only)
- Model, firmware (`PMTK605`), flash ID and real flash size.
- Memory used as % of the real 4 MB, record count, remaining hours at current settings.
- Log format (fields), time/distance/speed intervals, mode when full.
- Live status from NMEA: fix, satellites, HDOP.
- Warnings: memory > 75 % (start of log will be overwritten), clock behind by 1024 weeks.

### Download
- Incremental: if the log was not erased since last time (first sector header matches),
  read only from the last known address to the current one. "Download everything again"
  in a menu.
- The raw `.bin` of every download is kept in the library.
- **Download & erase**: erase only after the download is verified and saved, double
  confirmation; afterwards logging is re-enabled automatically (`PMTK182,4`) and the status
  is checked. Result: "Memory erased, logging on".

### Library
- Local library, works without the device. No duplicates on re-download
  (track identity: start time + point count).
- Tracks: a new track starts after a gap > 30 min (configurable); grouped by day in the
  **time zone of the track's location** (offline tz database). GPX times are UTC.
- Week rollover fixed automatically: add 1024 weeks until the date is within
  [2008-01-01, today].
- Points without a fix are dropped; mirror duplicates removed.
- Track details: start/end, duration, points, distance, avg/max speed, ascent/descent,
  map (MapLibre + OpenFreeMap tiles, no key), speed and elevation charts.
- **Waypoints** (logger button presses) are separate items, not inside tracks: they sit in
  the same day-grouped list as the tracks, and opening one centres the map on it. On a track
  map they are an optional layer, off by default.
- Tracks and waypoints can be renamed and deleted. Edits live in `edits.json` next to the raw
  dumps, which stay untouched: names are keyed by the UTC time of the first point, deletions
  are time ranges, so a deleted item does not come back with the next download.
- macOS: the list is narrow and the map takes the rest; the width the user drags the divider
  to is remembered. Android: the map takes 2/3 of the detail screen, outside any scrolling
  container so pan and zoom are smooth.

- **Import** (Mac and Android): GPX (tracks, routes, waypoints; any namespace prefix),
  KML (`gx:Track` and placemarks with a time), CSV (this app's columns or common ones such as
  latitude/longitude/time), NMEA (RMC, altitude from GGA) and raw BIN dumps. Points without a
  time are skipped. Parsing lives in the shared cores (`Importer`) with the same tests in
  Swift and Kotlin (`fixtures/import/`). Each imported track is one file in `tracks/`
  (`import-<utc>-<id>.csv`, `PointsFile` format) and is never split; deleting it deletes the
  file. A track with the same start and end as one in the library, or a waypoint at the same
  second, is skipped. Names in the file become user names unless they equal the default name.
- **Map screen** (Android): a full-screen map; the Tracks button opens a sheet
  where tracks and waypoints are ticked to be shown together; the choice is remembered.
- **Recording on the phone** (Android): a foreground location service writes GPS fixes
  (accuracy ≤ 100 m, one every 1–60 s, default 5) straight into `tracks/phone-<utc>.csv`.
  Started from the Map screen, stopped from there or the notification. Other apps (Tasker)
  use an invisible exported activity with the actions `…action.START_RECORDING`,
  `STOP_RECORDING`, `TOGGLE_RECORDING`; it shows over the lock screen, because Android starts
  location services only for a visible app. Without location access and with the screen
  locked it posts a notification instead of a permission dialog.

### Export and sharing
- Formats: GPX 1.1, KML, CSV, BIN (raw dump).
- One track, a selection, or everything. Each track goes into its own file, the selected
  waypoints into one more; several files are shared together or saved into a chosen folder.
  User names go into the files and their names.
- System share sheet (mail, Drive, messengers) and system save dialog (iCloud Drive on Mac,
  Google Drive via the Android document picker). No cloud SDKs, no OAuth.

### Settings
- Track split gap, units (from system region, overridable), map layer for waypoints,
  phone recording interval (Android).
- No analytics, no crash reporting. The only network traffic is map tiles.

## Localization

English (base), Russian, Serbian (Cyrillic), Georgian, Kazakh (Cyrillic), Spanish,
Portuguese (Brazil), French, German, Italian, Ukrainian, Polish, Turkish, Chinese
(Simplified), Japanese, Korean, Arabic (RTL), Hindi, Indonesian. Translations are machine-made;
corrections from native speakers welcome.

## Repository and releases

- `github.com/a-m-protasov/a747-gps-logger`, MIT. Private until v1.0, then public after a
  full history check for personal data.
- Layout: `android/`, `macos/`, `docs/`, `fixtures/`, `tools/`.
- No real tracks, dumps or personal locations in the repo, screenshots or fixtures.
  `.gitignore` blocks `*.gpx`/`*.kml`/`*.bin` outside `fixtures/`.
- GitHub Actions on tag `v*`: signed APK + DMG in Releases. The DMG is ad-hoc signed (not
  notarized); the README explains how to open it.
- No vendor logos or trademarks.

## Order of work

1. Spec, protocol doc, synthetic dump generator, fixtures.
2. macOS app.
3. Android app: USB, then Bluetooth.
4. README (device description, GPS specs, illustration, photo).
5. CI, v1.0 release, make the repo public.
6. Link from the personal wiki.

## Later (v2)

- Writing device settings (log format, intervals, mode).
- Notarized DMG (Apple Developer Program), Homebrew cask, Google Play / F-Droid.
