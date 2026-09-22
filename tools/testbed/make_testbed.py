#!/usr/bin/env python3
"""Derive a conflation test bed from a "known good" OSM snapshot.

The snapshot is an area where the community has already put addresses on buildings. We strip
those addresses back off and turn them into address-point layers, so the plugin's output can be
scored against what mappers actually did.

Outputs (all in <out_dir>):

  snapshot.osm                      the untouched input (ground truth)
  buildings-stripped.osm            every addr:* removed from buildings; plain address nodes deleted
  buildings-stripped-generic.osm    same, plus every building=* collapsed to building=yes
  buildings-partial.osm             addresses stripped from a random --strip-fraction of buildings
  addresses-full.osm                one node per stripped address, at the building centroid
  addresses-full-parcel.osm         (with --parcels) same addresses, moved to the containing
                                    parcel's centroid, like county address points usually are
  addresses-partial.osm             only the addresses stripped in buildings-partial.osm
  addresses-overlap.osm             addresses-partial plus a sample of addresses that are still on
                                    buildings, some of them perturbed (abbreviated street, dropped
                                    unit, wrong housenumber)
  truth.json                        per generated address node: source building, kind, which
                                    layers it appears in, perturbation applied

Generated address nodes get id = -(source building id) (or -(source node id)) and a
`testbed:source=way/<id>` tag, so the link back to ground truth survives JOSM renumbering
negative ids. Only addr:* tags are copied otherwise.

Usage: make_testbed.py <snapshot.osm> <out_dir> [--seed 42] [--strip-fraction 0.6]
                       [--overlap-fraction 0.15] [--parcels parcels.geojson]
"""
import argparse
import json
import math
import os
import random
import xml.etree.ElementTree as ET

OUTBUILDINGS = {"garage", "garages", "shed", "carport", "roof", "outbuilding", "greenhouse", "barn"}
ABBREV = {
    "Avenue": "Ave", "Drive": "Dr", "Lane": "Ln", "Street": "St", "Road": "Rd", "Court": "Ct",
    "Place": "Pl", "Circle": "Cir", "Boulevard": "Blvd", "Terrace": "Ter", "Trail": "Trl",
    "West": "W", "North": "N", "East": "E", "South": "S",
}


def tags_of(el):
    return {t.get("k"): t.get("v") for t in el.findall("tag")}


def set_tags(el, tags):
    for t in list(el.findall("tag")):
        el.remove(t)
    for k, v in tags.items():
        ET.SubElement(el, "tag", k=k, v=v)


def local_xy(lat, lon, lat0):
    return lon * 111320.0 * math.cos(math.radians(lat0)), lat * 110540.0


def centroid(coords):
    """Area centroid of a ring of (lat, lon); falls back to the vertex mean."""
    if len(coords) < 3:
        return coords[0]
    lat0 = sum(c[0] for c in coords) / len(coords)
    lon0 = sum(c[1] for c in coords) / len(coords)
    # Work relative to the ring's mean point; absolute projected metres lose precision.
    pts = [local_xy(la - lat0, lo - lon0, lat0) for la, lo in coords]
    a = cx = cy = 0.0
    for i in range(len(pts)):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % len(pts)]
        cross = x1 * y2 - x2 * y1
        a += cross
        cx += (x1 + x2) * cross
        cy += (y1 + y2) * cross
    if abs(a) < 1e-6:
        return sum(c[0] for c in coords) / len(coords), sum(c[1] for c in coords) / len(coords)
    a *= 0.5
    cx /= 6 * a
    cy /= 6 * a
    return lat0 + cy / 110540.0, lon0 + cx / (111320.0 * math.cos(math.radians(lat0)))


def point_in_ring(lat, lon, ring):
    """Ray casting; ring is a list of [lon, lat] as in GeoJSON."""
    inside = False
    n = len(ring)
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        if (y1 > lat) != (y2 > lat):
            xint = x1 + (lat - y1) * (x2 - x1) / (y2 - y1)
            if lon < xint:
                inside = not inside
    return inside


def polygon_rings(geom):
    if geom is None:
        return []
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    return []


