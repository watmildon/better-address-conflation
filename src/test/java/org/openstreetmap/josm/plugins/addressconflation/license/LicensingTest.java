// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.io.EsriFeatureSource;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesSourceReader;

/** Declared licences as OpenAddresses writes them, and the clearances that override them. */
class LicensingTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static JsonValue json(String s) {
        try (JsonReader r = Json.createReader(new StringReader("{\"v\":" + s + "}"))) {
            return r.readObject().get("v");
        }
    }

    private static LicenseStatus declared(String s) {
        return DeclaredLicenses.classify(s == null ? null : json(s)).getStatus();
    }

    @Test
    void declaredLicencesFromRealDefinitions() {
        assertEquals(LicenseStatus.UNKNOWN, declared(null));
        assertEquals(LicenseStatus.UNKNOWN, declared("\"Unknown\""));
        assertEquals(LicenseStatus.COMPATIBLE, declared("{\"text\":\"Public Domain\",\"attribution\":false,\"share-alike\":false}"));
        assertEquals(LicenseStatus.COMPATIBLE, declared("{\"text\":\"CC0 1.0\",\"url\":\"https://creativecommons.org/publicdomain/zero/1.0/\"}"));
        assertEquals(LicenseStatus.COMPATIBLE, declared("{\"text\":\"PDDL\",\"url\":\"https://public-gis-missioncity.opendata.arcgis.com/pages/terms-of-use\"}"));
        assertEquals(LicenseStatus.COMPATIBLE, declared("{\"text\":\"ODbL 1.0\",\"url\":\"https://opendatacommons.org/licenses/odbl/1-0/\",\"attribution\":true,\"share-alike\":true}"));
        assertEquals(LicenseStatus.COMPATIBLE, declared("{\"text\":\"RLIS Open Database End User License\",\"attribution\":true,\"share-alike\":true}"));
        assertEquals(LicenseStatus.NEEDS_WAIVER, declared("{\"text\":\"CC BY 4.0\",\"url\":\"https://creativecommons.org/licenses/by/4.0\",\"attribution\":true}"));
        assertEquals(LicenseStatus.NEEDS_WAIVER, declared("\"CC BY 4.0\""));
        assertEquals(LicenseStatus.NEEDS_WAIVER, declared("{\"attribution\":true}"));
        assertEquals(LicenseStatus.NOT_COMPATIBLE, declared("{\"text\":\"CC BY-SA 4.0\",\"url\":\"https://creativecommons.org/licenses/by-sa/4.0\",\"share-alike\":true}"));
        assertEquals(LicenseStatus.NOT_COMPATIBLE, declared("{\"text\":\"CC BY-NC-SA 4.0\"}"));
        assertEquals(LicenseStatus.NOT_COMPATIBLE, declared("{\"text\":\"Not for commercial use or resale. The User shall save Bucks County harmless\"}"));
        assertEquals(LicenseStatus.CHECK_TERMS, declared("{\"text\":\"Indemnification\",\"url\":\"https://www.leegov.com/gis/data/gis-data\"}"));
        assertEquals(LicenseStatus.CHECK_TERMS, declared("{\"text\":\"PD + Indemnification\"}"));
        assertEquals(LicenseStatus.CHECK_TERMS, declared("{\"url\":\"https://www.eugene-or.gov/1352/Maps-and-GIS-Disclaimer\"}"));
        assertEquals(LicenseStatus.CHECK_TERMS, declared("\"https://www.co.mason.wa.us/gis/disclaimer.php\""));
    }

    @Test
    void californiaAndMarylandLawOverrideDeclaredLicences() {
        JsonValue sa = json("{\"text\":\"CC BY-SA 3.0\",\"url\":\"https://creativecommons.org/licenses/by-sa/3.0/\",\"share-alike\":true}");
        LicenseAssessment sonoma = Licensing.assess("us/ca/sonoma", "parcels", "https://example.org/FeatureServer/0", sa);
        assertEquals(LicenseStatus.COMPATIBLE, sonoma.getStatus());
        assertTrue(sonoma.getBasis().contains("California"), sonoma.getBasis());
        assertEquals("CC BY-SA 3.0", sonoma.getDeclared(), "the declared licence is still shown");
        assertEquals(LicenseStatus.COMPATIBLE,
                Licensing.assess("us/md/carroll", "parcels", "https://example.org/x", json("{\"text\":\"Indemnification\"}")).getStatus());
    }

    @Test
    void wisconsinOnlyFillsInWhenNothingIsDeclared() {
        assertEquals(LicenseStatus.COMPATIBLE, Licensing.assess("us/wi/vilas", "parcels", "https://example.org/x", null).getStatus());
        assertEquals(LicenseStatus.NEEDS_WAIVER,
                Licensing.assess("us/wi/iron", "parcels", "https://example.org/x", json("{\"text\":\"CC BY 4.0\"}")).getStatus());
        assertEquals(LicenseStatus.UNKNOWN, Licensing.assess("us/wi/vilas", "addresses", "https://example.org/x", null).getStatus());
    }

    @Test
    void namedPermissionsNeedTheSameServerAndLayer() {
        String king = "https://gismaps.kingcounty.gov/arcgis/rest/services/Property/KingCo_Parcels/MapServer/0";
        LicenseAssessment ok = Licensing.assess("us/wa/king", "parcels", king, json("{\"url\":\"https://www5.kingcounty.gov/sdc/addl_doc/KCGISCenterTermsAndConditions.pdf\"}"));
        assertEquals(LicenseStatus.COMPATIBLE, ok.getStatus());
        assertTrue(ok.getLink().contains("Contributors"), ok.getLink());
        // the same source read from somewhere else keeps its declared status
        assertEquals(LicenseStatus.CHECK_TERMS, Licensing.assess("us/wa/king", "parcels", "https://elsewhere.example.org/FeatureServer/0",
                json("{\"url\":\"https://www5.kingcounty.gov/sdc/addl_doc/KCGISCenterTermsAndConditions.pdf\"}")).getStatus());
        // Connecticut's permission covers its address points, not its parcels
        assertEquals(LicenseStatus.UNKNOWN,
                Licensing.assess("us/ct/statewide", "parcels", "https://services3.arcgis.com/3FL1kr7L4LvwA2Kb/arcgis/rest/services/x/FeatureServer/0", null).getStatus());
        // ArcGIS Online organizations are matched case-insensitively
        assertEquals(LicenseStatus.COMPATIBLE, Licensing.assess("us/wa/clark", "parcels",
                "https://services2.arcgis.com/ylxwjFBdCPBzP16d/arcgis/rest/services/TaxlotsforPublicUse/FeatureServer/0", null).getStatus());
    }

    @Test
    void tooltipsPointToPermissionGuideWhenNeeded() {
        assertTrue(DeclaredLicenses.classify(json("{\"text\":\"CC BY 4.0\"}")).toolTipHtml().contains(LicenseAssessment.GETTING_PERMISSION));
        assertTrue(DeclaredLicenses.classify(null).toolTipHtml().contains(LicenseAssessment.GETTING_PERMISSION));
        assertTrue(!Licensing.NAD.toolTipHtml().contains(LicenseAssessment.GETTING_PERMISSION), "not for compatible sources");
    }

    @Test
    void builtInSourcesAndReaderCarryTheirLicence() throws Exception {
        assertEquals(LicenseStatus.COMPATIBLE, EsriFeatureSource.nad().getLicense().getStatus());
        assertEquals(LicenseStatus.COMPATIBLE, EsriFeatureSource.microsoftBuildings().getLicense().getStatus());
        String def = "{\"schema\":2,\"coverage\":{\"county\":\"Mono\"},\"layers\":{\"parcels\":[{\"name\":\"county\",\"protocol\":\"ESRI\","
                + "\"data\":\"https://example.org/FeatureServer/0\",\"license\":{\"text\":\"CC BY-SA 4.0\",\"share-alike\":true},"
                + "\"conform\":{\"format\":\"geojson\",\"pid\":\"APN\"}}]}}";
        EsriFeatureSource s = OpenAddressesSourceReader.parse(def, false).get(0);
        assertEquals("CC BY-SA 4.0", s.getDeclaredLicense().asJsonObject().getString("text"));
        assertTrue(s.withServiceFields(List.of("APN")).getDeclaredLicense() != null, "kept through field matching");
    }
}
