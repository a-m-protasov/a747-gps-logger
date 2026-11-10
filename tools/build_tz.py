"""Builds shared/timezones.bin from timezone-boundary-builder ("with oceans" GeoJSON).

    .venv/bin/python tools/build_tz.py combined-with-oceans-now.json [tolerance_deg]

Source: https://github.com/evansiroky/timezone-boundary-builder (ODbL 1.0).

Format (little-endian), see docs/protocol.md §4.6:
    "A7TZ" u16 version=1 u16 zone_count
    per zone: u8 name_len, name (ASCII),
              i32 min_lon, min_lat, max_lon, max_lat   (1e-5 degrees)
              u32 ring_count, per ring: u32 n, n × (i32 lon, i32 lat)
"""
import json
import struct
import sys

from shapely.geometry import shape

SCALE = 100_000


def q(v: float) -> int:
    return int(round(v * SCALE))


def rings(geom):
    polys = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
    for p in polys:
        if p.is_empty:
            continue
        yield p.exterior.coords
        for hole in p.interiors:
            yield hole.coords


def main():
    src = sys.argv[1]
    tol = float(sys.argv[2]) if len(sys.argv) > 2 else 0.01
    features = json.load(open(src))["features"]
    # land zones first, then Etc/GMT ocean zones; sorted by name for a stable file
    features.sort(key=lambda f: (f["properties"]["tzid"].startswith("Etc/"), f["properties"]["tzid"]))
    out = bytearray(b"A7TZ" + struct.pack("<HH", 1, len(features)))
    total = 0
    for f in features:
        name = f["properties"]["tzid"].encode("ascii")
        g = shape(f["geometry"]).simplify(tol, preserve_topology=True)
        rs = []
        for coords in rings(g):
            pts = []
            for x, y in coords:
                p = (q(x), q(y))
                if not pts or pts[-1] != p:
                    pts.append(p)
            if len(pts) >= 4:
                rs.append(pts)
        xs = [p[0] for r in rs for p in r]
        ys = [p[1] for r in rs for p in r]
        out += struct.pack("<B", len(name)) + name
        out += struct.pack("<iiii", min(xs), min(ys), max(xs), max(ys))
        out += struct.pack("<I", len(rs))
        for r in rs:
            out += struct.pack("<I", len(r))
            for x, y in r:
                out += struct.pack("<ii", x, y)
            total += len(r)
    open("shared/timezones.bin", "wb").write(out)
    print(len(features), "zones,", total, "points,", len(out), "bytes")


if __name__ == "__main__":
    main()
