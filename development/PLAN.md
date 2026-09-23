# Better Address Conflation — JOSM plugin plan

Working name: **better-address-conflation** (rename before first release).
Tracking issue: https://github.com/watmildon/OSM_Notes/issues/100

## 1. Problem

Mappers adding US addresses today use the JOSM Conflation plugin (or hand work) to move
address points onto buildings. The address points almost never sit on the building:

* County/NAD points are placed at the **parcel centroid** or at the **street frontage**.
* Conflation matches on **centroid distance only**, so an address will happily land on the
  neighbor's house across a parcel line, or on the **garage/shed** that happens to be closer
  than the primary structure. Reviewers currently mitigate this by eyeballing every match with
  a large distance value, or by pre-tagging `building=detached` (Allison P's workaround).
* Conflation is strictly **1:1**, ignores relations, and offers no "reject this match and
  re-run". Duplexes, apartments, stacked units, and townhouse rows are all manual.
* Existing OSM addresses (partial, mis-spelled street, missing unit, swapped between neighbors)
  are found by validator noise rather than by a deliberate comparison step.

Every documented US import (LA buildings, Baltimore County, Milwaukee/Waukesha, Vermont E911,
San Francisco) rebuilt the same decision rules in one-off scripts (PL/SQL, PHP, Python, QGIS):

| Situation                          | What imports do today                                |
|------------------------------------|------------------------------------------------------|
| 1 address, 1 building              | tag the building                                     |
| N addresses, 1 building            | keep nodes (or `12;14`, or interpolation if > 10)    |
| 1 address, N buildings             | pick "the" building by hand                          |
| Address, no building               | keep node / skip / draw building                     |
| Match distance > 10–15 m           | manual review (usually an outbuilding)               |
| Existing OSM address in the area   | "conflict" bucket, manual                            |
| Source duplicates (stacked units)  | dedupe script                                        |

None of that logic exists inside JOSM. That is the gap this plugin fills.

## 2. Goal

A JOSM plugin that takes **an address layer**, **the edit layer's buildings**, and
**optionally a parcel layer**, and produces a reviewed, bucketed, undoable set of proposals:
"put this address on that building", "keep these as nodes inside that building", "this
conflicts with an existing address", "no building here". The mapper reviews by bucket, applies
the high-confidence bucket in one click, and spends their time only on the genuinely ambiguous
cases.

Non-goals for v1: importing parcel geometry into OSM (never), replacing MapWithAI/OpenData as a
data loader, fixing street-name spelling (validator rules already do this), anything
non-address.

## 3. Core algorithm

### 3.1 Inputs

* **Address source**: any `OsmDataLayer` whose nodes carry `addr:housenumber` (MapWithAI/NAD
  layer, a GeoJSON/OpenData layer, a pasted layer). Selected from a dropdown.
* **Target layer**: the active edit layer. Buildings = closed ways and multipolygon relations
  with `building=*`. Existing addresses = anything in the layer with `addr:housenumber`.
* **Cell source** (one of):
  * **Parcel layer**: an **OpenAddresses parcels file** (line-delimited GeoJSON, one Polygon /
    MultiPolygon Feature per line, properties `id`, `pid`, `hash`). The plugin ships its own
    reader for this format and loads it into a non-uploadable layer, keeping `pid` as `oa:pid`.
    The same reader loads OA `addresses` files (properties `number`, `street`, `unit`, `city`,
    `district`, `region`, `postcode`) straight into `addr:*` tags, so one "Load OpenAddresses
    file" action covers both inputs. Any other polygon `OsmDataLayer` is accepted as a fallback.
    OA already has parcel layers for many US counties (Maricopa, Glendale, ...) built from the
    county ESRI services, so this is the least-effort path for mappers.
  * **Synthetic cells**: Voronoi tessellation of the address nodes (JTS
    `VoronoiDiagramBuilder`), clipped to the address layer's bounds, then each cell trimmed
    to a circle of 2x the local address spacing (median nearest-neighbour distance over the
    ~10 nearest addresses) so edge and gap cells stay lot-sized. Phase 3 adds
    **road clipping**: cut cells along `highway=*` centerlines (parcels essentially never cross
    a road), then along `railway`/`waterway`/`landuse` edges. multipoly-gone already has
    "break polygon along features" logic that can be lifted.

### 3.2 Pipeline

```
addresses ──▶ dedupe ──▶ assign to cell ──▶ rank buildings in cell ──▶ bucket ──▶ proposals
                                 ▲                    ▲
                     parcels / Voronoi        edit-layer buildings (STRtree)
                                                       + existing OSM addresses
```

1. **Dedupe source** — address nodes whose `addr:*` tags are identical are absolute
   duplicates: keep one, delete the rest on apply. Same housenumber+street with differing units
   become a *unit group* and are never collapsed.
2. **Assign address → cell** — point-in-polygon against the cell index. Parcel mode: if a
   point is in no parcel, nearest parcel within the **match distance** preference, else bucket
   *outside cells*. The same preference bounds how far an address may sit from the building it
   is matched to; 10–15 m is fine in a city grid but not in spread-out developments, so it is
   user-tunable (default 30 m).
3. **Assign building → cell** — each building goes to the cell holding the largest share of
   its area. Record the share. Buildings that straddle cells with ~50/50 split are flagged.
4. **Rank buildings within a cell** — score = footprint area inside the cell, multiplied by a
   tag factor: `garage|shed|carport|roof|garages|outbuilding|greenhouse|barn` × 0.1;
   `house|detached|residential|apartments|commercial|retail|yes` × 1.0; buildings already
   holding a *different* address × 0.2; a building whose footprint **contains the address
   point** × 4 (county points for condos and townhouses sit on the unit), but only for real
   buildings, not roofs or garages. A POI tag (`amenity`, `shop`, `office`, ...) cancels the
   outbuilding penalty: a gas-station canopy tagged `building=roof` + `amenity=fuel` is the
   feature that carries the address. Primary = top score.
   Confidence drops when the runner-up is within 25 % of the primary's score (duplex mapped as
   two buildings, or a genuinely big shed).
