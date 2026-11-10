"""Reference implementation of docs/protocol.md (parsing, clean-up, splitting, export).

It produces the golden files in fixtures/. The Android and macOS apps must produce
byte-identical output from the same input. When this file and docs/protocol.md disagree,
fix whichever is wrong and regenerate the fixtures.

Time zones come from shared/timezones.bin (built by tools/build_tz.py). No dependencies.
"""
from __future__ import annotations

import math
import struct
from dataclasses import dataclass, field
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

SECTOR = 0x10000
HEADER = 0x200
ROLLOVER = 1024 * 7 * 86400
ROLLOVER_FLOOR = int(datetime(2008, 1, 1, tzinfo=timezone.utc).timestamp())
EARTH_R = 6371008.8

# bit: (name, size)
FIELDS = {
    0: ("utc", 4), 1: ("valid", 2), 2: ("lat", 8), 3: ("lon", 8), 4: ("height", 4),
    5: ("speed", 4), 6: ("heading", 4), 7: ("dsta", 2), 8: ("dage", 4), 9: ("pdop", 2),
    10: ("hdop", 2), 11: ("vdop", 2), 12: ("nsat", 2), 17: ("rcr", 2), 18: ("ms", 2),
    19: ("distance", 8),
}
SID_BITS = (1 << 13) | (1 << 14) | (1 << 15) | (1 << 16)
RCR_BUTTON = 0x8
VALID_NO_FIX = 0x0001


class UnsupportedFormat(Exception):
    pass


@dataclass
class Point:
    utc: int
    lat: float
    lon: float
    height: float | None = None
    speed: float | None = None
    hdop: float | None = None
    sats_used: int | None = None
    valid: int | None = None
    rcr: int | None = None


@dataclass
class ParseResult:
    points: list[Point] = field(default_factory=list)
    waypoints: list[Point] = field(default_factory=list)
    corrupt: int = 0
    dropped_no_fix: int = 0
    dropped_bad_time: int = 0
    duplicates: int = 0


def flash_size(flash_id: int) -> int:
    code = (flash_id >> 8) & 0xFF  # bytes: manufacturer, type, capacity, manufacturer
    if 0x10 <= code <= 0x18:
        return 1 << code  # JEDEC capacity code = log2(bytes)
    return 8 * 1024 * 1024


def record_size(mask: int) -> int:
    if mask & SID_BITS:
        raise UnsupportedFormat(f"log format {mask:08X} contains satellite data (SID)")
    return 2 + sum(size for bit, (_, size) in FIELDS.items() if mask & (1 << bit))


def is_separator(b: bytes, i: int) -> bool:
    return b[i:i + 7] == b"\xAA" * 7 and b[i + 12:i + 16] == b"\xBB" * 4