class Parcels:
    def __init__(self, path):
        self.items = []
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                feat = json.loads(line)
                polys = polygon_rings(feat.get("geometry"))
                if not polys:
                    continue
                pid = feat.get("properties", {}).get("pid", "")
                for poly in polys:
                    outer = poly[0]
                    lons = [p[0] for p in outer]
                    lats = [p[1] for p in outer]
                    bbox = (min(lats), min(lons), max(lats), max(lons))
                    c = centroid([(p[1], p[0]) for p in outer])
                    self.items.append((bbox, poly, pid, c))

    def find(self, lat, lon):
        for bbox, poly, pid, c in self.items:
            if not (bbox[0] <= lat <= bbox[2] and bbox[1] <= lon <= bbox[3]):
                continue
            if point_in_ring(lat, lon, poly[0]) and not any(point_in_ring(lat, lon, h) for h in poly[1:]):
                return pid, c
        return None, None


def perturb(tags, kind, rnd):
    t = dict(tags)
    if kind == "street-abbrev":
        t["addr:street"] = " ".join(ABBREV.get(w, w) for w in t.get("addr:street", "").split())
    elif kind == "unit-dropped":
        t.pop("addr:unit", None)
    elif kind == "housenumber":
        try:
            t["addr:housenumber"] = str(int(t["addr:housenumber"]) + rnd.choice((-2, 2)))
        except ValueError:
            t["addr:housenumber"] = t["addr:housenumber"] + "A"
    return t


def write_osm(path, root_attrs, elements):
    root = ET.Element("osm", root_attrs)
    for el in elements:
        root.append(el)
    ET.indent(root, space="  ")
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def address_node(nid, lat, lon, tags, source):
    """An address node. The testbed:source tag carries the ground-truth link
    (way/123 or node/456) because JOSM may renumber negative ids on load."""
    n = ET.Element("node", id=str(nid), lat=f"{lat:.7f}", lon=f"{lon:.7f}", visible="true")
    for k in sorted(tags):
        if k.startswith("addr:"):
            ET.SubElement(n, "tag", k=k, v=tags[k])
    ET.SubElement(n, "tag", k="testbed:source", v=f"{source['type']}/{source['id']}")
    return n


