# colonie-ny

A 1 km × 1 km block of Colonie, New York (Albany County), bbox `42.740,-73.760,42.750,-73.750`,
between Loudon Road and Troy Schenectady Road. Chosen as the **real parcel-centroid** bed: New
York publishes statewide tax-parcel centroid points carrying the situs address, plus the parcel
polygons, plus a separate rooftop address-point layer (SAM) that serves as an independent
cross-check.

OSM snapshot taken 2026-09-21. Data © OpenStreetMap contributors, ODbL. The `oa/` files come
from NYS GIS Program Office services (tax parcels, parcel centroids, SAM address points),
reshaped into the OpenAddresses output format with owner and valuation fields dropped. Source
definitions: `tools/testbed/sources/us-ny-parcel-centroids.json` and `us-ny-sam-rooftop.json`.

## What is in it

| | count |
|-|------:|
| buildings | 924 |
| buildings with an address | 581 |
| POI / entrance nodes with an address (commercial strip) | 279 |
| outbuildings (`shed` 176, `garage` 135, `canopy` 2) | 313 |
| NYS parcel centroids with a situs address (buffered bbox) | 725 |
| NYS parcels (buffered bbox) | 758 |
| NYS SAM address points (buffered bbox) | 1146 |

Building values: house 539, shed 176, garage 135, yes 46, apartments 11, retail 7.

## Where the points sit

| Layer | inside a building | median to nearest building centroid | p75 | p90 |
|---|---|---|---|---|
| `oa/addresses.geojson` (parcel centroids) | 36 % | 7.4 m | 15.4 m | 61 m |
| `oa/addresses-sam.geojson` (SAM, mostly rooftop) | 75 % | 4.2 m | 28.5 m | 67 m |

So a third of the parcel centroids land on the house, the rest are in the yard, and every lot
has a shed or a garage competing for the address.

## Files

Same layout as `glendale-olive` (see that README): `snapshot.osm`, the three stripped edit
layers, the four derived address layers with `testbed:source` links, `truth.json`, and the
`oa/` folder. The extra file here is `oa/addresses-sam.geojson`, the rooftop layer.

Pairings that make sense:

* `buildings-stripped` + `oa/addresses` + `oa/parcels` — the real thing. Scored by matching
  housenumber, street and unit back to `snapshot.osm`.
* `buildings-stripped` + `oa/addresses` (Voronoi) — same without a parcel layer.
* `buildings-stripped` + `oa/addresses-sam` + `oa/parcels` — rooftop baseline.
* `buildings-stripped-generic` + `addresses-full-parcel` + `oa/parcels` — no tag hints, with
  313 outbuildings to get wrong.

## Known ground-truth quirks

Scully Avenue and Aragon Avenue show a consistent one-house shift between the NYS parcel
address and the OSM address on the building the centroid falls in. One of the two is wrong;
the engine follows the parcel and is counted "wrong" against OSM for those six houses.