def sector_order(f: int, w: int) -> list[tuple[int, int]]:
    """(sector start, data end) pairs in chronological order (protocol §4.1)."""
    n = f // SECTOR
    if w <= f:
        last = max(0, -(-w // SECTOR) - 1)
        return [(s * SECTOR, min((s + 1) * SECTOR, w)) for s in range(last + 1)]
    wm = w % f
    newest = wm // SECTOR
    order = [(newest + 1 + k) % n for k in range(n - 1)] + [newest]
    return [(s * SECTOR, (s + 1) * SECTOR if s != newest else wm) for s in order]


def parse_record(b: bytes, i: int, mask: int, size: int) -> Point | None:
    rec = b[i:i + size]
    if len(rec) < size or rec[-2] != 0x2A:
        return None
    crc = 0
    for x in rec[:-2]:
        crc ^= x
    if crc != rec[-1]:
        return None
    vals = {}
    o = 0
    for bit in sorted(FIELDS):
        if not mask & (1 << bit):
            continue
        name, sz = FIELDS[bit]
        chunk = rec[o:o + sz]
        if name in ("lat", "lon", "distance"):
            vals[name] = struct.unpack("<d", chunk)[0]
        elif name in ("height", "speed", "heading", "dage"):
            vals[name] = struct.unpack("<f", chunk)[0]
        elif name == "utc":
            vals[name] = struct.unpack("<I", chunk)[0]
        elif name == "nsat":
            vals["sats_used"] = chunk[1]
        elif name in ("pdop", "hdop", "vdop"):
            vals[name] = struct.unpack("<H", chunk)[0] / 100
        else:
            vals[name] = struct.unpack("<H", chunk)[0]
        o += sz
    if "utc" not in vals or "lat" not in vals or "lon" not in vals:
        raise UnsupportedFormat(f"log format {mask:08X} lacks UTC/LAT/LON")
    return Point(utc=vals["utc"], lat=vals["lat"], lon=vals["lon"],
                 height=vals.get("height"), speed=vals.get("speed"), hdop=vals.get("hdop"),
                 sats_used=vals.get("sats_used"), valid=vals.get("valid"), rcr=vals.get("rcr"))


def fix_time(t: int, now: int) -> int | None:
    while t < ROLLOVER_FLOOR:
        t += ROLLOVER
    return None if t > now + 86400 else t


def parse_image(image: bytes, flash_id: int, write_addr: int, now: int) -> ParseResult:
    f = flash_size(flash_id)
    image = image[:f]
    w = write_addr
    res = ParseResult()
    raw: list[Point] = []
    for start, end in sector_order(f, w):
        end = min(end, len(image))
        if start + HEADER > end:
            continue
        mask = struct.unpack_from("<I", image, start + 2)[0]
        if mask == 0xFFFFFFFF:
            continue
        size = record_size(mask)
        i = start + HEADER
        resyncing = False  # a run of unreadable bytes counts as one corrupt record
        while i + 4 <= end:
            if image[i:i + 4] == b"\xFF\xFF\xFF\xFF":
                break
            if i + 16 <= end and is_separator(image, i):
                if image[i + 7] == 0x02:
                    mask = struct.unpack_from("<I", image, i + 8)[0]
                    size = record_size(mask)
                i += 16
                resyncing = False
                continue
            if i + size > end:
                break
            p = parse_record(image, i, mask, size)
            if p is None:
                if not resyncing:
                    res.corrupt += 1
                    resyncing = True
                i += 1
                continue
            resyncing = False
            raw.append(p)
            i += size
    seen_t, seen_w = set(), set()
    for p in raw:
        if p.valid == VALID_NO_FIX or (p.lat == 0 and p.lon == 0):
            res.dropped_no_fix += 1
            continue
        t = fix_time(p.utc, now)
        if t is None:
            res.dropped_bad_time += 1
            continue
        p.utc = t
        key = (p.utc, p.lat, p.lon)
        is_wpt = p.rcr is not None and p.rcr & RCR_BUTTON
        seen = seen_w if is_wpt else seen_t
        if key in seen:
            res.duplicates += 1
            continue
        seen.add(key)
        (res.waypoints if is_wpt else res.points).append(p)
    res.points.sort(key=lambda p: p.utc)
    res.waypoints.sort(key=lambda p: p.utc)
    return res


def split_tracks(points: list[Point], gap_s: int = 1800) -> list[list[Point]]:
    tracks: list[list[Point]] = []
    for p in points:
        if tracks and p.utc - tracks[-1][-1].utc <= gap_s:
            tracks[-1].append(p)
        else:
            tracks.append([p])
    return tracks


def haversine(a: Point, b: Point) -> float:
    p1, p2 = math.radians(a.lat), math.radians(b.lat)
    dp, dl = p2 - p1, math.radians(b.lon - a.lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_R * math.asin(min(1.0, math.sqrt(h)))


def stats(track: list[Point]) -> dict:
    dist = moving = 0.0
    seg_max = 0.0
    for a, b in zip(track, track[1:]):
        d = haversine(a, b)
        dt = b.utc - a.utc
        dist += d
        if dt > 0 and d / dt * 3.6 > 1:
            moving += dt
        if dt >= 2:
            seg_max = max(seg_max, d / dt * 3.6)
    speeds = [p.speed for p in track if p.speed is not None]
    asc = desc = 0.0
    ref = None
    for p in track:
        if p.height is None:
            continue
        if ref is None:
            ref = p.height
        elif abs(p.height - ref) > 3:
            if p.height > ref:
                asc += p.height - ref
            else:
                desc += ref - p.height
            ref = p.height
    return {
        "id": f"{track[0].utc}-{len(track)}",
        "start_utc": track[0].utc,
        "end_utc": track[-1].utc,
        "points": len(track),
        "distance_m": round(dist, 1),
        "duration_s": track[-1].utc - track[0].utc,
        "moving_s": round(moving),
        "avg_speed_kmh": round(dist / moving * 3.6, 1) if moving else 0.0,
        "max_speed_kmh": round(max(speeds) if speeds else seg_max, 1),
        "ascent_m": round(asc, 1),
        "descent_m": round(desc, 1),
    }


TZ_SCALE = 100_000
_zones = None


def _load_zones():
    """shared/timezones.bin, format in tools/build_tz.py."""
    import os
    path = os.path.join(os.path.dirname(__file__), "..", "..", "shared", "timezones.bin")
    b = open(path, "rb").read()
    assert b[:4] == b"A7TZ"
    _, count = struct.unpack_from("<HH", b, 4)
    o = 8
    zones = []
    for _ in range(count):
        n = b[o]
        name = b[o + 1:o + 1 + n].decode("ascii")
        o += 1 + n
        bbox = struct.unpack_from("<iiii", b, o)
        o += 16
        (rc,) = struct.unpack_from("<I", b, o)
        o += 4
        rings = []
        for _ in range(rc):
            (pc,) = struct.unpack_from("<I", b, o)
            o += 4
            pts = struct.unpack_from(f"<{2 * pc}i", b, o)
            o += 8 * pc
            rings.append(pts)
        zones.append((name, bbox, rings))
    return zones


def _inside(x: int, y: int, rings) -> bool:
    """Even-odd ray casting in integer coordinates (identical in all implementations)."""
    inside = False
    for r in rings:
        n = len(r) // 2
        j = n - 1
        for i in range(n):
            xi, yi, xj, yj = r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1]
            if (yi > y) != (yj > y):
                # x < xi + (xj - xi) * (y - yi) / (yj - yi)
                lhs = (x - xi) * (yj - yi)
                rhs = (xj - xi) * (y - yi)
                if (lhs < rhs) if yj > yi else (lhs > rhs):
                    inside = not inside
            j = i
    return inside


def tz_name(lat: float, lon: float) -> str:
    global _zones
    if _zones is None:
        _zones = _load_zones()
    x, y = int(round(lon * TZ_SCALE)), int(round(lat * TZ_SCALE))
    for name, (x0, y0, x1, y1), rings in _zones:
        if x0 <= x <= x1 and y0 <= y <= y1 and _inside(x, y, rings):
            return name
    return "UTC"


def tz_at(lat: float, lon: float) -> ZoneInfo:
    return ZoneInfo(tz_name(lat, lon))


def local_name(p: Point) -> str:
    return datetime.fromtimestamp(p.utc, tz_at(p.lat, p.lon)).strftime("%Y-%m-%d %H:%M")


def iso(t: int) -> str:
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _gpx_extras(p: Point) -> str:
    s = f"<ele>{p.height:.1f}</ele>" if p.height is not None else ""
    s += f"<time>{iso(p.utc)}</time>"
    if p.sats_used is not None:
        s += f"<sat>{p.sats_used}</sat>"
    if p.hdop is not None:
        s += f"<hdop>{p.hdop:.2f}</hdop>"
    return s


GPX_HEAD = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<gpx version="1.1" creator="A747 GPS Logger" xmlns="http://www.topografix.com/GPX/1/1">\n')


def to_gpx(tracks: list[list[Point]]) -> str:
    out = [GPX_HEAD]
    for t in tracks:
        out.append(f"<trk><name>{local_name(t[0])}</name>\n<trkseg>\n")
        for p in t:
            out.append(f'<trkpt lat="{p.lat:.7f}" lon="{p.lon:.7f}">{_gpx_extras(p)}</trkpt>\n')
        out.append("</trkseg>\n</trk>\n")
    out.append("</gpx>\n")
    return "".join(out)


def waypoints_gpx(wpts: list[Point]) -> str:
    out = [GPX_HEAD]
    for p in wpts:
        ele = f"<ele>{p.height:.1f}</ele>" if p.height is not None else ""
        out.append(f'<wpt lat="{p.lat:.7f}" lon="{p.lon:.7f}">{ele}<time>{iso(p.utc)}</time>'
                   f"<name>{local_name(p)}</name></wpt>\n")
    out.append("</gpx>\n")
    return "".join(out)


def _kml_coord(p: Point) -> str:
    return f"{p.lon:.7f},{p.lat:.7f},{p.height:.1f}" if p.height is not None else f"{p.lon:.7f},{p.lat:.7f}"


def to_kml(tracks: list[list[Point]], wpts: list[Point]) -> str:
    out = ['<?xml version="1.0" encoding="UTF-8"?>\n'
           '<kml xmlns="http://www.opengis.net/kml/2.2">\n<Document>\n']
    for t in tracks:
        coords = " ".join(_kml_coord(p) for p in t)
        out.append(f"<Placemark><name>{local_name(t[0])}</name>"
                   f"<LineString><coordinates>{coords}</coordinates></LineString></Placemark>\n")
    for p in wpts:
        out.append(f"<Placemark><name>{local_name(p)}</name>"
                   f"<Point><coordinates>{_kml_coord(p)}</coordinates></Point></Placemark>\n")
    out.append("</Document>\n</kml>\n")
    return "".join(out)


def _opt(v, fmt):
    return "" if v is None else format(v, fmt)


def to_csv(tracks: list[list[Point]]) -> str:
    out = ["track,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used\n"]
    for t in tracks:
        name = local_name(t[0])
        for p in t:
            out.append(f"{name},{iso(p.utc)},{p.lat:.7f},{p.lon:.7f},{_opt(p.height, '.1f')},"
                       f"{_opt(p.speed, '.1f')},{_opt(p.hdop, '.2f')},{_opt(p.sats_used, 'd')}\n")
    return "".join(out)


def waypoints_csv(wpts: list[Point]) -> str:
    out = ["name,time_utc,lat,lon,ele_m\n"]
    for p in wpts:
        out.append(f"{local_name(p)},{iso(p.utc)},{p.lat:.7f},{p.lon:.7f},{_opt(p.height, '.1f')}\n")
    return "".join(out)
