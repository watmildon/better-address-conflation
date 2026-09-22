#!/usr/bin/env python3
"""Fetch address and parcel layers for a bbox and write them in OpenAddresses output format.

OpenAddresses (OA) processed data is line-delimited GeoJSON: one Feature per line, with the
standardized properties

    addresses: id, number, street, unit, city, district, region, postcode, hash
    parcels:   id, pid, hash

This script reads an OA source definition (schema 2, ESRI protocol layers only), queries each
layer's ArcGIS FeatureServer for the bbox, applies the source's `conform` mapping, and writes
`<layer>.geojson` files that look like what batch.openaddresses.io would produce. Only the OA
properties are kept, so owner names and other non-OA fields from the county feed are dropped.

Usage:
  fetch_oa_layers.py <source.json|URL> <south,west,north,east> <out_dir> [--layers addresses,parcels]

Example (Maricopa County, AZ):
  fetch_oa_layers.py https://raw.githubusercontent.com/openaddresses/openaddresses/master/sources/us/az/maricopa.json \
      33.560,-112.180,33.570,-112.170 test-data/glendale-olive/oa
"""
import argparse
import hashlib
import json
import math
import os
import sys
import urllib.parse
import urllib.request

PAGE = 1000


def load_source(ref):
    if ref.startswith("http://") or ref.startswith("https://"):
        with urllib.request.urlopen(ref, timeout=60) as r:
            return json.load(r)
    with open(ref) as f:
        return json.load(f)


def esri_query(url, bbox, offset, where=None):
    south, west, north, east = bbox
    params = {
        "geometry": f"{west},{south},{east},{north}",
        "geometryType": "esriGeometryEnvelope",
        "inSR": "4326",
        "spatialRel": "esriSpatialRelIntersects",
        "outFields": "*",
        "outSR": "4326",
        "f": "geojson",
        "resultOffset": str(offset),
        "resultRecordCount": str(PAGE),
    }
    if where:
        params["where"] = where
    q = url.rstrip("/") + "/query?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(q, headers={"User-Agent": "Mozilla/5.0 (better-address-conflation testbed)"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def fetch_all(url, bbox, where=None):
    feats = []
    offset = 0
    while True:
        d = esri_query(url, bbox, offset, where)
        if "error" in d:
            raise SystemExit(f"{url}: {d['error']}")
        page = d.get("features", [])
        feats.extend(page)
        more = d.get("properties", {}).get("exceededTransferLimit") or d.get("exceededTransferLimit")
        if not page or (len(page) < PAGE and not more):
            break
        offset += len(page)
    return feats


def conform_value(props, spec):
    """OA conform values are a field name or a list of field names joined by spaces."""
    if spec is None:
        return ""
    if isinstance(spec, list):
        parts = [str(props.get(f) or "").strip() for f in spec]
        return " ".join(p for p in parts if p)
    if isinstance(spec, dict):
        # Function-style conform (regexp, join, ...) is not implemented here.
        raise SystemExit(f"function-style conform not supported: {spec}")
    return str(props.get(spec) or "").strip()


def oa_hash(geometry, props):
    h = hashlib.md5(json.dumps([geometry, props], sort_keys=True).encode()).hexdigest()
    return h[:16]


def polygon_centroid(geom):
    """Area centroid of the largest ring of a Polygon/MultiPolygon, as a Point geometry."""
    polys = [geom["coordinates"]] if geom["type"] == "Polygon" else list(geom["coordinates"])
    best = None
    for poly in polys:
        ring = poly[0]
        lon0 = sum(p[0] for p in ring) / len(ring)
        lat0 = sum(p[1] for p in ring) / len(ring)
        kx = 111320.0 * math.cos(math.radians(lat0))
        ky = 110540.0
        pts = [((p[0] - lon0) * kx, (p[1] - lat0) * ky) for p in ring]
        a = cx = cy = 0.0
        for i in range(len(pts)):
            x1, y1 = pts[i]
            x2, y2 = pts[(i + 1) % len(pts)]
            cross = x1 * y2 - x2 * y1
            a += cross
            cx += (x1 + x2) * cross
            cy += (y1 + y2) * cross
        if abs(a) < 1e-9:
            c = (lon0, lat0, 0)
        else:
            a *= 0.5
            c = (lon0 + cx / (6 * a) / kx, lat0 + cy / (6 * a) / ky, abs(a))
        if best is None or c[2] > best[2]:
            best = c
    return {"type": "Point", "coordinates": [best[0], best[1]]}


def to_oa_feature(layer, feat, conform):
    p = feat.get("properties", {})
    g = feat.get("geometry")
    if layer == "addresses" and g and g.get("type") in ("Polygon", "MultiPolygon"):
        # A parcel layer used as an address source: OpenAddresses places the
        # address at the polygon centroid. This is the "address on parcel
        # centroid" case the conflation engine has to handle.
        g = polygon_centroid(g)
    if layer == "addresses":
        out = {
            "id": conform_value(p, conform.get("id")),
            "number": conform_value(p, conform.get("number")),
            "street": conform_value(p, conform.get("street")),
            "unit": conform_value(p, conform.get("unit")),
            "city": conform_value(p, conform.get("city")),
            "district": conform_value(p, conform.get("district")),
            "region": conform_value(p, conform.get("region")),
            "postcode": conform_value(p, conform.get("postcode")),
        }
    elif layer == "parcels":
        out = {"id": conform_value(p, conform.get("id")), "pid": conform_value(p, conform.get("pid"))}
    elif layer == "buildings":
        out = {"id": conform_value(p, conform.get("id")), "height": conform_value(p, conform.get("height"))}
    else:
        raise SystemExit(f"unsupported layer {layer}")
    out["hash"] = oa_hash(g, out)
    return {"type": "Feature", "properties": out, "geometry": g}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("bbox", help="south,west,north,east")
    ap.add_argument("out_dir")
    ap.add_argument("--layers", default="addresses,parcels")
    ap.add_argument("--suffix", default="", help="appended to output file names, e.g. -parcel-situs")
    a = ap.parse_args()

    src = load_source(a.source)
    if src.get("schema") != 2:
        raise SystemExit("only OA schema 2 sources are supported")
    bbox = tuple(float(x) for x in a.bbox.split(","))
    os.makedirs(a.out_dir, exist_ok=True)

    for layer in a.layers.split(","):
        entries = src.get("layers", {}).get(layer, [])
        if not entries:
            print(f"{layer}: not in source, skipped", file=sys.stderr)
            continue
        entry = entries[0]
        if entry.get("protocol") != "ESRI":
            raise SystemExit(f"{layer}: only ESRI protocol sources are supported")
        feats = fetch_all(entry["data"], bbox, entry.get("_where"))
        conform = entry.get("conform", {})
        if layer == "addresses" and conform.get("number"):
            feats = [f for f in feats if conform_value(f.get("properties", {}), conform["number"])]
        path = os.path.join(a.out_dir, f"{layer}{a.suffix}.geojson")
        with open(path, "w") as f:
            for feat in feats:
                f.write(json.dumps(to_oa_feature(layer, feat, conform), separators=(",", ":")) + "\n")
        print(f"{layer}: {len(feats)} features -> {path}")


if __name__ == "__main__":
    main()
