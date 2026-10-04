# Development

Notes for working on the plugin. The user-facing documentation is the top-level
[README](../README.md).

## Layout

| Path | What is there |
|---|---|
| `src/main/java/.../addressconflation/` | The plugin. `engine/` matches and buckets, `cells/` builds parcel and Voronoi cells, `apply/` turns proposals into JOSM commands, `gui/` holds the panel, setup window, overlay and preferences, `io/` downloads and reads source data, `license/` assesses source licences, `validation/` holds the validator test. |
| `src/main/resources/data/license-clearances.json` | Recorded permissions and state-law rulings that override a source's declared licence. |
| `src/test/` | JUnit 5 tests. `TestBedTest` scores the engine against the test beds. |
| [`test-data/`](../test-data/README.md) | Real-world test beds and fixtures. Also a test resource root. |
| `tools/testbed/` | Scripts that regenerate the test beds. |
| [`research/`](../research/) | Surveys behind design decisions: parcel source quirks, licence compatibility. |
| [`PLAN.md`](PLAN.md) | Design plan and roadmap. |

## Building

Use JDK 21, the version CI uses. The Gradle 8.5 wrapper does not run on newer JDKs, so point
`JAVA_HOME` at a 21 install if your default `java` is newer.

```bash
./gradlew build          # compile, test, jar in build/dist/
./gradlew build -x test  # jar only
./gradlew test
./gradlew runJosm        # a separate JOSM instance with the plugin loaded
```

Local builds are versioned `dev-SNAPSHOT`. JOSM 19439 is both the compile target and the
minimum version in the manifest (`build.gradle`).

## Trying a build in your own JOSM

1. `./gradlew build -x test`
2. Close JOSM. It rewrites `preferences.xml` on exit.
3. Copy `build/dist/better-address-conflation.jar` into the JOSM plugins folder (paths are in the
   [README](../README.md#installing)).
4. Start JOSM and enable the plugin under **Preferences → Plugins** the first time.

After that, rebuild, copy the jar, and restart JOSM to pick up changes.

## Tests

`./gradlew test` runs everything except `ParcelSourceSurvey`, which is skipped unless
`SURVEY_OUT` is set. That class downloads a sample from every OpenAddresses parcel source and
takes a long time:

```bash
SURVEY_OUT=/tmp/survey.jsonl ./gradlew test --tests '*ParcelSourceSurvey'
```

`SURVEY_PREFIX` picks the sources (default `us/`). Past results are in `research/data/` and are
written up in [research/parcel-source-survey.md](../research/parcel-source-survey.md).

The test beds, how they were made, and how to regenerate them are described in
[test-data/README.md](../test-data/README.md). `TestBedTest` prints per-bed accuracy and every
wrong match, which is the main signal when tuning the engine.

Tests initialise just enough of JOSM through `JosmTestSetup`. Register it with
`@RegisterExtension static JosmTestSetup josm = new JosmTestSetup();` in any test that touches
JOSM preferences, projections, icons or HTTP.

## Code in the tree with no UI yet

* `OpenAddressesImportAction` loads an OpenAddresses addresses, parcels or buildings file
  (line-delimited GeoJSON) as a layer. It is not registered in any menu.
* `DownloadSourceAction.DownloadTask(String oaRef, ...)` downloads the layers of an
  OpenAddresses source by id (`us/az/maricopa`), URL or local file.
* The `addressconflation.oa.expandStreets` preference (default true) expands county-style street
  abbreviations on those OpenAddresses paths. It has no checkbox and can be changed only in
  JOSM's advanced preferences.

## CI and releases

* `.github/workflows/ci.yml` runs `./gradlew build` on Ubuntu with JDK 21 for pushes and pull
  requests to `main`.
* `.github/workflows/release.yml` runs on a `v*` tag. It builds with `RELEASE_VERSION` set from
  the tag (`v0.1.0` becomes `0.1.0`), runs the tests, and attaches
  `build/dist/better-address-conflation.jar` to a GitHub Release with generated notes.
