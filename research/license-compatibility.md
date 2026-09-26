# Source licences and OSM compatibility (US, 2026-09-26)

Groundwork for showing licence information in JOSM: what licences OpenAddresses sources
declare, which of those are compatible with OpenStreetMap's ODbL, and what the plugin could
show. Not legal advice. The OSMF Licensing Working Group (LWG) has the final say on
compatibility; its guidance is quoted below.

## Short answer

| Licence family | Compatible with OSM? | Basis |
|-|-|-|
| Public domain (incl. US federal data), CC0, PDDL | **Yes**, once provenance is checked | LWG: CC0 and PD "in general compatible"; the licensor must actually hold the rights and no third-party rights may be mixed in |
| ODbL (and ODbL-based terms such as Oregon Metro's RLIS) | **Yes**, credited via the Contributors page | Same licence as OSM |
| OGL 2.0 / 3.0 (UK and faithful variants) | Yes, same caveats as CC0 | LWG; not seen in US sources |
| CDLA Permissive 2.0 | Yes | LWG minutes 2022-11-10; not seen in US sources |
| CC BY 2.0, 3.0, 4.0 | **Only with a signed waiver** | LWG: "all CC BY versions have additional terms that make them incompatible ... without explicit waivers". OSMF publishes waiver templates for CC BY 2.0/3.0 and 4.0 |
| Custom terms requiring attribution | Only with permission for attribution via the OSM Contributors page | LWG: attribution on derived works is incompatible |
| CC BY-SA, CC BY-NC, CC BY-ND (all versions) | **No** | LWG list of incompatible licences |
| Terms requiring indemnification, forbidding uses (e.g. non-commercial), region- or time-limited, revocable without cause, or requiring use of current data | **No** | LWG list of incompatible terms |
| No licence or terms | **No, until terms are found** | LWG: "The absence of documented terms does not imply that nobody ... has rights in the data" |

Anything incompatible can still be used with explicit permission from the data owner (OSM
wiki: Import/Getting permission).

Sources: [OSMF Licence Compatibility](https://osmfoundation.org/wiki/Licence/Licence_Compatibility),
[OSMF waiver and permission templates](https://osmfoundation.org/wiki/Licence/Waiver_and_Permission_Templates),
[OSM blog on CC BY data](https://blog.openstreetmap.org/2017/03/17/use-of-cc-by-data/),
[OSM Contributors](https://wiki.openstreetmap.org/wiki/Contributors).

## What OpenAddresses declares

Each layer of an OpenAddresses source definition may carry a `license` object:
`text`, `url`, `attribution` (bool), `attribution name`, `share-alike` (bool), occasionally
a bare string. The LWG itself notes that OpenAddresses' own CC0 covers only its lists and
format, not the data, which "is licenced on a hodge-podge of different terms".

Every US definition on `master` (commit `656e35b`, 2026-09-26; 1,971 files, 3,611 layers)
was sorted into buckets by the rules at the end of this file:

| Bucket | Verdict | Addresses | Parcels | Buildings | Centerlines |
|-|-|-:|-:|-:|-:|
| A: public domain, CC0, PDDL, "no restrictions" | Compatible (check provenance) | 90 | 40 | 17 | 2 |
| B: ODbL, RLIS | Compatible (credit on Contributors page) | 2 | 1 | 1 | 0 |
| C: CC BY | Needs a waiver | 20 | 12 | 8 | 2 |
| C: attribution required, custom terms | Needs permission | 18 | 7 | 2 | 0 |
| D: share-alike, non-commercial | Incompatible | 4 | 3 | 2 | 0 |
| E: "Indemnification" | Probably incompatible; read the terms | 26 | 15 | 9 | 1 |
| F: link to a terms or disclaimer page, or disclaimer text, no licence named | Unknown; read the terms | 64 | 42 | 17 | 0 |
| G: nothing declared (or "Unknown") | Unknown | 1,700 | 1,020 | 328 | 158 |
| **Total** | | 1,924 | 1,140 | 384 | 163 |

What stands out:

- **Most sources say nothing.** 89 % of parcel layers and 88 % of address layers have no
  licence at all. Under LWG rules that is "not usable until terms are found", not "free".
  For the plugin this bucket has to read as *unknown*, and the Download dialog should not
  suggest otherwise.
- **Fewer than 1 in 25 parcel sources is clearly compatible from OpenAddresses' metadata
  alone** (41 of 1,140 in buckets A and B). Adding the clearances recorded elsewhere (next
  sections) brings it to 231 of 1,140 (20 %): all 95 California and 23 Maryland parcel
  layers through state law whatever they declare, 71 Wisconsin layers that declare
  nothing, 11 named permissions, and the remaining declared-compatible layers.
- **The declared incompatible set is small and specific:** City of Tigard OR (CC BY-SA
  4.0), Spokane County WA (CC BY-NC-SA 4.0), Bucks County PA addresses ("Not for commercial
  use or resale"). Mono County and Sonoma County CA also declare CC BY-SA, but California
  law removes licence requirements from government GIS data (see below), so a county's
  licence text does not decide the matter there. Bucket D in the table counts what
  OpenAddresses declares, including those two.
- **"Indemnification" is OpenAddresses' own shorthand**, used for terms that contain a
  hold-harmless or indemnity clause. The LWG rejects terms that require indemnifying the
  source, but some of these pages may be plain warranty disclaimers (which are fine). Each
  needs reading; the plugin should show it as a warning, not a verdict.
- **The metadata is not audited.** Some `CC0` or `Public Domain` entries point at a county
  "terms of use" page rather than a licence, and `attribution: true` with no text says
  little. The declared licence is a starting point, not a clearance.

The raw data is in `data/oa-us-licenses-2026-09-26.json` (source, layer, licence object,
bucket).

## Sources the plugin itself uses

| Source | Licence | Verdict | Notes |
|-|-|-|-|
| National Address Database (Esri-hosted `USA_NAD_Addresses`) | "Public Domain", credited to "U.S. Department of Transportation and its Partners" (ArcGIS Online item `e75b56f13b404d7d8b47ef8be1c619ec`, maintained by Esri's OSM team) | Compatible, with the LWG caveat | The NAD is compiled from state, county and tribal partners; "and its Partners" is exactly the third-party-rights case the LWG warns about. USDOT's own disclaimer page refuses automated requests, so its wording was not re-checked here. |
| Microsoft US Building Footprints (Esri-hosted `MSBFP2`) | "licensed by Microsoft under the Open Data Commons Open Database License (ODbL)" | Compatible | Used by the plugin only as placement hints, never imported. Positions derived from them are still a use of the data, which ODbL allows. |

## Clearances already on file

Two places record US sources that are cleared for OSM even when OpenAddresses says nothing
about their licence.

### The OSM Contributors page

[Contributors](https://wiki.openstreetmap.org/wiki/Contributors) is where OSM credits
sources and where mappers record permissions they obtained (quoted emails, statements, legal
references). Its US section (read 2026-09-26) has three kinds of entry.

**State law that removes licence requirements.** These cover many sources at once:

| State | What the page records | OpenAddresses layers affected |
|-|-|-|
| Maryland | Senate Bill 94 (2015) removed governments' power to require a licence for GIS data; the page notes it binds "all local and municipal governments" too, so a county's licence text is void | All 29 Maryland sources (23 parcel layers), whatever they declare; 20 parcel and 25 address layers declare nothing |
| Wisconsin | Public records law; the page lists the statewide parcel database as public domain and "available for use in OSM", and the State Cartographer's Office portal data as public domain | `us/wi/statewide` has no parcel layer yet; PR [#8340](https://github.com/openaddresses/openaddresses/pull/8340) adds one. 71 county parcel layers declare nothing. The entry names the state compilation, not the county services, although it is the same county data |
| California | "All California state GIS data" are exempt from licences under the Public Records Act, with a California Supreme Court ruling that county GIS parcel data are public records | All 95 parcel layers, whatever they declare: 70 declare nothing, and a county's own licence text (Mono and Sonoma say CC BY-SA) does not override the state law and the court's ruling |

**Statewide and county permissions for specific data.** Matched to OpenAddresses sources by
jurisdiction and by the host the source reads from:

| Contributors entry | Covers | OpenAddresses source and layers | Declared in OpenAddresses |
|-|-|-|-|
| Pima County GIS (AZ), 2025 | All layers on the county open data portal, "no use restrictions" | `us/az/pima` addresses, parcels (`gisdata.pima.gov/.../GISOpenData`) | none |
| Connecticut, 2020 | State address points, CC0 | `us/ct/statewide` addresses only | none |
| IndianaMap | Address points; citation requested, not required | `us/in/statewide` addresses only | none |
| KyGovMaps (KY) | Open data portal is public domain | `us/ky/statewide` addresses | none |
| Maine E911 | Addresses (imported 2020) | `us/me/statewide` addresses | none |
| District of Columbia OCTO | DC Data Catalog | `us/dc/statewide` addresses, parcels, buildings | terms link |
| Morris County (NJ), 2020 | County GIS data "can be uploaded to OpenStreetMap"; credit asked | `us/nj/morris` addresses | none |
| Westchester County (NY), 2020 | Same wording as Morris | `us/ny/westchester` addresses, parcels, buildings | none |
| CAGIS, Hamilton County (OH) | Buildings and parcels used in an import | `us/oh/hamilton` addresses | none |
| Montgomery County (OH), 2020 | County GIS data can be imported | `us/oh/montgomery` addresses, buildings | none |
| Lane County (OR) | Disclaimer recorded | `us/or/lane` addresses, parcels | attribution |
| City of Salem (OR), 2022 | Address points are public domain | `us/or/city_of_salem` addresses | attribution |
| VGIN (VA) | VBMP data, credit | `us/va/statewide` addresses, parcels, buildings | public domain |
| Prince William County (VA), 2020 | Public domain, no restrictions | `us/va/prince_william` addresses, parcels | none |
| City of Williamsburg (VA), 2022 | Published data is public domain | `us/va/city_of_williamsburg` addresses | none |
| City of Bellingham (WA), 2013 | City data, disclaimer referenced | `us/wa/city_of_bellingham` addresses, parcels, buildings | terms link |
| City of Bothell (WA), 2018 | City data, disclaimer referenced | `us/wa/city_of_bothell` addresses, parcels, buildings | none |
| City of Seattle (WA), 2012 | City data, credit on this page | `us/wa/city_of_seattle` addresses, parcels, buildings | none |
| Clark County (WA), 2021 | County GIS data for editing OSM | `us/wa/clark` addresses (parcels once fixed, see issue drafts) | none |
| King County (WA), 2012 and 2026-06-15 | "OpenStreetMap can reuse datasets hosted on King County GIS Open Data" | `us/wa/king` addresses, parcels | terms link |
| Pierce County (WA), 2020 | County GIS data for editing OSM | `us/wa/pierce` addresses, parcels | terms link |
| Dane County (WI) | Open data portal datasets and imagery | `us/wi/dane` addresses, parcels, buildings; the source reads the county map server, not the portal, so confirm it is the same data | none |

Not matched: the City of Pueblo (CO) entry is for the city's GIS Division, but
`us/co/pueblo` reads Pueblo County's server, so it is not covered. City of Conway (AR) and
City of Redmond (WA) have no OpenAddresses source. Faulkner County (AR) is imagery only.

Together these give a documented basis to 12 more parcel layers by name (Pima, DC,
Westchester, Lane, Prince William, Bellingham, Bothell, Seattle, Clark, King, Pierce,
Dane), plus the Maryland, Wisconsin and California layers through state law. Several
entries are narrower than their source: Connecticut, Indiana, Maine and Salem cover
addresses only, not the parcel layers of the same source.

Many entries end with "credit asked" or a disclaimer to reference. Listing them on the
Contributors page is how OSM meets that, which is why they are recorded there.

### Esri's cleared datasets

Esri's OSM team maintains [a list of datasets it has cleared for OSM](https://wiki.openstreetmap.org/wiki/Esri/ArcGIS_Datasets):
191 datasets, 156 of them "CC-BY 4.0 with Waiver for OpenStreetMap" and most of the rest
public domain, CC0, PDDL or ODbL. They are buildings and addresses only; **no parcel datasets are
on it**.

Matching that list to OpenAddresses sources by jurisdiction and layer type finds 52
OpenAddresses layers in cleared jurisdictions, and OpenAddresses declares no licence for 42
of them (for example Chester County PA addresses, Lake County IL addresses, Sioux Falls SD,
Marin County CA buildings). A waiver covers a jurisdiction's specific dataset, so a match
means "very likely cleared, confirm it is the same dataset", which is still far better than
"unknown".

## Parcels are a special case

The plugin never imports parcel geometry, but it uses parcels to decide which building gets
an address. The LWG's scope is sources "utilized for adding objects to OpenStreetMap or
improving existing data", which covers that. So the parcel source's licence matters as much
as the address source's, even though no parcel ends up in OSM. Since most parcel
sources have no documented basis, the plugin should be upfront about it rather than quiet.

## What the plugin could show

- **A licence badge per source** in the Download dialog, next to each parcel offer and on
  the NAD and Microsoft rows: *Compatible*, *Needs waiver*, *Not compatible*, *Check terms*
  (an indemnification clause or a terms page to read), *Unknown*.
  Colour plus a word, never colour alone.
- **Hover text with the declared licence** (`text`, `url`, the attribution and
  share-alike flags) and the reason for the badge, with the licence or terms URL openable.
- **The same badge in the Analyze popup and in each downloaded layer's name or info
  panel**, so the licence travels with the data after the dialog is closed.
- **A built-in clearance list** checked before the OpenAddresses metadata, kept as a small
  data file in the plugin. Each entry: the source id and layer it covers, the host it
  applies to (so a moved service does not inherit a permission), the basis (Contributors
  page entry, Esri waiver, state law) with a link, and the date recorded. Seeded from the
  Contributors entries and Esri list above, plus RLIS as ODbL. State-law entries apply to
  every source in the state, shown as "state law" rather than "cleared" so the mapper sees
  the basis. In California and Maryland the state law wins over whatever licence a county
  declares; the declared licence is still shown in the hover text, as information.
- **Changeset hygiene**: suggest `source=*` changeset tags naming the address and parcel
  sources actually used, which is what the LWG expects for attribution via the
  Contributors page.
- **Inform, don't block.** A mapper may hold a permission letter the plugin doesn't know
  about. An *Unknown* or *Not compatible* badge should come with a pointer to the OSM wiki
  pages on getting permission, not a refusal.

## Bucketing rules

Applied in order to each layer's `license` (text and url lower-cased and searched
together):

1. `rlis open database` → B (ODbL-based, per the OSM wiki's Portland building import page).
2. `by-nc`, `non-commercial`, `not for commercial` → D.
3. `by-sa`, `share-alike`, or `share-alike: true` without ODbL → D.
4. `odbl` → B.
5. `cc0`, `publicdomain/zero`, `pddl`, `public domain`, `pd`, `no restrictions`,
   `without attribution` (and no indemnification) → A.
6. `cc by` or a `licenses/by/` URL → C (CC BY).
7. `indemn` → E.
8. `unknown`, or nothing → G.
9. `attribution: true` → C (attribution).
10. Only text, or only a URL → F.
