"""Generates synthetic logger images and golden outputs in fixtures/.

All locations are synthetic paths in public places; no real tracks are used.

    python3 tools/gen_fixtures.py          # needs timezonefinder

Each fixtures/<name>/ contains:
    image.bin      raw flash image as the app would download it
    meta.json      flash_id, write_addr, now (epoch), split_gap_s
    tracks.gpx, tracks.kml, tracks.csv, waypoints.gpx, waypoints.csv
    summary.json   counters and per-track statistics
"""
from __future__ import annotations

import json
import math
import os
import struct
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "reference"))
import a747  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "fixtures")
NOW = int(datetime(2026, 11, 10, tzinfo=timezone.utc).timestamp())
MASK_OLD = 0x0002003F  # UTC VALID LAT LON HEIGHT SPEED RCR
MASK_NEW = 0x0002143F  # + HDOP NSAT


def ts(*a) -> int:
    return int(datetime(*a, tzinfo=timezone.utc).timestamp())


def f32(x: float) -> float:
    return struct.unpack("<f", struct.pack("<f", x))[0]


class Writer:
    """Writes records the way the logger does, into a ring of `flash` bytes."""

    def __init__(self, flash: int, mask: int):
        self.flash = flash
        self.mem = bytearray(b"\xFF" * flash)
        self.mask = mask
        self.addr = 0  # logical address, may exceed flash (wraps)
        self.count = 0
        self._new_sector()

    def _put(self, data: bytes):
        for k, b in enumerate(data):
            self.mem[(self.addr + k) % self.flash] = b
        self.addr += len(data)

    def _new_sector(self):
        start = self.addr
        hdr = struct.pack("<HIHIII", 0xFFFF, self.mask, 2, 30, 100, 0)
        for k in range(a747.SECTOR):  # erase sector
            self.mem[(start + k) % self.flash] = 0xFF
        self._put(hdr + b"\xFF" * (a747.HEADER - len(hdr)))
        self.sector_start = start
        self.count = 0

    def _room(self, n: int):
        end = self.sector_start + a747.SECTOR
        if self.addr + n > end:
            # close sector: write record count into header
            p = self.sector_start % self.flash
            self.mem[p:p + 2] = struct.pack("<H", self.count)
            self.addr = end
            self._new_sector()

    def separator(self, kind: int, value: int):
        self._room(16)
        self._put(b"\xAA" * 7 + bytes([kind]) + struct.pack("<I", value) + b"\xBB" * 4)
        if kind == 0x02:
            self.mask = value

    def record(self, utc, lat, lon, height, speed, rcr=1, valid=2, hdop=None, sats=None,
               corrupt=False):
        body = struct.pack("<IHddff", utc, valid, lat, lon, height, speed)
        if self.mask & (1 << 10):
            body += struct.pack("<H", int(round((hdop or 1.0) * 100)))
        if self.mask & (1 << 12):
            body += bytes([(sats or 8) + 3, sats or 8])
        body += struct.pack("<H", rcr)
        crc = 0
        for b in body:
            crc ^= b
        if corrupt:
            crc ^= 0x55
        self._room(len(body) + 2)
        self._put(body + b"*" + bytes([crc]))
        self.count += 1


def walk(w: Writer, start_utc, lat0, lon0, n, step_s=3, heading0=0.0, ele0=50.0,
         rollover=True, **kw):
    """A smooth synthetic walk/ride. Returns the last (utc, lat, lon)."""
    lat, lon, t = lat0, lon0, start_utc
    for i in range(n):
        heading = heading0 + 40 * math.sin(i / 37)
        v = 4.0 + 3.0 * (1 + math.sin(i / 53))  # m/s
        d = v * step_s
        lat += d * math.cos(math.radians(heading)) / 111_320
        lon += d * math.sin(math.radians(heading)) / (111_320 * math.cos(math.radians(lat)))
        ele = ele0 + 25 * math.sin(i / 90) + 4 * math.sin(i / 7)
        raw = t - a747.ROLLOVER if rollover else t
        w.record(raw, lat, lon, f32(ele), f32(v * 3.6), **kw)
        t += step_s
    return t, lat, lon


