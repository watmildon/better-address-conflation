// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;
import org.openstreetmap.josm.plugins.addressconflation.io.ServiceInspector.Geometry;
import org.openstreetmap.josm.plugins.addressconflation.io.ServiceInspector.Info;
import org.openstreetmap.josm.plugins.addressconflation.io.ServiceInspector.NotALayerException;

/** The editor's Check button: what services say about themselves. */
class ServiceInspectorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void arcgisLayerGivesFieldsGeometryAndAReprojectedExtent() throws IOException {
        try (FakeServer server = new FakeServer(r -> {
            if (r.equals("/rest/services/Parcels/FeatureServer/0?f=json")) {
                return FakeServer.json("{\"name\":\"Tax Parcels\",\"geometryType\":\"esriGeometryPolygon\","
                        + "\"fields\":[{\"name\":\"OBJECTID\"},{\"name\":\"PIN\"}],"
                        + "\"extent\":{\"xmin\":3000000,\"ymin\":1500000,\"xmax\":3100000,\"ymax\":1600000,\"spatialReference\":{\"wkid\":2965}}}");
            }
            if (r.startsWith("/rest/services/Parcels/FeatureServer/0/query") && r.contains("returnExtentOnly=true") && r.contains("outSR=4326")) {
                return FakeServer.json("{\"extent\":{\"xmin\":-87.1,\"ymin\":39.9,\"xmax\":-86.7,\"ymax\":40.2,\"spatialReference\":{\"wkid\":4326}}}");
            }
            return null;
        })) {
            Info info = ServiceInspector.inspect(server.url("/rest/services/Parcels/FeatureServer/0"), Protocol.ARCGIS);
            assertEquals("Tax Parcels", info.getTitle());
            assertEquals(Geometry.POLYGONS, info.getGeometry());
            assertEquals(List.of("OBJECTID", "PIN"), info.getFields());
            assertEquals(39.9, info.getExtent().getMinLat(), 1e-9);
            assertEquals(-86.7, info.getExtent().getMaxLon(), 1e-9);
        }
    }

    @Test
    void arcgisWithoutExtentStillChecks() throws IOException {
        try (FakeServer server = new FakeServer(r -> r.endsWith("?f=json")
                ? FakeServer.json("{\"name\":\"Addresses\",\"geometryType\":\"esriGeometryPoint\",\"fields\":[{\"name\":\"ADDNUM\"}]}")
                : FakeServer.json("{\"error\":{\"code\":400,\"message\":\"Invalid query\"}}"))) {
            Info info = ServiceInspector.inspect(server.url("/FeatureServer/1"), Protocol.ARCGIS);
            assertEquals(Geometry.POINTS, info.getGeometry());
            assertNull(info.getExtent());
        }
    }

    @Test
    void webMercatorExtentFromTheDescriptionWithoutAskingTheServer() throws IOException {
        // Esri-hosted layers describe their extent in Web Mercator; asking the server to compute
        // one can take a minute and fail (Utah's statewide parcels). About Crawfordsville IN.
        try (FakeServer server = new FakeServer(r -> r.endsWith("?f=json")
                ? FakeServer.json("{\"name\":\"Buildings\",\"geometryType\":\"esriGeometryPolygon\",\"fields\":[],\"extent\":{"
                        + "\"xmin\":-9686000,\"ymin\":4869000,\"xmax\":-9674000,\"ymax\":4877000,"
                        + "\"spatialReference\":{\"wkid\":102100,\"latestWkid\":3857}}}")
                : FakeServer.json("{\"error\":{\"code\":400,\"message\":\"Unable to complete operation\"}}"))) {
            Info info = ServiceInspector.inspect(server.url("/FeatureServer/0"), Protocol.ARCGIS);
            assertEquals(-87.011, info.getExtent().getMinLon(), 0.001);
            assertEquals(40.076, info.getExtent().getMaxLat(), 0.001);
            assertTrue(server.requests.stream().noneMatch(r -> r.contains("returnExtentOnly")), server.requests.toString());
        }
    }

    @Test
    void aWholeServiceOffersItsLayers() throws IOException {
        try (FakeServer server = new FakeServer(r -> FakeServer.json(
                "{\"layers\":[{\"id\":0,\"name\":\"Parcels\"},{\"id\":3,\"name\":\"Address Points\"}]}"))) {
            String base = server.url("/rest/services/Land/FeatureServer");
            NotALayerException e = assertThrows(NotALayerException.class, () -> ServiceInspector.inspect(base + "/", Protocol.ARCGIS));
            assertEquals(Map.of(base + "/0", "0 Parcels", base + "/3", "3 Address Points"), e.getLayers());
            assertEquals(List.of(base + "/0", base + "/3"), List.copyOf(e.getLayers().keySet()), "in the service's order");
        }
    }

    @Test
    void aGroupLayerOffersItsSublayers() throws IOException {
        try (FakeServer server = new FakeServer(r -> FakeServer.json(
                "{\"id\":5,\"name\":\"Cadastral\",\"type\":\"Group Layer\",\"subLayers\":[{\"id\":6,\"name\":\"Tax Parcels\"}]}"))) {
            String root = server.url("/rest/services/Base/MapServer");
            NotALayerException e = assertThrows(NotALayerException.class, () -> ServiceInspector.inspect(root + "/5", Protocol.ARCGIS));
            assertEquals(Map.of(root + "/6", "6 Tax Parcels"), e.getLayers());
        }
    }

    @Test
    void arcgisErrorsCarryTheServersReason() throws IOException {
        try (FakeServer server = new FakeServer(r -> FakeServer.json("{\"error\":{\"code\":499,\"message\":\"Token Required\"}}"))) {
            IOException e = assertThrows(IOException.class, () -> ServiceInspector.inspect(server.url("/FeatureServer/0"), Protocol.ARCGIS));
            assertTrue(e.getMessage().contains("Token Required"), e.getMessage());
        }
    }

    @Test
    void anInvalidAddressSaysSoWithoutEchoingIt() {
        IOException e = assertThrows(IOException.class, () -> ServiceInspector.inspect("2026-10-04 14:13:59 INFO: x y", Protocol.ARCGIS));
        assertEquals("not a valid web address", e.getMessage());
    }

    @Test
    void ogcCollectionGivesTitleQueryablesGeometryAndExtent() throws IOException {
        try (FakeServer server = new FakeServer(r -> {
            if (r.equals("/ogc/collections/addresses")) {
                return FakeServer.json("{\"id\":\"addresses\",\"title\":\"County addresses\",\"links\":[],"
                        + "\"extent\":{\"spatial\":{\"bbox\":[[-87.1,39.9,-86.7,40.2]]}}}");
            }
            if (r.equals("/ogc/collections/addresses/queryables")) {
                return FakeServer.json("{\"properties\":{\"ADDNUM\":{},\"STREET\":{},\"geometry\":{}}}");
            }
            if (r.startsWith("/ogc/collections/addresses/items")) {
                return FakeServer.json("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":"
                        + "{\"ADDNUM\":\"10\",\"STREET\":\"Main St\",\"ZIP\":\"47933\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-86.9,40.0]}}]}");
            }
            return null;
        })) {
            Info info = ServiceInspector.inspect(server.url("/ogc/collections/addresses"), Protocol.OGC_FEATURES);
            assertEquals("County addresses", info.getTitle());
            assertEquals(Geometry.POINTS, info.getGeometry());
            assertEquals(List.of("ADDNUM", "STREET", "ZIP"), info.getFields());
            assertEquals(-87.1, info.getExtent().getMinLon(), 1e-9);
            assertEquals(40.2, info.getExtent().getMaxLat(), 1e-9);
        }
    }

    @Test
    void ogcCollectionDocumentIsAskedForAsPlainJson() throws IOException {
        // Like PDOK: a collection document requested as GeoJSON is a 404.
        try (FakeServer server = new FakeServer((r, accept) -> {
            if (r.equals("/ogc/collections/pand")) {
                return accept.startsWith("application/geo+json") ? new Object[] {404, "application/problem+json", "{\"status\":404}"}
                        : FakeServer.json("{\"id\":\"pand\",\"title\":\"Pand\",\"links\":[]}");
            }
            return null;
        })) {
            assertEquals("Pand", ServiceInspector.inspect(server.url("/ogc/collections/pand"), Protocol.OGC_FEATURES).getTitle());
        }
    }

    @Test
    void ogcCollectionListOffersItsCollections() throws IOException {
        try (FakeServer server = new FakeServer(r -> FakeServer.json(
                "{\"collections\":[{\"id\":\"parcels\",\"title\":\"Tax parcels\"},{\"id\":\"addresses\"}]}"))) {
            String list = server.url("/ogc/collections");
            NotALayerException e = assertThrows(NotALayerException.class, () -> ServiceInspector.inspect(list, Protocol.OGC_FEATURES));
            assertEquals(Map.of(list + "/parcels", "Tax parcels (parcels)", list + "/addresses", "addresses"), e.getLayers());
            // A landing page that embeds the list gets the /collections segment added.
            NotALayerException fromLanding = assertThrows(NotALayerException.class,
                    () -> ServiceInspector.inspect(server.url("/ogc/"), Protocol.OGC_FEATURES));
            assertTrue(fromLanding.getLayers().containsKey(list + "/parcels"), fromLanding.getLayers().toString());
        }
    }
}
