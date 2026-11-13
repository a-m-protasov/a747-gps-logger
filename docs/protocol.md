# Protocol and log format

This document is the single source of truth for both apps. Behaviour that is not described
here must not differ between the Android and macOS implementations. The Python reference
implementation in `tools/reference/` follows this document and produces the golden files
in `fixtures/`.

Facts marked *observed* come from a real i-Blue 747A+ (firmware `AXN_1.30-B_1.3_C01`,
`TSI_747A+`). Everything else matches the MTK logger format as implemented by GPSBabel and
BT747.

## 1. Transport

### 1.1 USB

| | |
|---|---|
| VID:PID | `0E8D:3329` ("MTK GPS Receiver"), USB CDC, `bDeviceClass 2` |
| Interface 0 | data, class 10, bulk IN `0x81`, bulk OUT `0x01` |
| Interface 1 | comm, class 2, interrupt IN `0x82` |

The data interface comes *before* the comm interface, so macOS attaches no CDC driver and
creates no `/dev/cu.*`. Talk to the bulk endpoints directly:

1. Claim interface 0.
2. `SET_LINE_CODING`: `bmRequestType 0x21`, `bRequest 0x20`, `wValue 0`, `wIndex <comm
   interface>`, data `00 C2 01 00 00 00 08` (115200 baud, 1 stop bit, no parity, 8 bits).
3. `SET_CONTROL_LINE_STATE`: `0x21`, `0x22`, `wValue 3` (DTR|RTS), `wIndex <comm interface>`.
4. NMEA starts streaming on bulk IN at 1 Hz.

### 1.2 Bluetooth (Android only)

Classic Bluetooth SPP, UUID `00001101-0000-1000-8000-00805F9B34FB`. Default PIN `0000`.
Same byte stream as USB. *Not yet verified on the real device.*

## 2. Sentences

Commands are NMEA-style: `$<body>*<XX>\r\n`, where `XX` is the XOR of all bytes of
`<body>` as two upper-case hex digits. Incoming lines are split on `\n`, trimmed, and the
checksum is verified; lines with a bad checksum are ignored. Normal NMEA output (`$GPRMC`,
`$GPGGA`, `$GPGSA`, `$GPGSV`) is interleaved with replies, so a reader waits for the first
line with the expected prefix and ignores the rest.

Timeout per command: 3 s. Retry a failed command up to 5 times.

### 2.1 Commands used

| Command | Reply | Meaning |
|---|---|---|
| `PMTK605` | `$PMTK705,<fw>,<build>,<model>,<ver>` | Firmware and model. *Observed:* `AXN_1.30-B_1.3_C01,0003,TSI_747A+,1.0` |
| `PMTK182,2,<n>` | `$PMTK182,3,<n>,<hex>` | Query log setting `n` (§2.2) |
| `PMTK182,7,<addr>,<size>` | `$PMTK182,8,<addr>,<hexdata>` then `$PMTK001,182,7,3` | Read flash. `addr`, `size`: 8 hex digits. `size` = `0x800` |
| `PMTK182,6,1` | `$PMTK001,182,6,3` | Erase log. Takes ~27 s; wait up to 60 s |
| `PMTK182,4` | `$PMTK001,182,4,3` | Enable logging |
| `PMTK400` / `PMTK401` / `PMTK414` | `PMTK500` / `PMTK501` / `PMTK514` | Fix rate / SBAS / NMEA output mask (shown on the Device screen) |

`$PMTK001,<cmd>,<flag>`: flag `3` = success, `2` = failed (e.g. setting the format while
memory is full), `1` = unsupported, `0` = invalid.

The apps in v1 send **only** the commands in this table. The only command that changes the
device is the erase (followed by "enable logging").

### 2.2 `PMTK182,2,<n>` settings

| n | Setting | Units |
|---|---|---|
| 2 | Log format (field bitmask, §3.3) | hex bitmask |
| 3 | Time interval | 0.1 s (`30` = 3 s; `0` = off) |
| 4 | Distance interval | 0.1 m (`100` = 10 m; `0` = off) |
| 5 | Speed interval | 0.1 km/h (`0` = off) |
| 6 | Mode when full | `1` overwrite, `2` stop |
| 7 | Status flags | bitmask, §2.3 |
| 8 | Next write address | bytes |
| 9 | Flash ID | JEDEC ID, §2.4 |
| 10 | Record count | count |

