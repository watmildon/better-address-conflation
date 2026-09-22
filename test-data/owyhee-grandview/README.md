# owyhee-grandview

A 0.06° × 0.06° window (about 5 km × 6.7 km) around Grand View in Owyhee County, Idaho, bbox
`42.960,-116.130,43.020,-116.070`. Rural: a tiny town core, farms along the Snake River, big
parcels. Suggested by another mapper as a benchmark because the county's addresses were
imported as parcel-centroid nodes (Allison P, December 2024; barkerid, 2025) and then merged
onto building outlines by hand, which was never finished.

OSM snapshot taken 2026-09-22. Data © OpenStreetMap contributors, ODbL. `oa/parcels.geojson`
is Idaho's statewide parcel layer (IDWR, via the OpenAddresses `us/id/statewide` source);
`oa/buildings.geojson` is Microsoft's US Building Footprints for Idaho clipped to the window,
in OpenAddresses format, for use as a hint layer.

## What is in it

| | count |
|-|------:|
| buildings | 580 (`yes` 349, `detached` 198, `house` 22, `apartments` 9) |
| buildings with an address | 228 |
| unmerged parcel-centroid address nodes | 42 |
| parcels (buffered bbox) | 429 |
| Microsoft footprints (buffered bbox) | 435 |

County-wide, 92 % of the imported addresses are already on buildings (4181 of 4552) and 371
plain nodes remain; this window has the densest remaining cluster.

## Why it is different

* **No tag hints, but a convention.** Buildings are almost all `yes` or `detached`. In this
  area mappers used `detached` for houses and `yes` for barns and shops, so a 476 m² `yes`
  barn next to a 235 m² `detached` house is the normal case, not the exception.
* **Big parcels.** The merged houses sit a median 15 m from their parcel centroid, 79 m at the
  90th percentile, versus 3 m in Glendale. A fixed match radius is the wrong tool here;
  parcels as cells are what make it work.
* **The unmerged nodes are where OSM has no building.** Of the 42, 28 sit exactly on their
  parcel centroid and their nearest mapped building is a median 86 m away (75th percentile
  400 m). Sixteen are a subdivision (Estate Place) with no buildings in OSM or in the
  Microsoft footprints, so they are probably vacant lots.

## Results (see `OwyheeReportTest`)

| Run | Result |
|---|---|
| building-centroid points + parcels | 100 % |
| parcel-centroid points + parcels | 93.9 %; the misses are barns bigger than the house, now mostly in the Ambiguous bucket |
| parcel-centroid points + Voronoi, 30 m | 87.8 %, with 73 addresses finding no building within 30 m |
| parcel-centroid points + Voronoi, 120 m | 86.4 % |
| no OSM buildings, Microsoft footprints as hints | 86 % of hinted nodes land inside the true building |
| cleanup of the 42 real unmerged nodes, edit layer as its own source, footprints as hints | 5 merged onto the only building in the parcel, 10 moved onto a hinted footprint, 16 left as no-building, 7 sent to review, 3 flagged against an existing address |

Two engine rules came out of this bed: plain `building=yes` is demoted when the cell also has
a building with an explicit value (unless the point is inside the `yes` building), and a match
further than 100 m from the address point goes to review even when it is the only building
in the parcel, because on a big rural parcel that is often a pump house.