5. **Bucket** each address (or unit group) — see 3.3.
6. **Emit proposals** — every proposal carries: address node(s), target primitive(s), bucket,
   confidence (0–1), and a human-readable reason list ("largest of 3 buildings in parcel",
   "runner-up is 92 % of primary area", "NAD point is 38 m from building centroid").

### 3.2a Hints

A third, optional input: a layer of building footprints that is read for position only and
never edited (the MapWithAI layer, county footprints, Microsoft footprints via an
OpenAddresses `buildings` layer). Hint footprints are indexed like OSM buildings. When a cell
has no usable OSM building, or OSM offers only an outbuilding while a hint footprint scores
more than 3x it, the address goes to the **Placed by hint** bucket: the node is placed on the
hinted footprint, nothing is imported. With the edit layer as its own address source this is
a mass-cleanup tool for badly placed existing nodes, which are moved rather than recreated.
In that mode only plain address nodes (`addr:*` plus bookkeeping keys such as `source`,
`note`, `check_date`) are sources. A node that also carries `building`, a POI key or `name` is
a feature: it is left alone and counts as an existing address.

### 3.2b Buildings mapped as nodes

Some mappers mark a building with a single `building=*` node. Unaddressed building nodes are
indexed per cell (in Voronoi cells, within the match distance of the address) and only used
where the cell has no outline; an outline always wins, and a building node beats a hint
footprint.

* One building node, one address: **Clean**, the address tags go on the node.
* One building node, several addresses: **Multi-address building**, each address stays its
  own node, placed within 6 m of the building node.
* Several building nodes: **Ambiguous building** with no default target, one proposal per
  address. Closest-wins is wrong here (the nearest node is often the garage or the
  neighbour), so the mapper picks: selecting the row highlights every candidate, the mapper
  selects one on the map, and Apply uses it. The same pick works for ambiguous outlines.

### 3.3 Buckets