def scenario_basic():
    """Linear log on a 4 MB chip: format change, waypoints, no-fix, corrupt and duplicate
    records, a log off/on, three tracks over two days in Lisbon."""
    w = Writer(4 * 1024 * 1024, MASK_OLD)
    t, lat, lon = walk(w, ts(2026, 5, 1, 7, 12), 38.7071, -9.1355, 150, heading0=80)
    w.record(t - a747.ROLLOVER, lat, lon, f32(40.0), f32(0.0), rcr=0x8)  # button
    w.record(t + 3 - a747.ROLLOVER, 0.0, 0.0, f32(0.0), f32(0.0), valid=1)  # no fix
    t, lat, lon = walk(w, t + 6, lat, lon, 150, heading0=120)
    last = (t - 3 - a747.ROLLOVER, lat, lon)
    w.record(last[0], last[1], last[2], f32(41.0), f32(10.0))  # duplicate of last point
    w.record(t - a747.ROLLOVER, lat, lon, f32(41.0), f32(10.0), corrupt=True)
    w.separator(0x07, 0x0104)  # logging off
    w.separator(0x07, 0x0106)  # logging on, 2 h later
    t, lat, lon = walk(w, t + 7200, 38.6916, -9.2160, 400, heading0=60)
    w.separator(0x02, MASK_NEW)
    # next day, crosses into sector 1
    t, lat, lon = walk(w, ts(2026, 5, 2, 22, 40), 38.7223, -9.1393, 1900, heading0=200,
                       hdop=0.9, sats=9)
    w.record(t - a747.ROLLOVER, lat, lon, f32(60.0), f32(0.0), rcr=0x8, hdop=0.8, sats=10)
    image = bytes(w.mem[:w.addr])
    return image, {"flash_id": 0x1C31161C, "write_addr": w.addr}


def scenario_wrapped():
    """A 256 KB chip whose log wrapped: the oldest data was overwritten. The image is the
    full 512 KB the firmware thinks it has (mirror), as read by a naive downloader.
    Tbilisi, a ride across local midnight."""
    flash = 256 * 1024
    w = Writer(flash, MASK_NEW)
    t = ts(2026, 8, 14, 15, 0)
    lat, lon = 41.6938, 44.8015
    for k in range(5):
        t, lat, lon = walk(w, t, lat, lon, 1500, heading0=30 + 70 * k, hdop=1.2, sats=7)
        if k == 2:
            w.record(t - a747.ROLLOVER, lat, lon, f32(500.0), f32(0.0), rcr=0x8, hdop=1.0,
                     sats=8)
        t += 3600 * (2 if k % 2 else 3)
    assert flash < w.addr < 2 * flash, hex(w.addr)
    image = bytes(w.mem) * 2
    return image, {"flash_id": 0x1C311200 | 0x1C, "write_addr": w.addr}


def golden(name, image, meta):
    d = os.path.join(ROOT, name)
    os.makedirs(d, exist_ok=True)
    meta = {**meta, "now": NOW, "split_gap_s": 1800}
    r = a747.parse_image(image, meta["flash_id"], meta["write_addr"], NOW)
    tracks = a747.split_tracks(r.points, meta["split_gap_s"])
    files = {
        "image.bin": image,
        "meta.json": json.dumps({**meta, "flash_id": f"{meta['flash_id']:08X}",
                                 "write_addr": f"{meta['write_addr']:08X}"}, indent=2) + "\n",
        "tracks.gpx": a747.to_gpx(tracks),
        "tracks.kml": a747.to_kml(tracks, r.waypoints),
        "tracks.csv": a747.to_csv(tracks),
        "waypoints.gpx": a747.waypoints_gpx(r.waypoints),
        "waypoints.csv": a747.waypoints_csv(r.waypoints),
        "summary.json": json.dumps({
            "flash_size": a747.flash_size(meta["flash_id"]),
            "points": len(r.points), "waypoints": len(r.waypoints), "corrupt": r.corrupt,
            "dropped_no_fix": r.dropped_no_fix, "dropped_bad_time": r.dropped_bad_time,
            "duplicates": r.duplicates,
            "tracks": [{**a747.stats(t), "name": a747.local_name(t[0])} for t in tracks],
        }, indent=2) + "\n",
    }
    for fn, content in files.items():
        mode = "wb" if isinstance(content, bytes) else "w"
        with open(os.path.join(d, fn), mode, **({} if mode == "wb" else {"newline": "\n"})) as f:
            f.write(content)
    print(name, len(image), "bytes,", len(r.points), "points,", len(tracks), "tracks,",
          len(r.waypoints), "waypoints, corrupt", r.corrupt, "dups", r.duplicates,
          "no-fix", r.dropped_no_fix)


if __name__ == "__main__":
    golden("basic", *scenario_basic())
    golden("wrapped", *scenario_wrapped())
