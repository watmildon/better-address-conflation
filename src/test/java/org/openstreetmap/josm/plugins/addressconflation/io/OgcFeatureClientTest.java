// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Kind;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;

/** OGC API - Features collections download page by page and convert like ArcGIS layers. */
class OgcFeatureClientTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final Bounds VIEW = new Bounds(40.0, -87.0, 40.01, -86.99);

    private static String parcel(String pin, double lon) {
        return String.format(java.util.Locale.ROOT, "{\"type\":\"Feature\",\"properties\":{\"Pin\":\"%s\"},\"geometry\":{\"type\":\"Polygon\","
                + "\"coordinates\":[[[%f,40.0],[%f,40.0],[%f,40.001],[%f,40.0]]]}}", pin, lon, lon + 0.001, lon + 0.001, lon);
    }

    private static FeatureSource parcels(String url) {
        return new FeatureSource("County parcels", url, Kind.PARCELS, Map.of("pid", List.of("PIN")), null, false)
                .withProtocol(Protocol.OGC_FEATURES);
    }

    @Test
    void followsNextLinksAndMatchesFieldCase() throws IOException {
        try (FakeServer server = new FakeServer(r -> {
            if (r.startsWith("/ogc/collections/parcels/items") && r.contains("page=2")) {
                return FakeServer.json("{\"type\":\"FeatureCollection\",\"features\":[" + parcel("C", -86.996) + "],\"links\":[]}");
            }
            if (r.startsWith("/ogc/collections/parcels/items")) {
                return FakeServer.json("{\"type\":\"FeatureCollection\",\"features\":[" + parcel("A", -86.999) + "," + parcel("B", -86.998)
                        + "],\"links\":[{\"rel\":\"next\",\"type\":\"text/html\",\"href\":\"items.html?page=2\"},"
                        + "{\"rel\":\"next\",\"type\":\"application/geo+json\",\"href\":\"items?page=2\"}]}");
            }
            return null;
        })) {
            DataSet ds = OgcFeatureClient.download(parcels(server.url("/ogc/collections/parcels")), VIEW, NullProgressMonitor.INSTANCE);
            Set<String> pids = ds.getWays().stream().map(w -> w.get("oa:pid")).collect(Collectors.toSet());
            assertEquals(Set.of("A", "B", "C"), pids, "PIN in the conform found the service's Pin");
            assertTrue(ds.getWays().stream().allMatch(Way::isClosed));
            String first = server.requests.get(0);
            assertTrue(first.contains("bbox=-87.000000%2C40.000000%2C-86.990000%2C40.010000"), first);
            assertTrue(first.contains("limit=" + OgcFeatureClient.PAGE), first);
            assertEquals(2, server.requests.size(), "HTML alternate skipped, then no next link: " + server.requests);
        }
    }

    @Test
    void keepsTheQueryAKeyLivesIn() {
        String u = OgcFeatureClient.itemsUrl("https://m.example.gov/ogc/collections/parcels/?apikey=abc", VIEW);
        assertTrue(u.startsWith("https://m.example.gov/ogc/collections/parcels/items?apikey=abc&bbox="), u);
    }

    @Test
    void pointsAreNotParcels() throws IOException {
        try (FakeServer server = new FakeServer(r -> FakeServer.json("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{\"Pin\":\"A\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-86.995,40.005]}}]}"))) {
            IOException e = assertThrows(IOException.class,
                    () -> OgcFeatureClient.download(parcels(server.url("/c/collections/p")), VIEW, NullProgressMonitor.INSTANCE));
            assertTrue(e.getMessage().contains("points"), e.getMessage());
        }
    }

    @Test
    void aWebPageIsReportedAsSuch() throws IOException {
        try (FakeServer server = new FakeServer(r -> new Object[] {200, "text/html", "<html><head><title>Sign in</title></head></html>"})) {
            IOException e = assertThrows(IOException.class,
                    () -> OgcFeatureClient.download(parcels(server.url("/c/collections/p")), VIEW, NullProgressMonitor.INSTANCE));
            assertTrue(e.getMessage().contains("web page") && e.getMessage().contains("Sign in"), e.getMessage());
        }
    }

    @Test
    void noJsonLinkMeansNoNextPage() {
        JsonObject doc = JsonSupport.JSON.createReader(new StringReader(
                "{\"links\":[{\"rel\":\"next\",\"type\":\"text/html\",\"href\":\"x.html\"},{\"rel\":\"self\",\"href\":\"items\"}]}")).readObject();
        assertNull(OgcFeatureClient.link(doc, "next", "https://m/collections/p/items"));
    }
}
