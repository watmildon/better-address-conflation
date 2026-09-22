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
parcel. Generation is deterministic for a given `--seed` (default 42).

| Directory | Area | Character |
|-----------|------|-----------|
| [glendale-olive](glendale-olive/) | Glendale AZ, W Olive Ave / N 55th Ave, bbox 33.560,-112.180,33.570,-112.170 | Single-family grid plus a 206-outline apartment complex, terraces, garages/carports, a strip of address points with no buildings, two POIs |
