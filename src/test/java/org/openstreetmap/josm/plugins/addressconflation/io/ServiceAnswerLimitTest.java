// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;

import com.sun.net.httpserver.HttpServer;

/** A pasted file URL must fail Check quickly, not download gigabytes as if it were a service answer. */
class ServiceAnswerLimitTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Claims to be 2 GB, as a published tileset is; sends a little and hangs up.
        server.createContext("/files/", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
            ex.sendResponseHeaders(200, 2_000_000_000L);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(new byte[1024]);
            } catch (IOException e) {
                // The client stopped reading, as it should.
            }
        });
        // No length given: streams more than the test's limit.
        server.createContext("/stream", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write("{\"features\":[".getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 1000; i++) {
                    os.write("{\"type\":\"Feature\"},".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // The client stopped reading, as it should.
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    void aPmtilesFileCheckedAsAServiceSaysWhatItIs() {
        for (Protocol p : new Protocol[] {Protocol.ARCGIS, Protocol.OGC_FEATURES}) {
            IOException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(IOException.class, () -> ServiceInspector.inspect(url("/files/nad-current.pmtiles"), p)));
            assertTrue(e.getMessage().contains("PMTiles file") && e.getMessage().contains("choose PMTiles"), p + ": " + e.getMessage());
        }
    }

    @Test
    void anyOtherLargeFileIsRefusedWithItsSize() {
        IOException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IOException.class, () -> ServiceInspector.inspect(url("/files/parcels.zip"), Protocol.ARCGIS)));
        assertTrue(e.getMessage().contains("this is a file of") && e.getMessage().contains("GB"), e.getMessage());
    }

    @Test
    void anAnswerWithoutALengthIsCutOffAtTheLimit() {
        IOException e = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IOException.class, () -> EsriFeatureClient.get(url("/stream"), "application/json", 10_000, 4_000)));
        assertTrue(e.getMessage().contains("the server sent more than"), e.getMessage());
    }
}
