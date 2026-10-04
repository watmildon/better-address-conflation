// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.addressconflation.io.EsriFeatureClient;
import org.openstreetmap.josm.plugins.addressconflation.io.EsriFeatureSource;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesReader;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesSourceReader;

/** Source definitions and feature conversion, with no network. */
class DownloadSourcesTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void parsesMaricopaSource() throws IOException {
        List<EsriFeatureSource> sources;
        try (InputStream is = JosmTestSetup.resource("fixtures/us-az-maricopa.oa.json")) {
            sources = OpenAddressesSourceReader.parse(is, true);
        }
        // addresses, parcels, centerlines (centerlines are neither addresses nor parcels but still ESRI)
        assertTrue(sources.size() >= 2, sources.toString());
        EsriFeatureSource addr = sources.stream().filter(s -> s.getKind() == EsriFeatureSource.Kind.ADDRESSES).findFirst().orElseThrow();
        assertEquals(Arrays.asList("HseNo", "HseNoSufx"), addr.getConform().get("number"));
        assertEquals(Arrays.asList("StDir", "StName", "StType", "StSufx"), addr.getConform().get("street"));
        assertTrue(addr.getName().startsWith("Maricopa"), addr.getName());
        EsriFeatureSource parcels = sources.stream().filter(s -> s.getKind() == EsriFeatureSource.Kind.PARCELS).findFirst().orElseThrow();
        assertEquals(List.of("APN"), parcels.getConform().get("pid"));
        assertTrue(addr.outFields().contains("HseNo,"));
    }

    @Test
    void resolvesReferences() {
        assertEquals("https://raw.githubusercontent.com/openaddresses/openaddresses/master/sources/us/az/maricopa.json",
                OpenAddressesSourceReader.resolve("us/az/maricopa"));
        assertEquals("https://raw.githubusercontent.com/openaddresses/openaddresses/master/sources/us/az/maricopa.json",
                OpenAddressesSourceReader.resolve("US/AZ/maricopa.json"));
        assertEquals("https://example.org/x.json", OpenAddressesSourceReader.resolve("https://example.org/x.json"));
    }

    @Test
    void convertsNadFeatures() throws IOException {
        JsonObject page;
        try (InputStream is = JosmTestSetup.resource("fixtures/nad-sample.geojson");
             JsonReader r = Json.createReader(is)) {
            page = r.readObject();
        }
        EsriFeatureSource nad = EsriFeatureSource.nad();
        List<JsonObject> oa = new ArrayList<>();
        for (JsonValue v : page.getJsonArray("features")) {
            JsonObject o = nad.toOaFeature(v.asJsonObject());
            if (o != null) {
                oa.add(o);
            }
        }
        assertEquals(page.getJsonArray("features").size(), oa.size());
        DataSet ds = OpenAddressesReader.fromFeatures(oa, OpenAddressesReader.Layer.ADDRESSES, nad.isExpandStreets());
        assertEquals(oa.size(), ds.getNodes().size());
        Node n = ds.getNodes().iterator().next();
        assertNotNull(n.get("addr:housenumber"));
        assertNotNull(n.get("addr:street"));
        assertEquals("AZ", n.get("addr:state"));
        // Esri already expanded the street names; the reader must not mangle them.
        assertTrue(n.get("addr:street").matches("(North|South|East|West) .*"), n.get("addr:street"));
        assertTrue(n.lat() > 33.55 && n.lat() < 33.58);
    }

    @Test
    void parcelPolygonAsAddressSourceBecomesCentroid() {
        Map<String, List<String>> conform = new LinkedHashMap<>();
        conform.put("number", List.of("NUM"));
        conform.put("street", List.of("DIR", "STREET", "TYPE"));
        EsriFeatureSource src = new EsriFeatureSource("t", "https://example.org/FeatureServer/0", EsriFeatureSource.Kind.ADDRESSES, conform, null, true);
        JsonObject feature = parse("{\"type\":\"Feature\",\"properties\":{\"NUM\":\"12\",\"DIR\":\"N\",\"STREET\":\"MAIN\",\"TYPE\":\"ST\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-112.0,33.0],[-111.999,33.0],[-111.999,33.001],[-112.0,33.001],[-112.0,33.0]]]}}");
        JsonObject oa = src.toOaFeature(feature);
        assertNotNull(oa);
        assertEquals("Point", oa.getJsonObject("geometry").getString("type"));
        double lon = oa.getJsonObject("geometry").getJsonArray("coordinates").getJsonNumber(0).doubleValue();
        double lat = oa.getJsonObject("geometry").getJsonArray("coordinates").getJsonNumber(1).doubleValue();
        assertEquals(-111.9995, lon, 1e-6);
        assertEquals(33.0005, lat, 1e-6);
        assertEquals("N MAIN ST", oa.getJsonObject("properties").getString("street"));
        DataSet ds = OpenAddressesReader.fromFeatures(List.of(oa), OpenAddressesReader.Layer.ADDRESSES, true);
        assertEquals("North Main Street", ds.getNodes().iterator().next().get("addr:street"));
        // no housenumber: dropped
        JsonObject noNumber = parse("{\"type\":\"Feature\",\"properties\":{\"DIR\":\"N\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-112,33]}}");
        assertNull(src.toOaFeature(noNumber));
    }

    @Test
    void convertsMicrosoftFootprints() {
        EsriFeatureSource ms = EsriFeatureSource.microsoftBuildings();
        assertEquals(EsriFeatureSource.Kind.BUILDINGS, ms.getKind());
        JsonObject feature = parse("{\"type\":\"Feature\",\"properties\":{\"OBJECTID\":42,\"StateAbbrev\":\"ID\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-116.1,43.0],[-116.0999,43.0],[-116.0999,43.0001],[-116.1,43.0001],[-116.1,43.0]]]}}");
        JsonObject oa = ms.toOaFeature(feature);
        assertNotNull(oa);
        assertEquals("42", oa.getJsonObject("properties").getString("id"));
        DataSet ds = OpenAddressesReader.fromFeatures(List.of(oa), OpenAddressesReader.Layer.BUILDINGS, false);
        assertEquals(1, ds.getWays().size());
        assertNotNull(ds.getWays().iterator().next().get("building"));
    }

    @Test
    void areaGuard() {
        assertNull(EsriFeatureClient.areaProblem(new Bounds(33.56, -112.18, 33.57, -112.17)));
        assertNotNull(EsriFeatureClient.areaProblem(new Bounds(33.0, -113.0, 34.0, -112.0)));
    }

    private static JsonObject parse(String s) {
        try (JsonReader r = Json.createReader(new StringReader(s))) {
            return r.readObject();
        }
    }
}
