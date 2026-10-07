// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openstreetmap.josm.plugins.addressconflation.io.PmtilesFixture.lat;
import static org.openstreetmap.josm.plugins.addressconflation.io.PmtilesFixture.lon;
import static org.openstreetmap.josm.plugins.addressconflation.io.PmtilesFixture.tags;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Kind;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;
import org.openstreetmap.josm.plugins.addressconflation.io.PmtilesFixture.MvtLayer;
import org.openstreetmap.josm.plugins.addressconflation.io.PmtilesFixture.RangeServer;

/** Reading address points from a PMTiles archive by range requests, as the NAD tileset is published. */
class PmtilesClientTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final int Z = 14;
    /** Two neighbouring zoom-14 tiles in Indiana. */
    private static final int X = 4205;
    private static final int Y = 6200;
    private static final int EXTENT = 4096;

    private static final String NAD_METADATA = "{\"name\":\"nad-r24\",\"vector_layers\":[{\"id\":\"addresses\",\"fields\":{"
            + "\"addr:housenumber\":\"String\",\"addr:street\":\"String\",\"addr:unit\":\"String\",\"addr:city\":\"String\","
            + "\"addr:state\":\"String\",\"addr:postcode\":\"String\"}}],"
            + "\"tilestats\":{\"layers\":[{\"layer\":\"addresses\",\"geometry\":\"Point\"}]}}";

    private static Map<String, String> address(String number, String street, String unit) {
        Map<String, String> t = tags("addr:housenumber", number, "addr:street", street, "addr:city", "Crawfordsville",
                "addr:state", "IN", "addr:postcode", "47933");
        if (unit != null) {
            t.put("addr:unit", unit);
        }
        return t;
    }

    /**
     * Tile X holds a building-level address and two units at one spot, a point whose unit is
     * empty, and (in its buffer) a point that belongs to tile X+1. Tile X+1 holds that point
     * again plus one near its east edge.
     */
    private static PmtilesFixture.Archive nadArchive() {
        MvtLayer a = new MvtLayer("addresses", EXTENT)
                .point(1000, 1000, address("114", "East Main Street", null))
                .point(1000, 1000, address("114", "East Main Street", "Apt A"))
                .point(1000, 1000, address("114", "East Main Street", "Apt B"))
                .point(2000, 3000, address("607 1/2", "County Road 123", ""))
                .point(EXTENT + 4, 2000, address("89-02", "Avenue N", null));
        MvtLayer b = new MvtLayer("addresses", EXTENT)
                .point(4, 2000, address("89-02", "Avenue N", null))
                .point(4000, 500, address("1", "Far East Road", null));
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive().tile(Z, X, Y, PmtilesFixture.tile(a)).tile(Z, X + 1, Y,
                PmtilesFixture.tile(b));
        archive.metadata = NAD_METADATA;
        archive.bounds = new double[] {lon(Z, X), lat(Z, Y + 1), lon(Z, X + 2), lat(Z, Y)};
        return archive;
    }

    /** The NAD tileset as a mapper would add it: every property mapped by Check's guesses. */
    private static FeatureSource nadSource(String url) {
        Map<String, List<String>> f = new LinkedHashMap<>();
        for (String key : CustomSource.keysFor(Kind.ADDRESSES)) {
            f.put(key, List.of(CustomSource.guessField(key, List.of("addr:housenumber", "addr:street", "addr:unit", "addr:city",
                    "addr:state", "addr:postcode"))));
        }
        return new CustomSource("NAD tiles", Protocol.PMTILES, url, Kind.ADDRESSES, f, null).toFeatureSource();
    }

    /** Both tiles, except the last 600 units of the second one's width; just inside the tile edges. */
    private static Bounds view() {
        double inset = 0.5 / EXTENT;
        return new Bounds(lat(Z, Y + 1 - inset), lon(Z, X + inset), lat(Z, Y + inset), lon(Z, X + 1 + 3500.0 / EXTENT));
    }

    @Test
    void tileIdsFollowTheHilbertCurveOfTheSpec() {
        assertEquals(0, PmtilesClient.tileId(0, 0, 0));
        assertEquals(1, PmtilesClient.tileId(1, 0, 0));
        assertEquals(2, PmtilesClient.tileId(1, 0, 1));
        assertEquals(3, PmtilesClient.tileId(1, 1, 1));
        assertEquals(4, PmtilesClient.tileId(1, 1, 0));
        assertEquals(5, PmtilesClient.tileId(2, 0, 0));
        assertEquals(19078479, PmtilesClient.tileId(12, 3423, 1763));
        assertThrows(IllegalArgumentException.class, () -> PmtilesClient.tileId(2, 4, 0));
    }

    @Test
    void directoryFindsTilesRunsAndLeaves() throws IOException {
        List<long[]> entries = new ArrayList<>();
        entries.add(new long[] {10, 0, 100, 1});
        entries.add(new long[] {11, 100, 50, 3}); // 11, 12, 13 share one tile (ocean)
        entries.add(new long[] {20, 500, 40, 0}); // leaf directory for 20 and up
        PmtilesClient.Directory d = PmtilesClient.Directory.parse(PmtilesFixture.directory(entries));
        assertEquals(-1, d.find(9));
        assertEquals(0, d.find(10));
        assertEquals(1, d.find(13));
        assertEquals(100, d.offsets[1], "offset 0 means right after the previous entry");
        assertEquals(-1, d.find(14));
        assertEquals(2, d.find(20));
        assertEquals(2, d.find(999), "everything past a leaf entry is looked up in the leaf");
        assertEquals(500, d.offsets[2]);
    }

    @Test
    void tilesCoveringAViewAtTheCompleteZoom() {
        List<int[]> tiles = PmtilesClient.tilesCovering(view(), Z);
        assertEquals(List.of(X + "/" + Y, (X + 1) + "/" + Y),
                tiles.stream().map(t -> t[0] + "/" + t[1]).collect(Collectors.toList()));
    }

    @Test
    void addressesInTheViewBecomeNodesWithTheirTags() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            DataSet ds = PmtilesClient.download(nadSource(server.url()), view(), null);
            List<Node> nodes = new ArrayList<>(ds.getNodes());
            nodes.sort(Comparator.comparing((Node n) -> n.get("addr:housenumber")).thenComparing(n -> String.valueOf(n.get("addr:unit"))));
            assertEquals(List.of("114", "114", "114", "607 1/2", "89-02"),
                    nodes.stream().map(n -> n.get("addr:housenumber")).collect(Collectors.toList()),
                    "units of one building are all kept; the buffered copy is read once; the point outside the view is not");

            Node building = nodes.get(2);
            assertNull(building.get("addr:unit"), "the building-level address keeps no unit");
            assertEquals("East Main Street", building.get("addr:street"));
            assertEquals("Crawfordsville", building.get("addr:city"));
            assertEquals("IN", building.get("addr:state"));
            assertEquals("47933", building.get("addr:postcode"));
            assertEquals("Apt A", nodes.get(0).get("addr:unit"));
            assertNull(nodes.get(3).get("addr:unit"), "an empty property is no value, not an empty tag");

            assertEquals(lon(Z, X + 1000.0 / EXTENT), building.lon(), 1e-9);
            assertEquals(lat(Z, Y + 1000.0 / EXTENT), building.lat(), 1e-9);
            assertEquals(lon(Z, X + 1 + 4.0 / EXTENT), nodes.get(4).lon(), 1e-9, "placed from the tile it belongs to");
        }
    }

    @Test
    void entriesInALeafDirectoryAreFound() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.leaf = true;
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(5, PmtilesClient.download(nadSource(server.url()), view(), null).getNodes().size());
        }
    }

    @Test
    void uncompressedArchivesAreReadToo() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.tileCompression = PmtilesClient.COMPRESSION_NONE;
        archive.internalCompression = PmtilesClient.COMPRESSION_NONE;
        try (RangeServer server = new RangeServer(archive.build())) {
            assertEquals(5, PmtilesClient.download(nadSource(server.url()), view(), null).getNodes().size());
        }
    }

    @Test
    void theHeaderAndRootAreReadOncePerSession() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            FeatureSource src = nadSource(server.url());
            PmtilesClient.download(src, view(), null);
            int first = server.requests.get();
            assertEquals(3, first, "one read for header and root, one per tile");
            PmtilesClient.download(src, view(), null);
            assertEquals(first + 2, server.requests.get(), "only the tiles again");
        }
    }

    @Test
    void checkReadsFieldsCoverageAndGeometry() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            ServiceInspector.Info info = ServiceInspector.inspect(server.url(), Protocol.PMTILES);
            assertEquals("nad-r24", info.getTitle());
            assertEquals(ServiceInspector.Geometry.POINTS, info.getGeometry());
            assertEquals(List.of("addr:housenumber", "addr:street", "addr:unit", "addr:city", "addr:state", "addr:postcode"), info.getFields());
            assertNotNull(info.getExtent());
            assertEquals(lon(Z, X), info.getExtent().getMinLon(), 1e-6);
            assertEquals(lat(Z, Y), info.getExtent().getMaxLat(), 1e-6);
            assertEquals("addr:housenumber", CustomSource.guessField("number", info.getFields()));
            assertEquals("addr:state", CustomSource.guessField("region", info.getFields()));
        }
    }

    @Test
    void withoutMetadataASampleTileTellsFieldsAndGeometry() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.metadata = "{}";
        // The sample is the tile at the header's centre.
        archive.bounds = new double[] {lon(Z, X), lat(Z, Y + 1), lon(Z, X + 1), lat(Z, Y)};
        try (RangeServer server = new RangeServer(archive.build())) {
            ServiceInspector.Info info = ServiceInspector.inspect(server.url(), Protocol.PMTILES);
            assertEquals(ServiceInspector.Geometry.POINTS, info.getGeometry());
            assertTrue(info.getFields().containsAll(List.of("addr:housenumber", "addr:street", "addr:unit")), info.getFields().toString());
        }
    }

    @Test
    void severalLayersAskTheMapperToPickOne() throws IOException {
        MvtLayer addresses = new MvtLayer("addresses", EXTENT).point(100, 100, address("1", "A Street", null));
        MvtLayer audit = new MvtLayer("audit", EXTENT).point(100, 100, tags("uuid", "x"));
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive().tile(Z, X, Y, PmtilesFixture.tile(addresses, audit));
        archive.metadata = "{\"vector_layers\":[{\"id\":\"addresses\",\"fields\":{\"addr:housenumber\":\"String\"}},"
                + "{\"id\":\"audit\",\"fields\":{\"uuid\":\"String\"}}]}";
        try (RangeServer server = new RangeServer(archive.build())) {
            ServiceInspector.NotALayerException e = assertThrows(ServiceInspector.NotALayerException.class,
                    () -> ServiceInspector.inspect(server.url(), Protocol.PMTILES));
            assertEquals(List.of(server.url() + "#addresses", server.url() + "#audit"), new ArrayList<>(e.getLayers().keySet()));

            ServiceInspector.Info info = ServiceInspector.inspect(server.url() + "#audit", Protocol.PMTILES);
            assertEquals(List.of("uuid"), info.getFields());
            IOException missing = assertThrows(IOException.class, () -> ServiceInspector.inspect(server.url() + "#nope", Protocol.PMTILES));
            assertTrue(missing.getMessage().contains("nope"), missing.getMessage());

            DataSet ds = PmtilesClient.download(nadSource(server.url() + "#addresses"), view(), null);
            assertEquals(1, ds.getNodes().size(), "only the named layer is read");
        }
    }

    @Test
    void polygonTilesetsAreRefused() throws IOException {
        MvtLayer parcels = new MvtLayer("parcels", EXTENT).square(100, 100, 200, tags("pid", "1"));
        PmtilesFixture.Archive archive = new PmtilesFixture.Archive().tile(Z, X, Y, PmtilesFixture.tile(parcels));
        archive.bounds = new double[] {lon(Z, X), lat(Z, Y + 1), lon(Z, X + 1), lat(Z, Y)};
        try (RangeServer server = new RangeServer(archive.build())) {
            IOException e = assertThrows(IOException.class, () -> ServiceInspector.inspect(server.url(), Protocol.PMTILES));
            assertTrue(e.getMessage().contains("only address points"), e.getMessage());
        }
    }

    @Test
    void compressionTheJdkCannotReadIsRefusedClearly() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.internalCompression = PmtilesClient.COMPRESSION_NONE;
        archive.tileCompression = PmtilesClient.COMPRESSION_NONE;
        byte[] file = archive.build();
        file[98] = 3; // brotli tiles
        try (RangeServer server = new RangeServer(file)) {
            IOException e = assertThrows(IOException.class, () -> ServiceInspector.inspect(server.url(), Protocol.PMTILES));
            assertTrue(e.getMessage().contains("brotli"), e.getMessage());
        }
    }

    @Test
    void imageTilesetsAndOtherFilesAreRefused() throws IOException {
        PmtilesFixture.Archive archive = nadArchive();
        archive.tileType = 2;
        try (RangeServer server = new RangeServer(archive.build())) {
            IOException e = assertThrows(IOException.class, () -> ServiceInspector.inspect(server.url(), Protocol.PMTILES));
            assertTrue(e.getMessage().contains("images"), e.getMessage());
        }
        try (RangeServer server = new RangeServer("<html>Not here</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            IOException e = assertThrows(IOException.class, () -> ServiceInspector.inspect(server.url(), Protocol.PMTILES));
            assertTrue(e.getMessage().contains("not a PMTiles file"), e.getMessage());
        }
    }

    @Test
    void aServerWithoutRangeRequestsIsRefused() throws IOException {
        byte[] big = new byte[100_000];
        byte[] real = nadArchive().build();
        System.arraycopy(real, 0, big, 0, real.length);
        try (RangeServer server = new RangeServer(big)) {
            server.honourRange = false;
            IOException e = assertThrows(IOException.class, () -> PmtilesClient.download(nadSource(server.url()), view(), null));
            assertTrue(e.getMessage().contains("range requests"), e.getMessage());
        }
    }

    @Test
    void aFileReplacedUnderTheSameUrlIsReopened() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            FeatureSource src = nadSource(server.url());
            assertEquals(5, PmtilesClient.download(src, view(), null).getNodes().size());

            // nad-current.pmtiles repointed at the next release.
            MvtLayer next = new MvtLayer("addresses", EXTENT).point(10, 10, address("2", "New Street", null));
            PmtilesFixture.Archive release = new PmtilesFixture.Archive().tile(Z, X, Y, PmtilesFixture.tile(next));
            release.leaf = true;
            server.file = release.build();
            server.etag = "\"2\"";
            DataSet ds = PmtilesClient.download(src, view(), null);
            assertEquals(List.of("New Street"), ds.getNodes().stream().map(n -> n.get("addr:street")).collect(Collectors.toList()));
        }
    }

    @Test
    void aMissingFileSaysSo() throws IOException {
        try (RangeServer server = new RangeServer(new byte[0])) {
            IOException e = assertThrows(IOException.class,
                    () -> ServiceInspector.inspect(server.url().replace("x.pmtiles", "y.pmtiles"), Protocol.PMTILES));
            assertTrue(e.getMessage().contains("404"), e.getMessage());
        }
    }

    @Test
    void atTheFeatureLimitTheMapperIsAskedOnce() throws IOException {
        try (RangeServer server = new RangeServer(nadArchive().build())) {
            List<String> asked = new ArrayList<>();
            // Tile X has 4 points in the view, tile X+1 has 1; a limit of 1 is reached by the first tile in.
            DataSet stopped = PmtilesClient.download(nadSource(server.url()), view(), null, new FeatureLimit(1, (name, count) -> {
                asked.add(name);
                return false;
            }));
            assertEquals(List.of("NAD tiles"), asked);
            int n = stopped.getNodes().size();
            assertTrue(n == 4 || n == 1, "stopping keeps whole tiles, got " + n);

            asked.clear();
            DataSet all = PmtilesClient.download(nadSource(server.url()), view(), null, new FeatureLimit(1, (name, count) -> {
                asked.add(name);
                return true;
            }));
            assertEquals(1, asked.size(), "asked once, then the rest comes without asking");
            assertEquals(5, all.getNodes().size());
        }
    }

    @Test
    void onlyAddressesComeFromPmtiles() {
        FeatureSource parcels = new CustomSource("tiles", Protocol.PMTILES, "https://example.org/p.pmtiles", Kind.PARCELS,
                Collections.emptyMap(), null).toFeatureSource();
        assertThrows(IOException.class, () -> PmtilesClient.download(parcels, view(), null));
    }

    @Test
    void pmtilesUrlsAreRecognisedAndStored() {
        assertEquals(Protocol.PMTILES, CustomSource.detect("https://data.example.org/nad/nad-current.pmtiles"));
        assertEquals(Protocol.PMTILES, CustomSource.detect("https://data.example.org/nad-r24.PMTILES#addresses"));
        assertEquals(Protocol.PMTILES, CustomSource.detect("https://bucket.example.com/nad-r24.pmtiles?X-Amz-Signature=abc"));
        assertNull(CustomSource.detect("https://data.example.org/nad-r24.json"));
        assertEquals("https://x/n.pmtiles#addresses", CustomSource.cleanUrl("  https://x/n.pmtiles#addresses "));

        assertEquals("https://x/n.pmtiles", PmtilesClient.fileUrl("https://x/n.pmtiles#addresses"));
        assertEquals("addresses", PmtilesClient.layerName("https://x/n.pmtiles#addresses"));
        assertEquals("my layer", PmtilesClient.layerName("https://x/n.pmtiles#my%20layer"));
        assertNull(PmtilesClient.layerName("https://x/n.pmtiles"));
        assertNull(PmtilesClient.layerName("https://x/n.pmtiles#"));

        CustomSource cs = new CustomSource("NAD tiles", Protocol.PMTILES, "https://x/n.pmtiles#addresses", Kind.ADDRESSES,
                Map.of("number", List.of("addr:housenumber"), "street", List.of("addr:street")), new Bounds(18, -167, 66, -64));
        CustomSource back = CustomSource.fromMap(cs.toMap());
        assertNotNull(back);
        assertEquals(Protocol.PMTILES, back.getProtocol());
        assertEquals("https://x/n.pmtiles#addresses", back.getUrl());
        assertEquals(List.of("addr:housenumber"), back.getFields().get("number"));
        assertFalse(back.covers(new Bounds(51, 0, 52, 1)), "coverage from the header keeps it out of other continents");
    }
}