**Number bases differ** (*observed*): settings 2, 8, 9, 10 are hex (8 and 10 zero-padded
to 8 digits, e.g. `000816E2`), settings 3–7 are **decimal** (e.g. `PMTK182,3,3,30` = 3 s,
`PMTK182,3,4,100` = 10 m, `PMTK182,3,7,260`).

### 2.3 Status flags (*observed*)

| Value | Situation |
|---|---|
| `3332` (`0xD04`) | Memory full, mode "stop" |
| `260` (`0x104`) | Logging off (just erased, or the switch is on NAV) |
| `262` (`0x106`) | Logging on |

The reply is decimal. Bit `0x2` of the value = logging enabled; `0x104`/`0x106` are the same
values the log uses in its "logging off/on" separators (§3.2). Other bits are shown raw.

### 2.4 Flash size

The firmware assumes 8 MB. The real size comes from the JEDEC ID returned by setting 9:
`1C31161C` → bytes manufacturer `0x1C` (EON), memory type `0x31`, capacity code `0x16`,
manufacturer again. Capacity code = log2(bytes): `0x16` → 2^22 = **4 MB** (*observed*).
Rule: `code = (id >> 8) & 0xFF`; `bytes = 1 << code` when `0x10 <= code <= 0x18`.
Otherwise assume 8 MB.

Addresses at or above the real size wrap around to the start of the chip. The 8 MB address
space therefore holds two mirrored copies of the same 4 MB (*observed*: a full 8 MB read
of a full log gives the same records twice; the halves are not always byte-identical
because the device may write between the two reads). Only the first `F` bytes are parsed.

## 3. Log layout

### 3.1 Sectors

The log is made of 64 KB (`0x10000`) sectors. Each sector starts with a `0x200`-byte
header, followed by records and separators. Unused space is filled with `0xFF`.

Sector header (little-endian):

| Offset | Size | Field |
|---|---|---|
| 0 | u16 | Record count in this sector, `0xFFFF` while the sector is still being written |
| 2 | u32 | Log format bitmask at sector start |
| 6 | u16 | Mode / policy |
| 8 | u32 | Time interval, 0.1 s |
| 12 | u32 | Distance interval, 0.1 m |
| 16 | u32 | Speed interval, 0.1 km/h |
| 20 | … | Unused here |

### 3.2 Separators

16 bytes: `AA AA AA AA AA AA AA <type> <u32 value LE> BB BB BB BB` (type at offset 7, value at 8–11).

| Type | Value |
|---|---|
| `0x02` | New log format bitmask (record size changes from here) |
| `0x03` | Time interval |
| `0x04` | Distance interval |
| `0x05` | Speed interval |
| `0x06` | Mode when full |
| `0x07` | `0x0106` logging turned on, `0x0104` logging turned off |

A separator can appear anywhere a record can.

### 3.3 Records

Fields appear in bit order (lowest bit first), only if their bit is set in the current
format. All numbers are little-endian.

| Bit | Field | Size | Type |
|---|---|---|---|
| 0 | UTC | 4 | u32, seconds since 1970-01-01 (before the rollover fix, §4.2) |
| 1 | VALID | 2 | u16 fix type, §3.4 |
| 2 | LATITUDE | 8 | f64 degrees |
| 3 | LONGITUDE | 8 | f64 degrees |
| 4 | HEIGHT | 4 | f32 metres |
| 5 | SPEED | 4 | f32 km/h |
| 6 | HEADING | 4 | f32 degrees |
| 7 | DSTA | 2 | u16 |
| 8 | DAGE | 4 | f32 |
| 9 | PDOP | 2 | u16 / 100 |
| 10 | HDOP | 2 | u16 / 100 |
| 11 | VDOP | 2 | u16 / 100 |
| 12 | NSAT | 2 | u8 in view, u8 in use |
| 13 | SID | 4 per satellite | see GPSBabel; skipped by these apps |
| 14–16 | ELEVATION / AZIMUTH / SNR | 2 each per satellite | only with SID |
| 17 | RCR | 2 | u16 log reason, §3.5 |
| 18 | MILLISECOND | 2 | u16 |
| 19 | DISTANCE | 8 | f64 metres |