| Bucket                     | Trigger                                                   | Default action                                             |
|----------------------------|-----------------------------------------------------------|------------------------------------------------------------|
| **Clean**                  | 1 address, primary building clear, no existing addr       | Tag building, delete node. *Apply-all safe.*               |
| **Multi-address building** | N addresses/units in cell, 1 primary building             | Keep every address as its own node, moved inside the building. Never merged into `12;14`, never interpolated. |
| **Ambiguous building**     | Runner-up building close in score, or building straddles cells | Show candidates; user picks; "next best" hotkey            |
| **Placed by hint**         | No usable OSM building, but a hint footprint is there     | Node placed on the hinted footprint; footprint not imported. *Apply-all safe.* |
| **No building**            | Cell has no building                                      | Keep node in place (default) / skip / shortcut to buildings_tools |
| **Building spans parcels** | One building assigned to several addressed cells (townhouse row mapped as one outline) | Keep nodes inside building; suggest split                  |
| **Existing address**       | Cell already has addr:* on building/node/POI              | Sub-buckets: *identical* (skip, or fill missing city/postcode), *unit-only diff*, *street diff after normalization*, *housenumber diff*. Never auto-applied. |
| **Outside cells**          | Parcel mode, point in no parcel                           | Review                                                     |
| **Source duplicates**      | Collapsed in step 1                                       | Informational; one click to delete extras                  |

Street comparison in *Existing address* normalizes case, expands suffixes/directionals
(reuse `StreetNameUtils` from TIGER-ROAR and the USStreetNameExpander tables), and reports
"same after normalization" vs "different".

### 3.4 Dataset shift detection

Some counties place points with a consistent offset. After a first pass, compute the median
vector from *Clean* address points to their building centroids. If it is large and tightly
clustered, offer "shift address layer by (dx, dy) and re-run". Cheap, and it removes a whole
category of hand fixes from the diary entries.

## 4. UI

Modeled on TIGER-ROAR and multipoly-gone: a `ToggleDialog` side panel, tree grouped by bucket,
click selects, double-click zooms, per-row reason text, **Apply** / **Apply bucket** buttons,
everything through `SequenceCommand` so undo works.

* **Setup strip** at the top: address layer dropdown, cell source (parcel layer dropdown or
  "Voronoi"), **Analyze**.
* **Review tree**: buckets as top-level nodes with counts; rows sorted by confidence ascending
  inside review buckets (worst first), descending inside Clean.
* **Overlay layer**: a non-uploadable `Layer` that paints cells (thin grey), address→building
  links coloured by bucket, and the primary building outline highlighted for the selected row.
  Toggle on/off. This replaces "pan around after conflating and look for errors".
* **Row actions**: Apply, Skip, Reject (re-rank to next-best building), Keep as node,
  Delete source node, Open in buildings_tools.
* **Preferences**: match distance (metres), outbuilding tag list and weights, ambiguity
  threshold, Voronoi clip features, tags to copy (`addr:*` allowlist, `source` handling),
  whether to delete from the source layer after apply.

Applying writes into the **edit layer only**. Source nodes are deleted from the source layer
if the pref says so (for a MapWithAI layer this keeps its own dedupe logic happy).

## 5. Architecture

```
org.openstreetmap.josm.plugins.addressconflation
├── AddressConflationPlugin        entry: registers dialog, prefs, validator test
├── model/     Proposal, Bucket, Cell, BuildingCandidate, AddressGroup
├── cells/     CellSource (interface), ParcelCellSource, VoronoiCellSource, RoadClipper
├── engine/    AddressNormalizer, Deduper, CellAssigner, BuildingRanker, Bucketer, Analyzer
├── apply/     ProposalApplier (builds Commands), TagMerger
├── gui/       ConflationDialog, ProposalTreeModel, OverlayLayer, PreferencesPanel
├── validation/ AddressOnOutbuildingTest, AddressOutsideCellTest (post-apply checks)
└── external/  (phase 4) NadClient copied from TIGER-ROAR, EsriParcelFetcher
```

