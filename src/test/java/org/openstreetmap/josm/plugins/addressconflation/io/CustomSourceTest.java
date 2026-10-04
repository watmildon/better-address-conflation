// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Kind;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseStatus;
import org.openstreetmap.josm.spi.preferences.Config;

/** The mapper's own sources: recognising URLs, guessing fields, storing and downloading them. */
class CustomSourceTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final Bounds COUNTY = new Bounds(39.9, -87.1, 40.2, -86.7);

    private static CustomSource parcels(Bounds extent) {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("pid", List.of("PIN"));
        return new CustomSource("Montgomery Co parcels", Protocol.ARCGIS,
                "https://gis.example.gov/arcgis/rest/services/Parcels/FeatureServer/0", Kind.PARCELS, f, extent);
    }

    @Test
    void urlShapeGivesTheProtocolAway() {
        assertEquals(Protocol.ARCGIS, CustomSource.detect("https://gis.example.gov/arcgis/rest/services/Parcels/FeatureServer/0"));
        assertEquals(Protocol.ARCGIS, CustomSource.detect("https://gis.example.gov/arcgis/rest/services/Base/MapServer/12/"));
        assertEquals(Protocol.ARCGIS, CustomSource.detect("https://gis.example.gov/rest/services/P/FeatureServer/3?token=abc"));
        assertEquals(Protocol.OGC_FEATURES, CustomSource.detect("https://maps.example.gov/ogc/collections/parcels"));
        assertNull(CustomSource.detect("https://gis.example.gov/arcgis/rest/services/Parcels/FeatureServer"));
        assertNull(CustomSource.detect("https://maps.example.gov/ogc/collections"));
        assertNull(CustomSource.detect(""));
    }

    @Test
    void pastedTextIsCleanedToTheLayer() {
        String layer = "https://services.arcgis.com/P3ePLMYs2RVChkJx/arcgis/rest/services/MSBFP2/FeatureServer/0";
        assertEquals(layer, CustomSource.cleanUrl("2026-10-04 14:13:59.228 INFO: Feature service query: " + layer
                + "/query?where=1%3D1&returnExtentOnly=true&outSR=4326&f=json"), "a JOSM log line");
        assertEquals(layer, CustomSource.cleanUrl("  " + layer + "?f=pjson "));
        assertEquals(layer, CustomSource.cleanUrl(layer));
        assertEquals("https://gis.example.gov/rest/services/Base/MapServer/3", CustomSource.cleanUrl(
                "see https://gis.example.gov/rest/services/Base/MapServer/3/query?where=1=1 for parcels"));
        assertEquals("https://m.example.gov/ogc/collections/parcels?apikey=abc", CustomSource.cleanUrl(
                "https://m.example.gov/ogc/collections/parcels/items?bbox=1,2,3,4&limit=10&apikey=abc&f=json"));
        assertEquals("https://m.example.gov/ogc/collections/parcels", CustomSource.cleanUrl("https://m.example.gov/ogc/collections/parcels/items/42"));
        assertEquals("not a url", CustomSource.cleanUrl("  not a url "));
        assertFalse(CustomSource.isWebAddress("not a url"));
        assertTrue(CustomSource.isWebAddress(layer));
    }

    @Test
    void whatTheLayerHoldsIsSuggestedFromItsNameAndGeometry() {
        String utah = "https://services1.arcgis.com/99lidPhWCzftIe9K/arcgis/rest/services/UtahStatewideParcels/FeatureServer/0";
        assertEquals(Kind.PARCELS, CustomSource.suggestKind("StateWideParcels", utah, ServiceInspector.Geometry.POLYGONS));
        assertEquals(Kind.PARCELS, CustomSource.suggestKind("Cadastral", "https://x/MapServer/9", ServiceInspector.Geometry.UNKNOWN));
        assertEquals(Kind.PARCELS, CustomSource.suggestKind("Tax Lots", "https://x/FeatureServer/0", ServiceInspector.Geometry.POLYGONS));
        assertEquals(Kind.BUILDINGS, CustomSource.suggestKind("MSBFP", "https://x/rest/services/Building_Footprints/FeatureServer/0",
                ServiceInspector.Geometry.POLYGONS));
        assertEquals(Kind.ADDRESSES, CustomSource.suggestKind("Site Addresses", "https://x/FeatureServer/2", ServiceInspector.Geometry.UNKNOWN));
        assertEquals(Kind.ADDRESSES, CustomSource.suggestKind("Parcel centroids", "https://x/FeatureServer/0", ServiceInspector.Geometry.POINTS),
                "points are addresses whatever the name");
        assertEquals(Kind.PARCELS, CustomSource.suggestKind("Layer 0", "https://x/FeatureServer/0", ServiceInspector.Geometry.POLYGONS),
                "unnamed polygons are most often parcels");
        assertNull(CustomSource.suggestKind(null, "https://x/FeatureServer/0", ServiceInspector.Geometry.UNKNOWN));
    }

    @Test
    void commonFieldNamesAreGuessed() {
        List<String> fields = Arrays.asList("OBJECTID", "ADD_NUMBER", "FullName", "Unit", "Zip_Code", "PIN");
        assertEquals("ADD_NUMBER", CustomSource.guessField("number", fields));
        assertEquals("FullName", CustomSource.guessField("street", fields));
        assertEquals("Unit", CustomSource.guessField("unit", fields));
        assertEquals("Zip_Code", CustomSource.guessField("postcode", fields));
        assertEquals("PIN", CustomSource.guessField("pid", fields));
        assertNull(CustomSource.guessField("city", fields));
        // NENA-style layers (New York's SAM points): the complete name, not the bare one.
        assertEquals("CompleteStreetName", CustomSource.guessField("street", List.of("StreetName", "CompleteStreetName")));
    }

    @Test
    void severalFieldsAreJoined() {
        assertEquals(List.of("PREFIX", "NAME", "SUFFIX"), CustomSource.parseFields("PREFIX + NAME+SUFFIX"));
        assertEquals(List.of("A", "B"), CustomSource.parseFields(" A, B "));
        assertTrue(CustomSource.parseFields("  ").isEmpty());
        assertEquals("PREFIX + NAME", CustomSource.formatFields(List.of("PREFIX", "NAME")));
    }

    @Test
    void sourcesSurviveARestart() {
        CustomSource.save(List.of(parcels(COUNTY)));
        List<CustomSource> back = CustomSource.load();
        assertEquals(1, back.size());
        CustomSource s = back.get(0);
        assertEquals("Montgomery Co parcels", s.getName());
        assertEquals(Protocol.ARCGIS, s.getProtocol());
        assertEquals(Kind.PARCELS, s.getKind());
        assertEquals(Map.of("pid", List.of("PIN")), s.getFields());
        assertEquals(COUNTY.getMinLat(), s.getExtent().getMinLat(), 1e-9);
        assertEquals(COUNTY.getMaxLon(), s.getExtent().getMaxLon(), 1e-9);
        CustomSource.save(Collections.emptyList());
    }

    @Test
    void anUnreadableEntryIsSkipped() {
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("name", "broken");
        broken.put("protocol", "WFS");
        Config.getPref().putListOfMaps(CustomSource.PREF, Arrays.asList(broken, parcels(null).toMap()));
        assertEquals(1, CustomSource.load().size());
        CustomSource.save(Collections.emptyList());
    }

    @Test
    void fieldsForAnotherKindAreDropped() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("pid", List.of("PIN"));
        f.put("number", List.of("ADDNUM"));
        CustomSource s = new CustomSource("x", Protocol.ARCGIS, "u", Kind.PARCELS, f, null);
        assertEquals(Map.of("pid", List.of("PIN")), s.getFields());
    }

    @Test
    void offeredOnlyWhereItCovers() {
        assertTrue(parcels(COUNTY).covers(new Bounds(40.0, -86.95, 40.05, -86.9)));
        assertFalse(parcels(COUNTY).covers(new Bounds(33.5, -112.2, 33.6, -112.1)));
        assertTrue(parcels(null).covers(new Bounds(33.5, -112.2, 33.6, -112.1)), "unknown coverage is offered everywhere");
    }

    @Test
    void downloadsWithAUserProvidedLicence() {
        FeatureSource src = parcels(COUNTY).toFeatureSource();
        assertEquals(Protocol.ARCGIS, src.getProtocol());
        assertEquals(LicenseStatus.USER_PROVIDED, src.getLicense().getStatus());
        assertNull(src.getWhere(), "no filter on the mapper's sources");

        CustomSource ogc = new CustomSource("o", Protocol.OGC_FEATURES, "https://m/collections/p", Kind.PARCELS, Map.of(), null);
        assertEquals(Protocol.OGC_FEATURES, ogc.toFeatureSource().getProtocol());
        assertNull(ogc.toFeatureSource().getLicense().getLink());
    }
}