def strip_addr(el):
    tags = tags_of(el)
    set_tags(el, {k: v for k, v in tags.items() if not k.startswith("addr:")})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("snapshot")
    ap.add_argument("out_dir")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--strip-fraction", type=float, default=0.6)
    ap.add_argument("--overlap-fraction", type=float, default=0.15)
    ap.add_argument("--parcels", help="OpenAddresses parcels.geojson (line-delimited)")
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    os.makedirs(a.out_dir, exist_ok=True)

    tree = ET.parse(a.snapshot)
    src = tree.getroot()
    root_attrs = {"version": "0.6", "generator": "make_testbed.py"}
    nodes = {n.get("id"): n for n in src.findall("node")}
    coords = {i: (float(n.get("lat")), float(n.get("lon"))) for i, n in nodes.items()}
    ways = src.findall("way")
    relations = src.findall("relation")

    # Classify what carries an address in the snapshot.
    buildings = [w for w in ways if "building" in tags_of(w)]
    addressed_bld = sorted((w for w in buildings if "addr:housenumber" in tags_of(w)), key=lambda w: int(w.get("id")))
    plain_addr_nodes, poi_addr_nodes = [], []
    for n in sorted(nodes.values(), key=lambda n: int(n.get("id"))):
        t = tags_of(n)
        if "addr:housenumber" not in t:
            continue
        other = {k for k in t if not k.startswith("addr:") and k not in ("source", "check_date")}
        (poi_addr_nodes if other else plain_addr_nodes).append(n)

    parcels = Parcels(a.parcels) if a.parcels else None

    # Ground-truth address records.
    records = []
    for w in addressed_bld:
        ring = [coords[nd.get("ref")] for nd in w.findall("nd") if nd.get("ref") in coords]
        lat, lon = centroid(ring)
        rec = {"node_id": -int(w.get("id")), "source": {"type": "way", "id": int(w.get("id"))},
               "kind": "building", "building": tags_of(w).get("building"),
               "lat": lat, "lon": lon, "tags": {k: v for k, v in tags_of(w).items() if k.startswith("addr:")}}
        if parcels:
            pid, c = parcels.find(lat, lon)
            rec["parcel"] = pid
            rec["parcel_centroid"] = list(c) if c else None
        records.append(rec)
    for n in plain_addr_nodes + poi_addr_nodes:
        lat, lon = coords[n.get("id")]
        rec = {"node_id": -int(n.get("id")), "source": {"type": "node", "id": int(n.get("id"))},
               "kind": "poi" if n in poi_addr_nodes else "standalone", "building": None,
               "lat": lat, "lon": lon, "tags": {k: v for k, v in tags_of(n).items() if k.startswith("addr:")}}
        if parcels:
            pid, c = parcels.find(lat, lon)
            rec["parcel"] = pid
            rec["parcel_centroid"] = list(c) if c else None
        records.append(rec)

    # Partial / overlap sampling (deterministic given seed).
    for rec in records:
        rec["in"] = {"full": True, "partial": False, "overlap": False}
        rec["perturbation"] = None
    stripped = [r for r in records if rnd.random() < a.strip_fraction]
    for r in stripped:
        r["in"]["partial"] = True
        r["in"]["overlap"] = True
    kept = [r for r in records if not r["in"]["partial"]]
    overlap_extra = [r for r in kept if rnd.random() < a.overlap_fraction]
    for r in overlap_extra:
        r["in"]["overlap"] = True
        roll = rnd.random()
        if roll < 0.15:
            r["perturbation"] = "street-abbrev"
        elif roll < 0.25 and "addr:unit" in r["tags"]:
            r["perturbation"] = "unit-dropped"
        elif roll < 0.33:
            r["perturbation"] = "housenumber"
    by_id = {r["node_id"]: r for r in records}

    def build_layer(name, selector, position="centroid", perturbed=False):
        els = []
        for r in records:
            if not selector(r):
                continue
            lat, lon = r["lat"], r["lon"]
            if position == "parcel" and r.get("parcel_centroid"):
                lat, lon = r["parcel_centroid"]
            tags = perturb(r["tags"], r["perturbation"], rnd) if perturbed and r["perturbation"] else r["tags"]
            els.append(address_node(r["node_id"], lat, lon, tags, r["source"]))
        write_osm(os.path.join(a.out_dir, name), root_attrs, els)
        return len(els)

    def build_buildings(name, strip_ids, generic=False):
        els = []
        for n in nodes.values():
            n2 = ET.fromstring(ET.tostring(n))
            if -int(n.get("id")) in strip_ids:
                if n in plain_addr_nodes:
                    continue  # a bare address point simply doesn't exist yet
                strip_addr(n2)
            els.append(n2)
        for w in ways:
            w2 = ET.fromstring(ET.tostring(w))
            t = tags_of(w2)
            if -int(w.get("id")) in strip_ids:
                strip_addr(w2)
                t = tags_of(w2)
            if generic and "building" in t:
                t["building"] = "yes"
                set_tags(w2, t)
            els.append(w2)
        for r in relations:
            els.append(ET.fromstring(ET.tostring(r)))
        write_osm(os.path.join(a.out_dir, name), root_attrs, els)

    all_ids = {r["node_id"] for r in records}
    partial_ids = {r["node_id"] for r in records if r["in"]["partial"]}
    build_buildings("buildings-stripped.osm", all_ids)
    build_buildings("buildings-stripped-generic.osm", all_ids, generic=True)
    build_buildings("buildings-partial.osm", partial_ids)
    counts = {
        "addresses-full.osm": build_layer("addresses-full.osm", lambda r: True),
        "addresses-partial.osm": build_layer("addresses-partial.osm", lambda r: r["in"]["partial"]),
        "addresses-overlap.osm": build_layer("addresses-overlap.osm", lambda r: r["in"]["overlap"], perturbed=True),
    }
    if parcels:
        counts["addresses-full-parcel.osm"] = build_layer("addresses-full-parcel.osm", lambda r: True, position="parcel")

    # Copy the snapshot verbatim so the directory is self-contained.
    dest = os.path.join(a.out_dir, "snapshot.osm")
    if os.path.abspath(dest) != os.path.abspath(a.snapshot):
        with open(a.snapshot, "rb") as fi, open(dest, "wb") as fo:
            fo.write(fi.read())

    truth = {
        "generator": "tools/testbed/make_testbed.py",
        "seed": a.seed,
        "strip_fraction": a.strip_fraction,
        "overlap_fraction": a.overlap_fraction,
        "counts": {
            "buildings": len(buildings),
            "addressed_buildings": len(addressed_bld),
            "standalone_address_nodes": len(plain_addr_nodes),
            "poi_address_nodes": len(poi_addr_nodes),
            "outbuildings": sum(1 for w in buildings if tags_of(w).get("building") in OUTBUILDINGS),
            **counts,
        },
        "addresses": records,
    }
    with open(os.path.join(a.out_dir, "truth.json"), "w") as f:
        json.dump(truth, f, indent=1)
    print(json.dumps(truth["counts"], indent=1))
    if parcels:
        no_parcel = sum(1 for r in records if r.get("parcel") is None)
        print(f"addresses with no containing parcel: {no_parcel}")


if __name__ == "__main__":
    main()
