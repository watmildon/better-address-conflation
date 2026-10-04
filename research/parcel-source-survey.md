# OpenAddresses parcel source survey (US, 2026-09-22)

Every US parcel source that OpenAddresses lists (1,106) was run through the plugin's own
download path to find the ways real county servers break it. This file catalogues what
turned up, what the plugin now handles, and what is outside its control.

## Method

For each source in `https://batch.openaddresses.io/api/data?layer=parcels`:

1. Read its definition from the OpenAddresses repository.
2. Take the first parcel of its latest OpenAddresses job sample
   (`/api/job/{id}/output/sample`), so the test box is known to hold parcels.
3. Download parcels for a ~300 m box around it with `EsriFeatureClient.download`, exactly
   as the Download dialog does.

The harness is `src/test/java/.../io/ParcelSourceSurvey.java`. It is skipped in normal
builds; to rerun it (about 20 minutes, 8 sources at a time):

```bash
SURVEY_OUT=/tmp/survey.jsonl ./gradlew test --tests '*ParcelSourceSurvey' --rerun
# SURVEY_PREFIX=us/id/ limits it to one state
```

Raw results, one JSON object per source, are in `data/parcel-survey-2026-09-22-before.jsonl`
and `-after.jsonl`.

## Results

| Outcome | Before fixes | After fixes |
|-|-:|-:|
| Parcels downloaded | 709 | **726** |
| Download failed | 265 | 248 |
| Not an ESRI service (file download only) | 108 | 108 |
| Nothing around the sample parcel | 11 | 12 |
| Definition gone from the repository | 7 | 7 |
| Survey harness error | 5 | 4 |

18 sources went from failing to working. One (`us/ky/jefferson`) failed with a transient
HTTP 500 in the second run and works when retried. Four more now come with parcel ids
(`oa:pid`) that were missing before. Of the ESRI sources, about three in four work.

The typical successful download took 8 s in the survey (90th percentile 17 s), including
reading the definition and the job sample, which the plugin does not repeat.

## Issues fixed in the plugin

| Issue | How it showed | Sources | Fix |
|-|-|-:|-|
| Field names differ in case from the definition | HTTP 500 "Field name 'STATE_PARCEL_ID' does not exist" (Indiana statewide), or parcels without ids | 11 | Read the layer's field list first and match names ignoring case (`FeatureSource.withServiceFields`) |
| Field names carry a database or table prefix on one side only | `SDE_GISA.Parcel_Boundary.APN` in the definition, `APN` in the service, or the reverse | 4 recovered | When the full name does not match, match on the last dotted part if exactly one field has it |
| Server refuses paging | "Pagination is not supported." | 14 | Retry once without `resultOffset`/`resultRecordCount`; log if the server says it truncated |
| Server cannot produce GeoJSON | HTTP 400 "Output format not supported", or an HTML page with HTTP 200 | 4 | Any failure of the GeoJSON request falls back to Esri JSON |
| Server's real error only visible in Esri JSON | HTML page for GeoJSON; Esri JSON says "Service Parcels/MapServer not found", "not started", "Token Required" | 37 (still failing, now with the reason) | Same fallback; its error is the one reported |
| Error reason in `details`, `message` empty | "server error (HTTP 400)" with nothing else | 85 had no reason before | Use `details` when `message` is empty |
| Old error shape `{"status":"error","messages":[...]}` | "Not JSON: <!DOCTYPE html..." | 3 | Understood |
| "Parcel" source is really an address-point layer | Empty parcel layer | 8 | Layer description checked; refused with "this layer holds points, not parcel outlines" |
| Raw exceptions as messages | `PKIX path building failed: sun.security...`, a bare host name, `Connect timed out` | 56 | Plain wording: certificate could not be verified, server X not found, did not answer in time, could not connect |
| Empty geometry in a feature | `IndexOutOfBoundsException` possible on an empty MultiPolygon or path | seen in `us/fl/putnam` sample data | Empty geometries are skipped |

Regression tests: `EsriServiceQuirksTest` (field matching, error parsing, point layers,
message wording) and `EsriFallbackServerTest`, a local HTTP server that imitates a server
that sends HTML for GeoJSON, refuses paging, or reports it is down.

## Issues outside the plugin's control

Counts are from the second run. Appendix A lists every source.