After the fields come `*` (`0x2A`) and a one-byte checksum: the XOR of all field bytes.

Record sizes: format `0002003F` → 34 bytes, `0002143F` → 38 bytes.

Records whose bitmask includes SID have variable length. The apps do not support SID and
report "unsupported log format" instead of guessing.

### 3.4 VALID

| Value | Fix |
|---|---|
| `0x0001` | No fix → **point dropped** |
| `0x0002` | SPS (normal fix) |
| `0x0004` | DGPS |
| `0x0008` | PPS |
| `0x0040` | Estimated |
| other | Unknown, kept |

A point at exactly 0°/0° is dropped.

### 3.5 RCR (log reason)

Bitmask: `0x1` time, `0x2` speed, `0x4` distance, `0x8` **button** (waypoint).

A record with bit `0x8` is a **waypoint**, not a track point. It goes to the Waypoints
list and is not part of any track.

## 4. Parsing algorithm

### 4.1 Reading order

Given the dump bytes (address 0 upwards), the real flash size `F` (§2.4) and the next write
address `W` (setting 8):

1. If the dump is longer than `F`, cut it to `F`. The rest is the mirror.
2. If `W <= F`: the log is linear, sectors `0 … ceil(W / 0x10000) − 1`.
3. If `W > F`: the log has wrapped. The sector holding address `W mod F` is the newest.
   Read sectors in the order: the sector after it … last sector, sector 0 … the newest
   sector. Inside the newest sector, stop at offset `W mod 0x10000`.

For each sector:

1. Read the header. If the format bitmask is `0xFFFFFFFF`, the sector is empty: skip it.
2. Start at offset `0x200` with the header's format.
3. Loop:
   - `FF FF FF FF` → end of data in this sector.
   - A 16-byte separator (§3.2) → apply it, advance 16.
   - Otherwise parse a record of the current size. If `*` is missing or the checksum is
     wrong, advance 1 byte and resync (look for the next valid record
     or separator). A run of unreadable bytes counts as **one** corrupt record.

### 4.2 GPS week rollover

The clock is 1024 weeks (619 315 200 s) behind. For each timestamp `t`: while
`t < 2008-01-01T00:00:00Z`, add 1024 weeks. A timestamp more than one day in the future
after this is reported as invalid and the point is dropped.

### 4.3 Clean-up

1. Drop points with VALID = `0x0001`, and points at 0°/0°.
2. Duplicates: keep only the first point for each (UTC, LAT, LON) triple, and the same for
   waypoints.
3. Sort track points and waypoints by time (stable).

### 4.4 Splitting into tracks

A new track starts when the gap between two consecutive track points exceeds the split gap
(default 30 min, setting 5–240 min). Waypoints do not split tracks.

GPS jumps are then cut out of each track. A *jump* is a step between two consecutive points
longer than 200 m and faster than 1000 km/h (time difference taken as at least 1 s). Points are
grouped into *chains*: a point joins the chain of the point before it unless a jump separates
them; after a jump it joins the latest chain whose last point it does not jump from, or starts
a new chain. Chains with fewer than 30 points are dropped (if all are that short, the longest
is kept, the first of equals). This removes a cold start that logs a few fixes far from the
real place, and single wild fixes (the track continues on its chain after them), while slow
travel and gaps are left alone.

Track identity (for duplicate detection in the library): `"<first UTC epoch>-<point count>"`.
A track that grows (incremental download continued it) replaces the shorter one with the
same first point.

### 4.5 Statistics

| Value | Definition |
|---|---|
| Distance | Sum of haversine distances, Earth radius 6 371 008.8 m |
| Duration | Last time − first time |
| Average speed | Distance / moving time. Moving time = sum of intervals where the segment speed > 1 km/h |
| Max speed | Max of the logged SPEED field. Without SPEED, the max of segment speeds over intervals ≥ 2 s |
| Ascent / descent | Height with 3 m hysteresis: accumulate only when the change since the last accepted height exceeds 3 m |

