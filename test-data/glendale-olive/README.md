# glendale-olive

A 1 km × 1 km block of Glendale, Arizona (bbox `33.560,-112.180,33.570,-112.170`), around
West Olive Avenue and North 55th Avenue. Picked from a scan of Phoenix for cells with dense,
well-addressed buildings and a mix of building types.

OSM snapshot taken 2026-09-21. Data © OpenStreetMap contributors, ODbL. The `oa/` files are
Maricopa County parcels and address points, reshaped into the OpenAddresses output format;
the county publishes them with a "no guarantee of accuracy" disclaimer and OpenAddresses
carries them as `us/az/maricopa`. Only OA properties are kept (no owner or valuation fields).

## What is in it

| | count |
|-|------:|
| buildings | 1105 |
| buildings with an address | 1048 |
| standalone address nodes (a strip on N 52nd Ave with no buildings mapped) | 78 |
| POI nodes with an address | 2 |
| outbuildings (`garages`, `carport`, `roof`) | 22 |
| county parcels (buffered bbox) | 1303 |
| county address points (buffered bbox) | 1547 |

Building values: house 807, apartments 198, yes 46, terrace 22, garages 13, retail 10,
carport 6, roof 3. 30 distinct streets. No multipolygon buildings.

Notable cases:

* **5201 West Olive Avenue**: an apartment complex mapped as 206 separate outlines, each with
  the same housenumber and its own `addr:unit`, all inside one parcel (APN 14820471H). Three
  outlines in that parcel are tagged 5171 in OSM while the parcel says 5201 — a genuine OSM
  error, kept as-is.
* **5116 West Olive Avenue** (APN 14821804): one parcel holding 17 addressed buildings.
* **N 52nd Avenue 9001–9015 strip**: 78 NAD address nodes with no building under them.
* **5325 West Butler Drive**: an assisted-living `amenity` way with `addr:unit=C4` and no
  `building` tag.

## Ground truth quality

For every addressed building, the parcel containing its centroid was looked up and the
parcel's situs address compared (after suffix/directional expansion) with the OSM tags:
1043 agree, 4 disagree (the 5171/5201 mislabels above), 1 parcel has no situs address.
Building centroid to parcel centroid: median 2.7 m, 90th percentile 100 m (driven by the
apartment complex).

## Files

| File | Use as | Contents |
|------|--------|----------|
| `snapshot.osm` | reference | the untouched OSM data (buildings, addresses, highways) |
| `buildings-stripped.osm` | edit layer | every `addr:*` removed from buildings and POIs; bare address nodes deleted |
| `buildings-stripped-generic.osm` | edit layer | same, plus every `building=*` collapsed to `building=yes` (no tag hints for ranking) |
| `buildings-partial.osm` | edit layer | addresses stripped from a random 60 % of buildings; the rest still carry them |
| `addresses-full.osm` | address layer | 1128 nodes, one per stripped address, at the building centroid |
| `addresses-full-parcel.osm` | address layer | same nodes moved to the containing parcel's centroid, like county points |
| `addresses-partial.osm` | address layer | the 656 addresses stripped in `buildings-partial.osm` |
| `addresses-overlap.osm` | address layer | `addresses-partial` plus 74 addresses that are still on buildings, 23 of them perturbed (14 wrong housenumber, 8 abbreviated street, 1 dropped unit) |
| `oa/parcels.geojson` | parcel layer | 1303 county parcels, OpenAddresses format (`id`, `pid`, `hash`) |
| `oa/addresses.geojson` | address layer | 1547 real county address points, OpenAddresses format |
| `truth.json` | scoring | per generated node: source building or node, kind, parcel id, parcel centroid, which layers it is in, perturbation |

Generated address nodes have `id = -(source way id)` or `-(source node id)` plus a
`testbed:source=way/<id>` tag, so the link back to the building survives JOSM renumbering
negative ids on load. Only `addr:*` tags are copied otherwise; the engine ignores the
`testbed:*` key.

Pairings that make sense:

* `buildings-stripped` + `addresses-full` — the easy baseline; everything should be Clean.
* `buildings-stripped` + `addresses-full-parcel` + `oa/parcels` — the real problem: parcel
  centroids, house vs carport, 206 stacked units in one parcel.
* `buildings-stripped-generic` + `addresses-full-parcel` — ranking with no building tags.
* `buildings-partial` + `addresses-overlap` — existing-address sub-buckets.
* `buildings-stripped` + `oa/addresses` + `oa/parcels` — unlinked real county data; score by
  matching housenumber+street against `snapshot.osm`.
