// License: GPL. For details, see LICENSE file.
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
        EsriFeatureSource src = new EsriFeatureSource("in", "https://example.org/FeatureServer/0", EsriFeatureSource.Kind.PARCELS,
                conform, null, false);
        EsriFeatureSource fixed = src.withServiceFields(List.of("objectid", "state_parcel_id", "parcel_id"));
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
}
