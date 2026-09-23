// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;

import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the misbehaving county servers found by the parcel source survey
 * (research/parcel-source-survey.md): the download must get through, or fail with the
 * server's own reason.
 */
class EsriFallbackServerTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String FEATURES = "{\"features\":[{\"attributes\":{\"PID\":\"7\"},"
            + "\"geometry\":{\"rings\":[[[-112.0,33.0],[-111.999,33.0],[-111.999,33.001],[-112.0,33.0]]]}}]}";
    private static final Bounds VIEW = new Bounds(32.999, -112.001, 33.002, -111.998);

    private HttpServer server;

    /** Serve /FeatureServer/0 with a handler from the query string to (status, content type, body). */
    private EsriFeatureSource serve(Function<String, Object[]> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/FeatureServer/0", ex -> {
            String q = String.valueOf(ex.getRequestURI().getRawQuery());
            Object[] r = q.equals("f=json") && !ex.getRequestURI().getPath().endsWith("/query")
                    ? new Object[] {200, "application/json", "{\"fields\":[{\"name\":\"PID\"}]}"} : handler.apply(q);
            byte[] body = ((String) r[2]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", (String) r[1]);
            ex.sendResponseHeaders((Integer) r[0], body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/FeatureServer/0";
        return new EsriFeatureSource("test", url, EsriFeatureSource.Kind.PARCELS, Map.of("pid", List.of("PID")), null, false);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void htmlForGeoJsonAndNoPagingStillDownloads() throws IOException {
        // Like us/fl/clay (no GeoJSON) and us/ca/riverside (no pagination) at once.
        EsriFeatureSource src = serve(q -> {
            if (q.contains("f=geojson")) {
                return new Object[] {200, "text/html", "<html><head><title>Error: Output format not supported</title></head></html>"};
            }
            if (q.contains("resultOffset")) {
                return new Object[] {200, "application/json", "{\"error\":{\"code\":400,\"message\":\"Pagination is not supported.\",\"details\":[]}}"};
            }
            return new Object[] {200, "application/json", FEATURES};
        });
        DataSet ds = EsriFeatureClient.download(src, VIEW, NullProgressMonitor.INSTANCE);
        assertEquals(1, ds.getWays().size());
        assertEquals("7", ds.getWays().iterator().next().get("oa:pid"));
    }

    @Test
    void deadServiceReportsTheReasonNotTheUrl() throws IOException {
        // Like us/id/kootenai: an HTML page for GeoJSON, the real reason only in Esri JSON.
        EsriFeatureSource src = serve(q -> q.contains("f=geojson")
                ? new Object[] {200, "text/html", "<!DOCTYPE html><html><body>oops</body></html>"}
                : new Object[] {200, "application/json", "{\"status\":\"error\",\"messages\":[\"Could not access any server machines.\"]}"});
        IOException e = assertThrows(IOException.class, () -> EsriFeatureClient.download(src, VIEW, NullProgressMonitor.INSTANCE));
        assertEquals("service error: Could not access any server machines.", e.getMessage());
        assertTrue(!e.getMessage().contains("http"), e.getMessage());
    }
}
