# Test data

Real-world test beds for the conflation engine. Each directory is a "known good" area of OSM
where the community already put addresses on buildings, with the addresses stripped back off
into point layers so the plugin's output can be scored against what mappers actually did.

Regenerate a bed with:

```bash
tools/testbed/fetch_snapshot.sh <south,west,north,east> test-data/<name>/snapshot.osm
tools/testbed/fetch_oa_layers.py <OpenAddresses source.json or URL> <buffered bbox> test-data/<name>/oa
tools/testbed/make_testbed.py test-data/<name>/snapshot.osm test-data/<name> --parcels test-data/<name>/oa/parcels.geojson
```

Buffer the OpenAddresses bbox by ~0.001° so buildings that straddle the OSM bbox still have a
parcel. Generation is deterministic for a given `--seed` (default 42). Source definitions for
services that are not in OpenAddresses (parcel layers used as address sources, NYS parcel
centroids, NYS SAM points) live in `tools/testbed/sources/`; pass `--suffix=-name` to keep
several address layers side by side.

## Where county address points actually sit

Measured 2026-09-21 by fetching each county's address points for one dense residential
0.01° cell and comparing them with OSM buildings there. "Inside" is the share of points that
fall inside any OSM building footprint; distances are to the nearest building centroid.

| Source (OpenAddresses id) | Inside | Median | p90 | Placement |
|---|---|---|---|---|
| us/wi/milwaukee (Wauwatosa) | 97 % | 4.2 m | 8.3 m | rooftop |
| us/vt/statewide (S Burlington) | 98 % | 3.6 m | 6.8 m | rooftop |
| us/md/baltimore (Towson) | 99 % | 2.3 m | 9.8 m | rooftop |
| us/ca/los_angeles (Torrance) | 98 % | 3.0 m | 6.6 m | rooftop |
| us/ca/sonoma (Santa Rosa) | 98 % | 4.8 m | 14.4 m | rooftop |
| us/dc/statewide (Petworth) | 98 % | 2.2 m | 4.4 m | rooftop |
| us/az/maricopa (Glendale) | 71 % | 3.0 m | 6.3 m | rooftop |
| us/in/marion (Indianapolis) | 21 % | 7.9 m | 14.1 m | front of structure; 10 m from parcel centroid |
| us/ca/orange (Anaheim) | 11 % | 12.3 m | 18.4 m | frontage/driveway; 15 m from parcel centroid |
| us/wi/waukesha | | | | service now returns an HTML hub page |
| NYS tax parcel centroids (Colonie) | 36 % | 7.4 m | 61 m | **parcel centroid** |
| NYS SAM points (Colonie) | 75 % | 4.2 m | 67 m | rooftop, with parcel-centroid fallbacks |
| Maricopa parcel situs address at parcel centroid (Glendale) | 68 % | 4.2 m | 84 m | **parcel centroid** |

Takeaways: most county E911/address-point feeds behind the documented US imports are rooftop
points, so the hard case is not the norm. Real parcel-centroid address data comes from
assessor parcel layers (a situs address per parcel) and from statewide parcel-centroid
products such as New York's. In single-family grids a parcel centroid usually still lands on
the house; the trouble is large parcels (complexes, strip malls) and lots with big outbuildings.
NY's SAM points carry an explicit `PointType` (1 rooftop, 4 parcel centroid); parcel-centroid
points are at most 15 % of a county there (Hamilton, Broome).

| Directory | Area | Character |
|-----------|------|-----------|
| [glendale-olive](glendale-olive/) | Glendale AZ, W Olive Ave / N 55th Ave, bbox 33.560,-112.180,33.570,-112.170 | Single-family grid plus a 206-outline apartment complex, terraces, garages/carports, a strip of address points with no buildings, two POIs |
| [owyhee-grandview](owyhee-grandview/) | Grand View, Owyhee County ID, bbox 42.960,-116.130,43.020,-116.070 | Rural benchmark: parcel-centroid import partly merged by hand, buildings tagged only `yes`/`detached`, big parcels, 42 unmerged nodes, Idaho parcels and Microsoft footprints as hints |
| [colonie-ny](colonie-ny/) | Colonie NY (Albany County), bbox 42.740,-73.760,42.750,-73.750 | Post-war suburb with 539 houses, 176 sheds and 135 garages, a commercial strip on Loudon Road with 279 POI address nodes; real parcel-centroid addresses from NYS plus rooftop SAM points |