Engine classes are pure functions over `DataSet`s with no Swing, so they are unit-testable
the same way TIGER-ROAR's analyzers are. JTS 1.20 packed into the jar (`packIntoJar`, as in
multipoly-gone) for STRtree, point-in-polygon, area, Voronoi, and polygon clipping.

## 6. Build and tooling

Copy the conventions from TIGER-ROAR / multipoly-gone verbatim:

* `org.openstreetmap.josm` gradle plugin 0.8.2, `josmCompileVersion = 19439`,
  `minJosmVersion = 19439`, `canLoadAtRuntime = true`, Gradle 8.5 wrapper.
* `RELEASE_VERSION` env → version, else `dev-SNAPSHOT`.
* JUnit 5, `JosmTestSetup` extension (MemoryPreferences + EPSG:4326), `.osm` fixtures under
  `test-data/`, regression snapshot tests (`ResultSnapshot`) so refactors are caught by diff.
* GitHub Actions `ci.yml` (JDK 21, build, upload jar) and `release.yml` (tag `v*` → GitHub
  release with the jar). Local dev is on JDK 17, which is fine for compile version 19439.
* `./gradlew runJosm` for manual testing with the installed MapWithAI, buildings_tools,
  utilsplugin2 plugins.

## 7. Phases

Status (2026-09-22): phases 0 and 1 are done; phase 2 is code-complete (dialog, apply path,
overlay layer, undo awareness) but untested by hand; phase 3's road-clipped Voronoi and shift
detection are done and tested; hint layers (section 3.2a) are done: with every OSM building
removed from the Glendale bed and the city's footprints as hints, 99 % of hinted nodes land
inside the building that really carries the address, and same-layer cleanup moves existing
nodes instead of recreating them; phase 4's fetchers are done (a generic ESRI FeatureServer client with the
NAD as a preset, and OpenAddresses source definitions resolved by id so a county's own
address and parcel services download for the current view; `io/EsriFeatureClient`,
`io/OpenAddressesSourceReader`, `io/DownloadSourceAction`). Engine scores on the Glendale test bed: 100 % (centroid points, Voronoi),
99.8 % (centroid points, parcels), 99.6 % with 190 existing addresses detected (overlap
variant), 99.8 % on the real Maricopa address points (which turn out to be rooftop points),
80 % on synthetic parcel-centroid points where the 206-unit complex sets the ceiling; outside
that complex the parcel-centroid case misses 2 of 839, both in parcels holding several
buildings and several addresses at one point, which now land in the Ambiguous bucket.
A second bed, `test-data/colonie-ny/`, holds real parcel-centroid addresses (NYS tax parcel
centroids, 36 % inside a building) with 313 sheds and garages; the engine scores 98.8 % there
with parcels and 97.4 % with Voronoi. A survey of nine county feeds behind documented US
imports (see `test-data/README.md`) found most are rooftop points; parcel-centroid data
comes from assessor parcel layers and statewide parcel-centroid products. A third, rural bed
(`test-data/owyhee-grandview/`, Owyhee County ID: a parcel-centroid import partly merged by
hand, buildings tagged only `yes`/`detached`, big parcels) scores 100 % on centroid points and
93.9 % on parcel-centroid points with parcels, and drove two rules: `yes` is demoted beside an
explicit building value unless the point is inside it, and matches further than 100 m go to
review. Its 42 real unmerged nodes run through the cleanup path with Microsoft footprints as
hints: 5 merged, 10 moved onto footprints, 16 no-building, 7 review, 3 existing-address.
Three lessons baked in: the containment boost applies only to real buildings, not to roofs
or garages (a parcel centroid under a gas-station canopy must not hand the canopy the address); a building whose footprint contains the address point gets a 4x score
boost (without it, condo and townhouse units all collapse onto the largest outline), and JOSM
renumbers negative ids when a second file is loaded in the same JVM, so the test bed carries a
`testbed:source` tag rather than relying on ids.

