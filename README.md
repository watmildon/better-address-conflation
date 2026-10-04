# Better Address Conflation

A [JOSM](https://josm.openstreetmap.de/) plugin that matches address points to buildings the way
a careful mapper would: by parcel, not by nearest centroid. It picks the primary structure over
the garage, keeps every address in a multi-address building as its own node, flags what is
already mapped, and puts everything in review buckets so the safe cases go in one click and your
time goes to the ambiguous ones.

Status: early. Expect rough edges, and review what you apply.

Built for the United States and its territories: the downloads (NAD, Microsoft US footprints,
OpenAddresses' US parcel listings) and the street and unit rules are US-specific. Downloading or
analyzing elsewhere shows a warning once per JOSM session; you can continue past it.

## Why

The standard JOSM Conflation plugin matches on centroid distance. County and NAD address points
sit on the parcel centroid or the street frontage, so they land on the neighbour's house or on the
shed that happened to be closer. Every US address import re-implements the same fixes in a
one-off script. This plugin puts those rules inside JOSM.

## Installing

Requires JOSM 19439 or newer. The plugin is not in JOSM's built-in plugin list yet, so it is
installed by hand:

1. Download `better-address-conflation.jar` from the
   [Releases page](https://github.com/watmildon/better-address-conflation/releases).
   (No release has been tagged yet. Until then, build the jar yourself: see
   [development/README.md](development/README.md).)
2. Put it in JOSM's plugins folder:
   * Windows: `%APPDATA%\JOSM\plugins\`
   * macOS: `~/Library/JOSM/plugins/`
   * Linux: `~/.local/share/JOSM/plugins/` (older installs: `~/.josm/plugins/`)
3. In JOSM, open **Preferences → Plugins**, tick **better-address-conflation**, and restart JOSM.

To update, replace the jar and restart JOSM.

## Quick start

1. Download or open the OSM data for the area (buildings and streets) as your edit layer.
2. Open the **Better Address Conflation** panel (Ctrl+Alt+Shift+A, or **Windows → Better Address Conflation**).
3. Click **Download...** to fetch address points, building footprints and parcels for the
   current view (see [Getting source data](#getting-source-data)), or load your own address layer.
4. Make sure the OSM layer is the active layer, then click **Analyze...**, check the layers, and
   click **Analyze**.
5. Review by bucket. Apply the safe buckets in bulk and work through the rest one at a time.
6. Validate and upload as usual. The downloaded layers are never uploaded.

## Getting source data

**Download...** in the panel offers, for the current map view:

* **Address points from the National Address Database (NAD)**: US address points.
* **Microsoft building footprints**: used only as placement hints where OSM has no building.
  They are never imported.
* **Parcels**: the panel asks OpenAddresses which parcel sources cover the view and lists them,
  most local first. Only ESRI services can be downloaded for just the view, so other sources are
  listed but cannot be ticked.

Every source shows a licence badge (hover it for details):

| Badge | Meaning |
|---|---|
| Compatible | Public domain, CC0, PDDL, ODbL, or a recorded permission. Can be used for OSM. |
| Needs waiver | CC BY or other attribution terms. Usable only with a signed waiver or permission. |
| Not compatible | Share-alike, non-commercial or no-derivatives terms. Needs permission from the owner. |
| Check terms | The source has terms of use that need reading first. |
| Unknown | No licence is known. Do not use it for OSM without documented terms. |

NAD (public domain) and the Microsoft footprints (ODbL) are both compatible. The badges inform;
they never block a download. Parcel layers decide which building an address belongs to and are
never uploaded either way, but the address data you add to OSM must have a compatible licence.

Zoom in if the view is too large: downloads are limited to 0.02 square degrees, roughly
15 km across at US latitudes. Downloading again over a neighbouring area adds to the existing layers.

You can also use any layer of your own whose nodes carry `addr:housenumber` as the address
source, such as the MapWithAI layer, a GeoJSON file opened with the OpenData plugin, or a pasted
layer. Polygon layers you load can serve as parcels or hints in the same way.

## Analyzing

**Analyze...** opens a setup window:

* **OSM Layer**: always the active layer. Its buildings get the addresses.
* **Addresses**: the layer whose address nodes are matched. Pick the active layer itself to tidy
  up its own loose address nodes. They are moved or folded into buildings, keeping their history.
* **Parcels**: a parcel layer, or **Voronoi cells (no parcel layer)**. With Voronoi cells, each
  address gets the area closer to it than to any other address, trimmed to about two lot widths.
  **Clip Voronoi cells by roads** also cuts the cells along streets and railways in the active
  layer, so a cell never reaches the house across the road.
* **Hints**: an optional footprint layer (Microsoft, MapWithAI or county buildings) used for
  position only, never edited.

Layers the plugin downloaded are picked by default.

Inside each cell, buildings are ranked by footprint area times a tag factor:

* Garages, sheds, carports, barns and other outbuildings score low.
* Roofs and canopies score lower still, unless they carry a POI tag such as `amenity=fuel`.
* A plain `building=yes` beside a `building=house` is probably the barn.
* A building that already holds a different address scores low.
* A building whose footprint contains the address point gets a large boost, so condo and
  townhouse points land on their own unit.

Where a cell's only buildings are mapped as nodes, the address can go on the building node.
Exact duplicate address points are dropped.

If most address points sit the same distance and direction off their buildings, the panel
offers **Points look shifted by N m: move the address layer and re-run**. This moves every
address node by that offset (undoable) and analyzes again.

## Reviewing and applying

Results are grouped into buckets by what you need to do, in order from "apply in bulk" to "needs
eyes". Whether a match came from an OSM building or a hint footprint does not change its bucket;
the row says which (`→ building=house` or `→ hint`). Hover a bucket for its description and a
row for the reasons behind the match.

| Bucket | What it means | When applied |
|---|---|---|
| Clean | One address, one clear building. Also an address OSM already has, when nothing disagrees: the source may add keys such as `addr:postcode` or `addr:state` (case-only differences don't count). | An OSM building gets the `addr:*` tags. On a hint footprint, the address becomes a node at the centre of the footprint; the footprint is not imported. Already mapped: only the missing keys are added to the feature that has the address, and the source node is dropped. |
| Multi-address building | Several addresses on one OSM building. | Each stays a node, moved inside the building. Never merged, never interpolated. |
| No building | No building in the address's cell. | The node is copied as-is. |
| Check, then apply | Probably right, but look first. A parcel line splits the building (see **Split building tolerance** below), one outline covers several addressed parcels (such as a townhouse row mapped as one building), or several addresses land on one hint footprint, which may really be several buildings. | Same as Clean or Multi-address building, one row at a time. Several addresses always stay separate nodes inside the building. |
| Ambiguous building | The runner-up building is close in size to the primary. | You pick (see below). |
| Existing address | OSM already has a matching address, but something disagrees: a value differs (the row names the key, e.g. `addr:postcode`), `addr:unit` differs or is on one side only, the street is spelled differently or is another street, the building carries a different address, the address is on an outbuilding instead of the main building, or OSM has it on more than one feature. | Same housenumber, street and unit: the source node is dropped and OSM is left as it is. The others are for you to resolve by hand. |
| Duplicate across cells | The same address appears in more than one cell. | Review only. |
| Outside parcels | The point is in no parcel and none is within the match distance. | Review only. |

* Click a row to select its address and candidate buildings on the map. Double-click a row, or
  use **Zoom**, to zoom to it. Shift- and Ctrl-click select several rows.
* It works the other way too: select address nodes, OSM buildings, hint footprints or parcels on
  the map (in whichever layer is active), and their rows are selected in the panel, ready for
  **Apply**.
  * An OSM building or hint footprint selects the addresses that would go on it, the address it
    already carries, and ambiguous rows that offer it as a choice. A building that only lost to
    a better one selects nothing.
  * A parcel selects every address in it.
  * Selecting part of what the current rows highlight keeps those rows, so you can narrow an
    ambiguous row down to one building and click **Apply**.
* **Apply** applies the selected rows. **Apply bucket** applies every remaining row in the selected
  bucket. Bulk apply is offered only for the first three buckets.
* **Ambiguous buildings**: select the row, then on the map select just the one building the address
  belongs to, and click **Apply**.
* By default the matched address node is deleted from the address layer, so nothing is applied
  twice.
* Everything goes through JOSM's undo stack. Undoing an apply puts the row back in the list.
* The **Overlay** checkbox shows a map layer with the parcel or Voronoi cells and a line from each
  address to its building, coloured by bucket. Selected rows are highlighted in yellow. The
  overlay is never saved or uploaded.

## Validator

The plugin adds an **Address on outbuilding** warning to JOSM's validator. It flags addresses on
garages, sheds, carports and roofs that carry no POI tag, the classic conflation mistake,
whichever tool put them there.

## Preferences

**Preferences → Better Address Conflation**:

| Setting | Default | What it does |
|---|---|---|
| Match distance (m) | 30 | How far an address point may sit from its parcel or building. Raise it in spread-out rural areas. |
| Ambiguity ratio | 0.75 | When the second-best building scores at least this fraction of the best, the address goes to review. |
| Split building tolerance (%) | 20 | With a parcel layer: how much of a building may lie outside the address's parcel before the match goes to **Check, then apply**. The slack absorbs thin slivers where parcels and footprints are offset by a few metres. Raise it where the layers are badly offset; lower it to see every building that crosses a lot line. Not used with Voronoi cells. |
| Outbuilding values | garage, garages, shed, carport, roof, canopy, outbuilding, greenhouse, barn, hut, cabin, shelter, kiosk, storage_tank, silo, service | `building=*` values ranked low as address targets. |
| Delete address nodes after applying | on | Removes the matched node from the address layer. |

## Feedback

Bugs and ideas: [GitHub issues](https://github.com/watmildon/better-address-conflation/issues).

## License

GPL v2 or later, like JOSM.
