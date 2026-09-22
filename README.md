# Better Address Conflation

A [JOSM](https://josm.openstreetmap.de/) plugin that matches address points to buildings the way
a careful mapper would: by parcel, not by nearest centroid. It picks the primary structure over
the garage, keeps every address in a multi-address building as its own node, flags what is
already mapped, and puts everything in review buckets so the safe cases go in one click and your
time goes to the ambiguous ones.

Status: early. The engine is scored against a real-world test bed (see [test-data/](test-data/));
the review dialog works but has no overlay painting yet. See [PLAN.md](PLAN.md).

## Why

The standard JOSM Conflation plugin matches on centroid distance. County and NAD address points
sit on the parcel centroid or the street frontage, so they land on the neighbour's house or on the
shed that happened to be closer. Every US address import re-implements the same fixes in a
one-off script. This plugin puts those rules inside JOSM.

## How it works

1. **Cells.** Each address is assigned to a parcel. Load an
   [OpenAddresses](https://openaddresses.io/) parcels file with **File → Open OpenAddresses
   file...** (line-delimited GeoJSON, the `parcels` layer). With no parcel layer, Voronoi cells
   around the address points stand in for parcels.
2. **Ranking.** Buildings in the cell are scored by footprint area times a tag factor. Garages,
   sheds, carports and roofs score low. A building whose footprint contains the address point
   gets a large boost, so condo and townhouse points land on their own unit.
3. **Buckets.**
   * *Clean*: one address, one clear building. Apply the whole bucket.
   * *Multi-address building*: several addresses on one building. Each stays a node, moved
     inside the building. Never merged, never interpolated.
   * *No building*: the node is copied as-is.
   * *Building spans parcels*: one outline over several addressed parcels; nodes go inside.
   * *Ambiguous building*: the runner-up is close in size. You pick.
   * *Existing address*: identical, unit-only difference, street spelling variant, or a
     different address already on the building.
   * *Duplicate across cells* and *Outside parcels*: review.
4. **Apply.** Everything goes through JOSM's undo stack. Identical source duplicates are dropped.

## Usage

1. Load the area in JOSM (buildings present) as the edit layer.
2. Load addresses as a second layer: the MapWithAI NAD layer, an OpenAddresses `addresses` file,
   or any layer with `addr:housenumber` nodes.
3. Optionally load an OpenAddresses `parcels` file. It becomes a non-uploadable layer.
4. Open the **Address Conflation** panel (Ctrl+Alt+Shift+A), pick the layers, click **Analyze**.
5. Review by bucket. Click a row to select it on the map, double-click to zoom, **Apply** or
   **Apply bucket**.

Preferences → Address Conflation: match distance (how far a point may sit from its parcel or
building; raise it in spread-out developments), ambiguity ratio, outbuilding values, whether to
delete source nodes after applying, and whether to expand county-style street names when loading
OpenAddresses files.

## Building

```bash
./gradlew build          # jar in build/dist/
./gradlew test
./gradlew runJosm        # JOSM with the plugin loaded
```

Test data and the scripts that regenerate it are described in [test-data/README.md](test-data/README.md).

## License

GPL v2 or later, like JOSM.