| Cause | Sources | Notes |
|-|-:|-|
| Service or layer gone (404, "not found", "Invalid URL", "layerId was not found", "Invalid or missing input parameters") | 143 | Definitions pointing at deleted or renamed services. The single biggest group; worth reporting upstream in bulk. |
| TLS certificate rejected | 22 | Expired certificates, host names that no longer match (Box Elder moved to `boxeldercountyut.gov`), and servers that omit the intermediate certificate (`gis.cmpdd.org`, `web2.kcsgis.com`, `www.colesco.illinois.gov`). Browsers fetch a missing intermediate, Java does not. A user can start JOSM with `-Dcom.sun.security.enableAIAcaIssuers=true` to let Java do the same. The plugin must not turn verification off. |
| Needs a login ("Token Required") | 21 | Not public any more. |
| Timed out | 18 | Some are just slow; retrying later can work. |
| Service stopped or server down ("not started", "Could not access any server machines", 502/503/52x) | 21 | Often temporary. |
| Server-side query failure ("Error performing query operation", HTTP 500) | 12 | Includes `us/co/lake`, whose spatial queries all fail while attribute queries work. |
| Forbidden (403), including a Cloudflare challenge page ("Just a moment...") | 5 | |
| Connection refused or reset | 4 | |
| Web page instead of data | 2 | `us/la/catahoula`, `us/wi/waukesha`. |

Also outside our control:

- **Stale parcel-id fields** (Appendix B): 31 working sources download parcels
  without ids, because the definition names a field the service no longer has (Anchorage
  has `Parcel_ID`, the definition says `PARCEL_NUM`). Parcels still work for conflation; only
  `oa:pid` is missing.
- **Wrong layer**: `us/va/greene` points at a floodplain cross-section layer, and
  `us/sc/berkeley` at a group layer.
- **Empty id values in the data itself**: `us/id/idaho` has `Parcel_ID`, but it is null
  for the parcels sampled.
- **Not ESRI**: 108 sources publish a downloadable file rather than a queryable service
  (Appendix E). The Download dialog lists them greyed out.

## Ideas not done yet

- **Check offers before showing them.** The Download dialog could fetch each offered
  layer's description (one small request) and grey out sources that are gone, stopped or
  need a login, instead of letting the user find out after pressing Download. That covers
  the gone, stopped and login groups, about 185 of the 248 failures.
- **Retry once on timeouts and 5xx.** Several failures were transient
  (`us/ky/jefferson` worked on retry).
- **Guess the parcel-id field when the named one is missing.** Candidates such as
  `PARCEL_ID`, `PARCELID`, `PIN`, `APN`, `PARCEL_NUM`. This is a guess, so it would need a
  preference or a note in the layer; parcels without ids already work for conflation.
- **Report upstream.** Appendices A (gone), B (stale fields), C (point layers) and D
  (missing definitions) are ready-made lists for OpenAddresses issues or pull requests;
  almost none are reported yet (see "Already reported upstream?").
- **Non-ESRI sources** could be read from OpenAddresses' own processed output (PMTiles,
  `pmtiles_url` on each job) instead of the county's file.

## Already reported upstream?

Checked against the OpenAddresses repository on 2026-09-22: all 412 open issues and 11 open
pull requests in `openaddresses/openaddresses`, matched on source path, service URL and
county name. Nothing was filed there.

**Almost none of these failures are reported.** Of the 298 sources flagged above (248
failing, 12 empty, 31 with a stale id field, 7 with a missing definition), 12 have an open
issue or PR that mentions the same source or server. Only one of those, Carroll County, GA,
is about the parcel layer failing. The others concern the source's address layer, another
problem on the same server, or (South Dakota) 2018 requests to add sources.