### 4.6 Time zone

Day grouping and the times shown in the UI use the time zone at the track's **first point**
(waypoints: at the waypoint). Exported files always use UTC.

The zone is looked up offline in `shared/timezones.bin` (built by `tools/build_tz.py` from
timezone-boundary-builder "with oceans", simplified to 0.005°; format in that script):

1. Convert the point to integers: `x = round(lon × 100000)`, `y = round(lat × 100000)`.
2. Walk the zones in file order. Skip a zone whose bounding box does not contain `(x, y)`.
3. Even-odd ray casting over **all** rings of the zone. For each edge `(xi, yi)–(xj, yj)`
   with `(yi > y) ≠ (yj > y)`: `lhs = (x − xi)(yj − yi)`, `rhs = (xj − xi)(y − yi)`
   in 64-bit integers; toggle when `lhs < rhs` if `yj > yi`, or `lhs > rhs` otherwise.
4. The first zone that contains the point wins. None → `UTC`.

Offsets come from the platform's tz database for the zone name and the point's UTC time.

## 5. Download

1. Query settings 2–10, `PMTK605`.
2. **Incremental**: if the library has a previous download from this device (same flash ID
   and model), bytes 2–20 of the sector 0 header and the first record at `0x200` are
   unchanged (the count at offset 0 changes when a sector is completed, so it is not
   compared), and the new `W` is at least the previous `W` and at most `F`: read only
   `[previous W rounded down to 0x800, W)` and splice it into the stored image. Otherwise read everything from 0 to `min(W, F)`, or all of `F` if
   the log has wrapped.
3. Read in `0x800` chunks. A chunk is accepted only if the reply address matches and the
   hex length is `2 × size`.
4. Store the image as a `.bin` in the library and parse it.

## 6. Erase

Only after a download whose image was stored and parsed without errors.

1. Two confirmations in the UI.
2. `PMTK182,6,1`, wait for `PMTK001,182,6,3` (up to 60 s).
3. `PMTK182,4`, wait for `PMTK001,182,4,3`.
4. Query setting 7 and 8: the status must have bit `0x0002` set and the write address must
   be `0x200`. Show "Memory erased, logging on". Otherwise show the raw values as an error.

## 7. Export formats

Coordinates: 7 decimals. Height: 1 decimal. Speed: 1 decimal. Times: UTC,
`YYYY-MM-DDTHH:MM:SSZ`. Line endings: `\n`. Encoding: UTF-8, no BOM.

**Rounding** (here and in §4.5): round the *exact* binary value of the double to the given
number of decimals, ties to even (what C `printf("%.1f")` and Python `format` do; in
Kotlin use `BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN)`, not `String.format`).
Height and speed are `f32` in the log: widen to double first.

### 7.1 GPX 1.1

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="A747 GPS Logger" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>2026-05-01 08:12</name>
<trkseg>
<trkpt lat="38.7071000" lon="-9.1355000"><ele>117.0</ele><time>2026-05-01T06:12:00Z</time></trkpt>
</trkseg>
</trk>
</gpx>
```

- One `<trk>` per track, one `<trkseg>` per track.
- `<ele>` only if HEIGHT is logged; `<sat>` if NSAT; `<hdop>` if HDOP (2 decimals).
- Track name: local start time `YYYY-MM-DD HH:MM` (§4.6).
- Waypoints file: `<wpt>` elements with `<ele>`, `<time>`, `<name>` = local time
  `YYYY-MM-DD HH:MM`.

### 7.2 KML 2.2

One `<Placemark>` per track with a `<LineString>` (`lon,lat,ele` triples separated by
spaces); waypoints as `<Point>` placemarks. Names as in GPX.

### 7.3 CSV

Header: `track,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used`. `track` is the track
name. Empty value when the field is not logged. Waypoints CSV:
`name,time_utc,lat,lon,ele_m`.

### 7.4 BIN

The stored raw image exactly as read from the device.
