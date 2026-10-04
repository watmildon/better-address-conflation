// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** What real ArcGIS servers do that the happy path does not expect. */
class EsriServiceQuirksTest {

    @Test
    void conformFieldsFollowTheServiceSpelling() {
        // Indiana's statewide parcels: the OpenAddresses source says STATE_PARCEL_ID, the
        // hosted service has state_parcel_id and rejects the upper-case name with HTTP 500.
        Map<String, List<String>> conform = new LinkedHashMap<>();
        conform.put("pid", List.of("STATE_PARCEL_ID"));
        conform.put("id", List.of("GONE_FIELD"));
        FeatureSource src = new FeatureSource("in", "https://example.org/FeatureServer/0", FeatureSource.Kind.PARCELS,
                conform, null, false);
        FeatureSource fixed = src.withServiceFields(List.of("objectid", "state_parcel_id", "parcel_id"));
        assertEquals(List.of("state_parcel_id"), fixed.getConform().get("pid"));
        assertFalse(fixed.getConform().containsKey("id"), "fields the service lacks are dropped");
        assertEquals("state_parcel_id", fixed.outFields());
    }

    @Test
    void serverMessageFromArcGisEnterpriseHtml() {
        String html = "<html lang=\"en\">\n\n<head>\n<title>\nError: Field name 'STATE_PARCEL_ID' does not exist. Did you mean 'state_parcel_id'?</title>\n"
                + "<link href=\"/server/rest/static/main.css\" rel=\"stylesheet\" type=\"text/css\"/>\n</head><body>...</body></html>";
        assertEquals("Field name 'STATE_PARCEL_ID' does not exist. Did you mean 'state_parcel_id'?", EsriFeatureClient.serverMessage(html));
    }

    @Test
    void serverMessageFromJsonAndLimits() {
        assertEquals("Invalid query", EsriFeatureClient.serverMessage("{\"error\":{\"code\":400,\"message\":\"Invalid query\",\"details\":[]}}"));
        assertNull(EsriFeatureClient.serverMessage("<html><body>no title</body></html>"));
        assertNull(EsriFeatureClient.serverMessage(""));
        String longMsg = EsriFeatureClient.serverMessage("{\"error\":{\"message\":\"" + "x".repeat(1000) + "\"}}");
        assertTrue(longMsg.length() < 200, "trimmed: " + longMsg.length());
    }

    @Test
    void qualifiedFieldNamesMatchOnTheirLastPart() {
        Map<String, List<String>> conform = new LinkedHashMap<>();
        conform.put("pid", List.of("SDE_GISA.Parcel_Boundary.APN"));
        FeatureSource carsonCity = new FeatureSource("nv", "https://example.org/FeatureServer/0", FeatureSource.Kind.PARCELS,
                conform, null, false);
        assertEquals("APN", carsonCity.withServiceFields(List.of("OBJECTID", "APN", "APN_NUM")).outFields());

        conform.put("pid", List.of("Huntington.DBO.Parcels.APN"));
        FeatureSource huntington = new FeatureSource("ca", "https://example.org/FeatureServer/0", FeatureSource.Kind.PARCELS,
                conform, null, false);
        assertEquals("DATA.Parcels.APN", huntington.withServiceFields(List.of("DATA.Parcels.OBJECTID", "DATA.Parcels.APN")).outFields());
        // two candidates: no guessing
        assertEquals("*", huntington.withServiceFields(List.of("DATA.Parcels.APN", "DATA.Other.APN")).outFields());
    }

    @Test
    void errorTextFromEveryArcGisShape() {
        assertEquals("The requested layer (layerId: 0) was not found.", EsriFeatureClient.errorText(json(
                "{\"error\":{\"code\":400,\"message\":\"\",\"details\":[\"The requested layer (layerId: 0) was not found.\"]}}")));
        assertEquals("Could not access any server machines.", EsriFeatureClient.errorText(json(
                "{\"status\":\"error\",\"messages\":[\"Could not access any server machines.\"]}")));
        assertNull(EsriFeatureClient.errorText(json("{\"features\":[]}")));
    }

    @Test
    void pointLayersAreNotParcels() {
        // us/wa/clark and us/pa/susquehanna list address-point layers as their parcels.
        FeatureSource parcels = new FeatureSource("wa", "https://example.org/FeatureServer/0", FeatureSource.Kind.PARCELS,
                Map.of(), null, false);
        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> EsriFeatureClient.checkGeometry(parcels, "esriGeometryPoint"));
        assertEquals("this layer holds points, not parcel outlines", e.getMessage());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> EsriFeatureClient.checkGeometry(parcels, "esriGeometryPolygon"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> EsriFeatureClient.checkGeometry(FeatureSource.nad(), "esriGeometryPoint"));
    }

    @Test
    void networkFailuresInPlainWords() {
        assertEquals("the server's security certificate could not be verified",
                EsriFeatureClient.describe(new java.io.IOException(new javax.net.ssl.SSLHandshakeException("PKIX path building failed"))));
        assertEquals("server gis.example.org not found", EsriFeatureClient.describe(new java.net.UnknownHostException("gis.example.org")));
        assertEquals("the server did not answer in time", EsriFeatureClient.describe(new java.net.SocketTimeoutException("Read timed out")));
    }

    private static jakarta.json.JsonObject json(String s) {
        try (jakarta.json.JsonReader r = jakarta.json.Json.createReader(new java.io.StringReader(s))) {
            return r.readObject();
        }
    }
}