| Source | Our result | Open upstream | What it says | Relevance |
|-|-|-|-|-|
| `us/ga/carroll` | "Invalid URL" | [#7724](https://github.com/openaddresses/openaddresses/issues/7724) (2025-04, Broken Source) | Source needs a token; an older 2018 parcel layer on the same server still works | Same failure. Our definition points at a later export (`Carroll_Parcels_20240923_ExportFeatures`) that is gone as well |
| `us/ky/perry` | Definition missing from repo | [#7964](https://github.com/openaddresses/openaddresses/issues/7964) (2025-12, Broken Source) | Perry County, KY file held Perry County, OH addresses | Explains why the source was removed; the batch API still lists its parcels |
| `us/ms/city_of_diberville` | HTTP 404 | [#7781](https://github.com/openaddresses/openaddresses/issues/7781) (2025-07, Broken Source) | The processed parcels file has bad coordinates | Same parcel source, different problem; the service it reads is now gone too |
| `us/ny/westchester` | Works, no parcel ids (`PRINTKEY` missing) | [#8037](https://github.com/openaddresses/openaddresses/issues/8037) (2026-01, Broken Source) | Address layer gone; points at the county's Tax Parcels dataset | Parcel layer not reported; its id field is stale |
| `us/oh/statewide` | Certificate rejected (`webgis.co.trumbull.oh.us`) | PR [#8243](https://github.com/openaddresses/openaddresses/pull/8243) | Replaces the statewide address layer and drops a Trumbull County address layer | Same server, but the PR leaves the Trumbull parcel layer in place |
| `us/la/ascension` | Connection reset (`geo.apgov.us`) | [#5747](https://github.com/openaddresses/openaddresses/issues/5747) (2021) | Address layer on the same server "Could not access any server machines" | Same server, older report about another layer |
| `us/il/champaign` | 403, no permission | [#4550](https://github.com/openaddresses/openaddresses/issues/4550) (2019, updated 2026-08) | Address source broken, new URL suggested | Same county, address layer only |
| `us/sd/kingsbury`, `lake`, `roberts`, `hamlin`, `codington` | Point layers, "not started", or no ids | [#4324](https://github.com/openaddresses/openaddresses/issues/4324)-[#4335](https://github.com/openaddresses/openaddresses/issues/4335) (2018) | Requests to add parcel layers on `1stdistrict.org` | Not failure reports. The layers they suggest are now gone or are not parcels (Roberts `MapServer/1` is "Recent Sales"; Hamlin `MapServer/4` is water valves) |

Open PRs that would change parcel coverage:

- [#8340](https://github.com/openaddresses/openaddresses/pull/8340) adds a parcel layer to
  `us/wi/statewide` (Wisconsin statewide parcels), which has none today.

Found while checking, not reported anywhere:

- `us/tx/statewide` fails with "Service not found": the server dropped
  `Parcels/stratmap24_land_parcels_48` and now serves
  `Parcels/stratmap_land_parcels_48_most_recent/MapServer/0`, a polygon layer that still has
  the `geo_id` field the definition uses. This looks like a one-line fix.

## Appendix A: sources still failing, by cause

Messages are what the plugin now shows. Survey of 2026-09-22; servers come and go, so rerun before acting on a single entry.

### Service or layer gone (143)

| Source | Message |
|-|-|
| `us/ak/haines` | service error: Invalid URL |
| `us/al/colbert` | service error: Service Colbert/Public/MapServer not found |
| `us/ca/city_of_elk_grove` | service error: Service Open_Data_Portal/EG_PARCELS/MapServer not found |
| `us/ca/city_of_rancho_cucamonga` | service error: Invalid URL |
| `us/ca/city_of_redlands` | service error: Invalid URL |
| `us/ca/contra_costa` | service error: Service not found |
| `us/ca/siskiyou` | service error: Invalid URL |
| `us/ca/yolo` | service error: Invalid URL |
| `us/co/city_of_boulder` | service error: Service not found |
| `us/co/conejos` | service error: Invalid URL |
| `us/co/gilpin` | server error (HTTP 404): 404 - File or directory not found. |
| `us/co/jefferson` | service error: Service not found |
| `us/co/rio_blanco` | service error: Item does not exist or is inaccessible. |
| `us/co/routt` | server error (HTTP 404): 404 - File or directory not found. |
| `us/co/summit` | service error: Service not found |
| `us/co/teller` | server tcweb.co.teller.co.us not found |
| `us/fl/baker` | service error: Invalid URL |
| `us/fl/marion` | service error: Service Onemap/Parcels_withoutCF/MapServer not found |
| `us/fl/pasco` | service error: Service Accela/PascoAccela/MapServer not found |
| `us/fl/sumter` | service error: Service Interactive/Parcels_Pro/MapServer not found |
| `us/ga/barrow` | service error: Invalid URL |
| `us/ga/ben_hill` | service error: Invalid or missing input parameters. |
| `us/ga/bleckley` | service error: Invalid URL |
| `us/ga/bryan` | service error: Service Parcels/MapServer not found |
| `us/ga/camden` | service error: Error handling service request :Could not find a service with the name 'Camden/MapServer/Camden_Parcels' in the configured cl |
| `us/ga/carroll` | service error: Invalid URL |
| `us/ga/chatham` | service error: Error handling service request :Could not find service. Service may be stopped or it may not be configured. |
| `us/ga/cherokee` | service error: Invalid URL |
| `us/ga/columbia` | service error: Service not found |
| `us/ga/coweta` | service error: Invalid URL |
| `us/ga/emanuel` | service error: Invalid URL |
| `us/ga/fannin` | service error: Invalid URL |
| `us/ga/glascock` | service error: Invalid URL |
| `us/ga/gwinnett` | service error: Service not found |
| `us/ga/habersham` | service error: Service habersham/habersham_rokmaps/MapServer not found |
| `us/ga/harris` | service error: Invalid URL |
| `us/ga/liberty` | server gis.libertycountyga.com not found |
| `us/ga/madison` | service error: Item does not exist or is inaccessible. |
| `us/ga/paulding` | service error: Invalid URL |
| `us/ga/peach` | service error: Invalid URL |
| `us/ga/pierce` | service error: Invalid URL |
| `us/ga/polk` | service error: Invalid URL |
| `us/ga/randolph` | service error: Invalid URL |
| `us/ga/screven` | service error: Invalid URL |
| `us/ga/sumter` | service error: Service Americus_SumterCo_GA/Public/MapServer not found |
| `us/ga/talbot` | service error: Invalid URL |
| `us/ga/telfair` | service error: Invalid URL |
| `us/ga/union` | service error: Invalid URL |
| `us/ga/washington` | service error: Invalid URL |
| `us/ga/wilcox` | service error: Invalid URL |
| `us/hi/hawaii` | service error: Service not found |
| `us/ia/cerro_gordo` | server gismaps.co.cerro-gordo.ia.us not found |
| `us/il/city_of_east_peoria` | server error (HTTP 404): 404 - File or directory not found. |
| `us/il/macon` | service error: Invalid URL |
| `us/ky/meade` | service error: Invalid URL |
| `us/la/bienville` | service error: Item does not exist or is inaccessible. |
| `us/la/caddo` | server error (HTTP 404): 404 - File or directory not found. |
| `us/la/tangipahoa` | service error: Invalid URL |
| `us/md/anne_arundel` | service error: Error handling service request :Server object extension 'featureserver' not found. |
| `us/md/charles` | service error: Invalid URL |
| `us/md/harford` | service error: Invalid URL |
| `us/md/washington` | service error: Service not found |
| `us/md/worcester` | service error: Layer not found |
| `us/mi/branch` | service error: Invalid URL |
| `us/mi/macomb` | service error: Layer not found |
| `us/mn/clay` | service error: Layer not found |
| `us/mn/dodge` | service error: Service not found |
| `us/mn/douglas` | service error: Service not found |
| `us/mo/iron` | server www.semogis.com not found |
| `us/mo/perry` | server www.semogis.com not found |
| `us/ms/city_of_diberville` | server error (HTTP 404): 404 - File or directory not found. |
| `us/ms/desoto` | service error: Item does not exist or is inaccessible. |
| `us/nc/avery` | service error: Service NC/Avery/MapServer not found |
| `us/nc/davidson` | service error: Service MobileMap/MapServer not found |
| `us/nc/rutherford` | server error (HTTP 404): 404 - File or directory not found. |
| `us/nc/yancey` | server error (HTTP 404): 404 - File or directory not found. |
| `us/nd/barnes` | service error: Invalid URL |
| `us/nd/burke` | service error: Invalid URL |
| `us/nd/cavalier` | service error: Invalid URL |
| `us/nd/mckenzie_county` | service error: Service not found |
| `us/nd/richland` | service error: Service not found |
| `us/nd/traill` | service error: Invalid URL |
| `us/ne/omaha` | service error: Service Parcels/MapServer not found |
| `us/ne/valley` | service error: Service Valley_County_NE_Assessor/MapServer not found |
| `us/nm/bernalillo` | server ash.bernco.gov not found |
| `us/nm/lea` | server error (HTTP 404): 404 Not Found |
| `us/nm/san_juan` | service error: Service Maps/ClerksExternal/MapServer not found |
| `us/nm/valencia` | server error (HTTP 404): Not Found |
| `us/nv/eureka` | service error: Invalid URL |
| `us/nv/lincoln` | server sei.cloudsmartgis.com not found |
| `us/oh/belmont` | service error: Invalid URL |
| `us/or/city_of_gresham` | server portal.greshamoregon.gov not found |
| `us/or/city_of_mcminnville` | service error: Service not found |
| `us/or/coos` | service error: Invalid URL |
| `us/or/crook` | server error (HTTP 404): Portal for ArcGIS Error |
| `us/or/hood_river` | service error: Invalid URL |
| `us/or/morrow` | service error: Invalid URL |
| `us/pa/butler` | server gis.co.butler.pa.us not found |
| `us/pa/cameron` | service error: Invalid URL |
| `us/pa/clarion` | server gis.co.clarion.pa.us not found |
| `us/pa/forest` | service error: Invalid URL |
| `us/pa/fulton` | service error: Invalid URL |
| `us/pa/luzerne` | service error: Invalid URL |
| `us/pa/snyder` | server error (HTTP 404): 404 - File or directory not found. |
| `us/sc/beaufort` | service error: Service Parcels/MapServer not found |
| `us/sc/berkeley` | service error: Invalid or missing input parameters. |
| `us/sc/greenville` | service error: Service GreenvilleJS/Map_Layers_JS/MapServer not found |
| `us/sc/greenwood` | server error (HTTP 404): 404 - File or directory not found. |
| `us/sd/aurora` | service error: Service AURORA_COUNTY_LAYER2/MapServer not found |
| `us/sd/fall_river` | service error: Invalid URL |
| `us/sd/hughes` | server error (HTTP 404): 404 - File or directory not found. |
| `us/sd/jackson` | service error: Service JACKSON_COUNTY_WEB_LAYERS/MapServer not found |
| `us/tx/brazos` | service error: Invalid URL |
| `us/tx/city_of_waco` | server error (HTTP 404): 404 - File or directory not found. |
| `us/tx/desoto` | service error: Invalid URL |
| `us/tx/lasalle` | service error: Invalid URL |
| `us/tx/montgomery` | service error: Invalid URL |
| `us/tx/rusk` | service error: Service not found |
| `us/tx/statewide` | service error: Service not found |
| `us/tx/vanzandt` | service error: Invalid URL |
| `us/va/alleghany` | service error: Service Alleghany/Public/MapServer not found |
| `us/va/bedford` | service error: Service not found |
| `us/va/giles` | service error: Service VA/GilesCo_WebGIS/MapServer not found |
| `us/va/henrico` | server portal.henrico.us not found |
| `us/va/isle_of_wight` | service error: Invalid URL |
| `us/va/james_city` | service error: Service JCC_GIS_Data/GIS_Data_Property/MapServer not found |
| `us/va/statewide` | server gismaps.vdem.virginia.gov not found |
| `us/va/warrenton` | service error: Error handling service request :Could not find service. Service may be stopped or it may not be configured. |
| `us/wa/asotin` | service error: Invalid URL |
| `us/wa/city_of_pasco` | service error: Item does not exist or is inaccessible. |
| `us/wa/city_of_spokane` | service error: Item does not exist or is inaccessible. |
| `us/wa/clallam` | service error: Invalid URL |
| `us/wa/douglas` | server gis.douglascountywa.net not found |
| `us/wa/garfield` | service error: Invalid URL |
| `us/wa/whatcom` | service error: Error handling service request :Could not find service. Service may be stopped or it may not be configured. |
| `us/wa/whitman` | service error: Invalid URL |
| `us/wa/yakima` | service error: Invalid URL |
| `us/wi/adams` | service error: Invalid URL |
| `us/wv/hardy` | service error: Invalid URL |
| `us/wv/lewis` | service error: Service Lewis/LewisParcels/MapServer not found |
| `us/wv/pocohontas` | service error: Item does not exist or is inaccessible. |
| `us/wv/tyler` | service error: Item does not exist or is inaccessible. |
| `us/wv/wyoming` | service error: Service Wyoming/WyomingParcels/MapServer not found |

### TLS certificate rejected (22)

| Source | Message |
|-|-|
| `us/al/blount` | the server's security certificate could not be verified |
| `us/al/jackson` | the server's security certificate could not be verified |
| `us/al/limestone` | the server's security certificate could not be verified |
| `us/al/marshall` | the server's security certificate could not be verified |
| `us/co/clear_creek` | the server's security certificate could not be verified |
| `us/fl/monroe` | the server's security certificate could not be verified |
| `us/id/ada` | the server's security certificate could not be verified |
| `us/il/coles` | the server's security certificate could not be verified |
| `us/il/moultrie` | the server's security certificate could not be verified |
| `us/il/shelby` | the server's security certificate could not be verified |
| `us/il/williamson` | the server's security certificate could not be verified |
| `us/ms/city_of_biloxi` | the server's security certificate could not be verified |
| `us/ms/copiah` | the server's security certificate could not be verified |
| `us/ms/madison` | the server's security certificate could not be verified |
| `us/ms/rankin` | the server's security certificate could not be verified |
| `us/ms/simpson` | the server's security certificate could not be verified |
| `us/nh/jaffrey` | the server's security certificate could not be verified |
| `us/oh/statewide` | the server's security certificate could not be verified |
| `us/pa/northumberland` | the server's security certificate could not be verified |
| `us/sc/anderson` | the server's security certificate could not be verified |
| `us/ut/box_elder` | the server's security certificate could not be verified |
| `us/wi/oneida` | the server's security certificate could not be verified |

### Service stopped or server down (21)

| Source | Message |
|-|-|
| `us/al/macon` | service error: Service Macon/Public/MapServer not started |
| `us/co/douglas` | server error (HTTP 502) |
| `us/co/grand` | service error: Service Property/AssesssorMap/MapServer not started |
| `us/fl/escambia` | service error: Service escambia/escambia_query_new/MapServer not started |
| `us/fl/indian_river` | service error: Could not access any server machines. Please contact your system administrator. |
| `us/ga/glynn` | service error: Could not access any server machines. Please contact your system administrator. |
| `us/id/bonneville` | server error (HTTP 503): 503 Service Temporarily Unavailable |
| `us/id/kootenai` | service error: Could not access any server machines. Please contact your system administrator. |
| `us/id/teton` | server error (HTTP 522) |
| `us/md/montgomery` | service error: Service energov/energov/MapServer not started |
| `us/md/statewide` | server error (HTTP 503): Site Maintenance |
| `us/mo/saint_francois` | service error: Service StFrancois_CO/StFrancoisCo_Assessment/MapServer not started |
| `us/pa/beaver` | service error: Service InfoAtlas/Parcels/MapServer not started |
| `us/pa/westmoreland` | server error (HTTP 526) |
| `us/sc/charleston` | service error: Service GIS_VIEWER/New_Public_Search/MapServer not started |
| `us/sc/newberry` | service error: Service PropertyParcel/MapServer not started |
| `us/sd/butte` | service error: Service BUTTE_COUNTY_WEB_LAYERS/MapServer not started |
| `us/sd/lake` | service error: Service Lake/lakemapnet/MapServer not started |
| `us/va/amherst` | service error: Service WL_Amherst/Amherst_WL_P/MapServer not started |
| `us/va/appomattox` | service error: Service WL_Appomattox/Appomattox_WL_P/MapServer not started |
| `us/va/nottoway` | service error: Service WL_Nottoway/Nottoway_WL_P/MapServer not started |

### Needs a login (token) (21)

| Source | Message |
|-|-|
| `us/az/coconino` | service error: Token Required |
| `us/ca/city_of_orange` | service error: Token Required |
| `us/ca/city_of_sunnyvale` | service error: Token Required |
| `us/ca/shasta` | service error: Token Required |
| `us/co/city_of_fort_collins` | service error: Token Required |
| `us/fl/alachua` | service error: Token Required |
| `us/fl/highlands` | service error: Token Required |
| `us/ga/dougherty` | service error: Token Required |
| `us/ga/lamar` | service error: Token Required |
| `us/ga/lee` | service error: Token Required |
| `us/ga/oconee` | service error: Token Required |
| `us/ga/oglethrope` | service error: Token Required |
| `us/il/carroll` | service error: Token Required |
| `us/la/desoto` | service error: Token Required |
| `us/or/statewide` | service error: Token Required |
| `us/sc/aiken` | service error: Token Required |
| `us/sc/hampton` | service error: Token Required |
| `us/sc/marlboro` | service error: Token Required |
| `us/sc/union` | service error: Token Required |
| `us/wa/kittitas` | service error: Token Required |
| `us/wi/grant` | service error: Token Required |

### Timed out (18)

| Source | Message |
|-|-|
| `us/al/dekalb` | the server did not answer in time |
| `us/ca/city_of_carson` | the server did not answer in time |
| `us/ca/lake` | the server did not answer in time |
| `us/ct/city_of_new_britain` | the server did not answer in time |
| `us/ga/floyd` | the server did not answer in time |
| `us/id/clearwater` | the server did not answer in time |
| `us/id/jefferson` | the server did not answer in time |
| `us/in/porter` | the server did not answer in time |
| `us/la/st_tammany_parish` | the server did not answer in time |
| `us/md/talbot` | the server did not answer in time |
| `us/ms/hinds` | the server did not answer in time |
| `us/ms/warren` | the server did not answer in time |
| `us/ms/yazoo` | the server did not answer in time |
| `us/ny/sullivan` | the server did not answer in time |
| `us/pa/mckean` | the server did not answer in time |
| `us/pa/mifflin` | the server did not answer in time |
| `us/sc/calhoun` | the server did not answer in time |
| `us/wa/cowlitz` | the server did not answer in time |

### Server-side query error (12)

| Source | Message |
|-|-|
| `us/ca/city_of_san_luis_obispo` | service error: Error performing query operation |
| `us/ca/riverside` | service error: Error performing query operation |
| `us/co/lake` | service error: Cannot perform query. Invalid query parameters. |
| `us/ga/clayton` | server error (HTTP 500): Application Error |
| `us/ga/monroe` | service error: Error invoking service |
| `us/ky/jefferson` | server error (HTTP 500): Application Error |
| `us/md/st_marys` | service error: Error performing query operation |
| `us/or/multnomah` | service error: Error performing query operation |
| `us/or/polk` | server error (HTTP 500): Application Error |
| `us/pa/clearfield` | server error (HTTP 500): 500 - Internal server error. |
| `us/sc/georgetown` | service error: Failed to execute query. |
| `us/sd/lawrence` | service error: Error performing query operation |

### Forbidden (5)

| Source | Message |
|-|-|
| `us/ca/city_of_burbank` | server error (HTTP 403): Just a moment... |
| `us/fl/volusia` | service error: You do not have permissions to access this resource or perform this operation. |
| `us/il/champaign` | service error: You do not have permissions to access this resource or perform this operation. |
| `us/il/kane` | service error: You do not have permissions to access this resource or perform this operation. |
| `us/il/piatt` | service error: You do not have permissions to access this resource or perform this operation. |

### Connection refused / reset (4)

| Source | Message |
|-|-|
| `us/ca/city_of_brea` | Network is unreachable |
| `us/la/ascension` | Connection reset |
| `us/pa/berks` | Unexpected end of file from server |
| `us/ut/cache` | Connection reset |

### Web page instead of data (2)

| Source | Message |
|-|-|
| `us/la/catahoula` | the server sent a web page instead of data: EFS GeoTechnologies |
| `us/wi/waukesha` | the server sent a web page instead of data: Waukesha County Land Information Hub |

## Appendix B: OpenAddresses definitions with stale field names

The parcel-id field in the definition matches nothing in the service, even ignoring case and table prefixes, so parcels download without `oa:pid`.

| Source | Definition asks for |
|-|-|
| `us/ak/anchorage` | `PARCEL_NUM` |
| `us/ak/kodiak_island_borough` | `PROP_ID` |
| `us/ak/skagway` | `ID` |
| `us/al/lawrence` | `PARCELID` |
| `us/ca/city_of_hayward` | `APN` |
| `us/ca/city_of_huntington_beach` | `Huntington.DBO.Parcels.APN` |
| `us/de/kent` | `PARCELID` |
| `us/fl/brevard` | `PARCEL_ID` |
| `us/fl/columbia` | `ParcelID` |
| `us/ga/mcintosh` | `ANGLE` |
| `us/ia/lyon` | `PPN` |
| `us/la/livingston` | `Parcel_Number` |
| `us/nd/sargent` | `PARCEL` |
| `us/ny/westchester` | `PRINTKEY` |
| `us/oh/delaware` | `PARCEL_NO` |
| `us/oh/union` | `parcel_no` |
| `us/or/city_of_grants_pass` | `MNX` |
| `us/or/city_of_lebanon` | `MAPTAXLOT` |
| `us/or/columbia` | `MapTaxlot` |
| `us/or/curry` | `MapTaxlot` |
| `us/sc/sumter` | `PARID` |
| `us/sd/codington` | `PARCELID` |
| `us/sd/gregory` | `PARCEL_ID` |
| `us/sd/hamlin` | `RECORD` |
| `us/sd/lyman` | `PARCEL_NUMBER` |
| `us/tx/city_of_lewisville` | `prop_id` |
| `us/va/amelia` | `ParcelID` |
| `us/va/greene` | `PIN` |
| `us/wa/thurston` | `ParcelNumber` |
| `us/wi/ashland` | `PlanID` |
| `us/wi/columbia` | `CCGIS_DATA.GISOWNER.TaxParcels_Parcels.GIS_PIN` |

## Appendix C: sources that returned nothing around their own sample parcel

Point layers are now refused with "this layer holds points, not parcel outlines". The
polygon ones were not investigated further (sample parcel outside the live layer's extent,
a stale layer, or a projection issue on the server are all possible).

| Source | Layer geometry | Layer |
|-|-|-|
| `us/fl/putnam` | polygons | https://pamap.putnam-fl.gov/server/rest/services/CadastralData/FeatureServer/2 |
| `us/ga/effingham` | polygons | https://services.arcgis.com/9scQWTgPOi3GxJRr/ArcGIS/rest/services/Parcels2020/FeatureServer/0 |
| `us/ga/hall` | points | https://hallgis.hallcounty.org/arcgis/rest/services/Accela_APO/MapServer/3 |
| `us/ga/lanier` | polygons | https://www.sgrcmaps.com/alma/rest/services/Lanier/Parcels/MapServer/2 |
| `us/ga/walker` | points | https://services7.arcgis.com/tzrZqCyu9DgupjuD/ArcGIS/rest/services/Parcels/FeatureServer/0 |
| `us/mi/barry` | polygons | https://services.arcgis.com/i0SsdDzLI3mLGmPR/ArcGIS/rest/services/Tax_Parcels/FeatureServer/265 |
| `us/pa/susquehanna` | points | https://services5.arcgis.com/lmuC4NmaSm4GH9h5/arcgis/rest/services/Addresses/FeatureServer/3 |
| `us/sd/hand` | points | https://www.1stdistrict.org/arcgis/rest/services/Hand/handmapnet/FeatureServer/4 |
| `us/sd/kingsbury` | points | https://www.1stdistrict.org/arcgis/rest/services/Kingsbury/kingsburymapnet/FeatureServer/3 |
| `us/sd/roberts` | points | https://www.1stdistrict.org/arcgis/rest/services/Roberts/robertsmapnet/MapServer/2 |
| `us/wa/city_of_snohomish` | points | https://services9.arcgis.com/hUiJ0kKwHN6Cf0DY/ArcGIS/rest/services/EnerGov_AddrParcels/FeatureServer/0 |
| `us/wa/clark` | points | https://services2.arcgis.com/ylxwjFBdCPBzP16d/arcgis/rest/services/SitusAddress/FeatureServer/0 |

## Appendix D: definitions missing from the OpenAddresses repository

Listed by the batch API but 404 on GitHub (renamed or deleted sources):

- `us/ga/city_of_savannah`
- `us/in/marion_county`
- `us/ky/perry`
- `us/oh/mahoning`
- `us/oh/perry`
- `us/oh/trumbull`
- `us/wa/snohomish_county`

## Appendix E: parcel sources that are not ESRI services

108 sources (106 http, 1 ?, 1 ftp). These publish a file (shapefile, zip, GeoJSON) that cannot be queried by area; the Download dialog lists them greyed out.