**Phase 0 — Scaffold (small).** Gradle build, plugin class, empty dialog, prefs stub, CI,
`JosmTestSetup`, README skeleton. `./gradlew build` green, plugin loads in JOSM.

**Phase 1 — Engine, no UI.** `cells/`, `engine/`, `model/`. Fixtures come from two places.
The real-world test bed in `test-data/glendale-olive/` (see its README) is a known-good Phoenix
area with its addresses stripped back into point layers, plus the county parcels and address
points in OpenAddresses format, and a `truth.json` that says which building each address came
from; the engine is scored against it. Hand-built `.osm` fixtures for a single block cover: clean house+garage, duplex as one building, duplex as two buildings,
apartment with 12 stacked units, vacant lot with an address, building straddling two parcels,
townhouse row as one outline, an existing OSM address that disagrees on street spelling, NAD
source duplicates, a point outside every parcel. One fixture set with parcels, one without.
Snapshot tests over the proposal list. Deliverable: a CLI-free `Analyzer.run(...)` that
produces buckets we agree with on the fixtures.

**Phase 2 — Review UI and apply.** Dialog, tree, overlay layer, `ProposalApplier` with undo.
First real-world trial on an area you have already worked (Phoenix / Indianapolis / King
County, where parcels are open data and there's a Redmond import repo to borrow from).
Measure: how many addresses per minute vs the Conflation plugin, and how many post-apply
corrections were needed.

**Phase 3 — Quality.** Done: road-clipped Voronoi (proven on a synthetic case; on grid
suburbs it changes nothing because the Voronoi boundary between houses facing each other
already runs down the street), dataset shift detection with a one-click move-and-rerun,
existing-address sub-buckets, building-spans-parcels handling, roof/canopy factor 0.02 and
POI-tag override. Remaining: validator tests, tuning against more counties. Performance:
24k buildings and 18k addresses analyze in about 1 s (Voronoi or parcels), 1.7 s road-clipped.

**Phase 4 — Convenience and release.** Done: NAD fetch and generic ESRI FeatureServer fetch
driven by OpenAddresses source definitions (addresses and parcels). Remaining: JOSM
plugin-list submission, wiki page, diary post. Docs get the `$(cat ~/.overpassurl)` treatment for any Overpass examples.

## 8. Open questions (defaults chosen; change if you disagree)

1. **Multi-address building policy.** Decided: nodes stay nodes, moved inside the building.
   No semicolon lists, no interpolation, not even as an option.
2. **Layer-agnostic vs built-in fetchers.** v1 is layer-agnostic. Fetchers are phase 4 so the
   engine and review flow prove out first.
3. **Plugin name.** Placeholder. Something in the TIGER-ROAR / multipoly-gone spirit is your
   call.
4. **Relations as targets.** Supported from the start (multipolygon buildings are common in
   dense areas and the Conflation plugin's lack of support is a real pain point).
5. **Voronoi as a proxy.** Frontage-placed points give cells that extend back from the street,
   which is parcel-like; rooftop-placed points also work. Road clipping is what makes this
   good enough where no parcel layer exists, so it is scheduled early in phase 3.

## 9. References

* Issue: https://github.com/watmildon/OSM_Notes/issues/100
* Diaries: https://www.openstreetmap.org/user/watmildon/diary/401407 (Conflation plugin
  workflow), /400812 (MapWithAI + NAD workflow), /400537 (finding low-density areas)
* Imports reviewed: San Francisco (address→parcel→building), Milwaukee County, Waukesha
  County, Vermont VCGI E911 (2025, 331k points), Baltimore County (1:1 spatial join rule),
  LA buildings (1:1 rule, points for the rest), Import/United States Addresses (NAD via Esri)
* JOSM Conflation plugin source: https://github.com/JOSM/conflation (centroid/Hausdorff
  matchers over JCS, greedy top-score, no relations)
* MapWithAI `MergeBuildingAddress` command: merges a node into the single building containing
  it, only when that building has exactly one address point inside
* Style sources: https://github.com/watmildon/TIGER-ROAR, https://github.com/watmildon/multipoly-gone
