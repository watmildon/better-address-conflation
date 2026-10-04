// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseAssessment;
import org.openstreetmap.josm.plugins.addressconflation.license.Licensing;
import org.openstreetmap.josm.tools.Logging;

/**
 * A feature service layer plus the OpenAddresses-style "conform" that maps its fields onto
 * OA properties. Covers the National Address Database preset, any layer of an OpenAddresses
 * source definition, and the mapper's own sources. The {@link Protocol} says how it is
 * queried: an ArcGIS FeatureServer/MapServer layer or an OGC API - Features collection, both
 * of which return GeoJSON that shares the conversion.
 */
public final class FeatureSource {

    /** Which OpenAddresses layer the service feeds. */
    public enum Kind {
        ADDRESSES, PARCELS, BUILDINGS
    }

    /** How the layer is queried. */
    public enum Protocol {
        /** An ArcGIS REST FeatureServer or MapServer layer, {@code .../FeatureServer/0}. */
        ARCGIS,
        /** An OGC API - Features collection, {@code .../collections/parcels}. */
        OGC_FEATURES
    }

    public static final String NAD_URL =
            "https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0";

    /** Microsoft's US Building Footprints, hosted by Esri. */
    public static final String MS_BUILDINGS_URL =
            "https://services.arcgis.com/P3ePLMYs2RVChkJx/arcgis/rest/services/MSBFP2/FeatureServer/0";

    private static final GeometryFactory GF = new GeometryFactory();

    private final String name;
    private final String url;
    private final Protocol protocol;
    private final Kind kind;
    private final Map<String, List<String>> conform;
    private final String where;
    private final boolean expandStreets;
    /** The {@code license} value of the source definition's layer entry, or null. */
    private final JsonValue declaredLicense;
    /** The licence verdict for this source, or null when nobody assessed it. */
    private final LicenseAssessment license;

    public FeatureSource(String name, String url, Kind kind, Map<String, List<String>> conform, String where, boolean expandStreets) {
        this(name, url, Protocol.ARCGIS, kind, conform, where, expandStreets, null, null);
    }

    private FeatureSource(String name, String url, Protocol protocol, Kind kind, Map<String, List<String>> conform, String where,
            boolean expandStreets, JsonValue declaredLicense, LicenseAssessment license) {
        this.name = name;
        this.url = url;
        this.protocol = protocol;
        this.kind = kind;
        this.conform = Collections.unmodifiableMap(new LinkedHashMap<>(conform));
        this.where = where;
        this.expandStreets = expandStreets;
        this.declaredLicense = declaredLicense;
        this.license = license;
    }

    /** This source with the licence its definition declares. */
    public FeatureSource withDeclaredLicense(JsonValue declared) {
        return new FeatureSource(name, url, protocol, kind, conform, where, expandStreets, declared, license);
    }

    /** This source with a licence verdict attached. */
    public FeatureSource withLicense(LicenseAssessment assessment) {
        return new FeatureSource(name, url, protocol, kind, conform, where, expandStreets, declaredLicense, assessment);
    }

    /** This source under another display name (the layer name after download). */
    public FeatureSource withName(String newName) {
        return new FeatureSource(newName, url, protocol, kind, conform, where, expandStreets, declaredLicense, license);
    }

    /** This source queried over another protocol. */
    public FeatureSource withProtocol(Protocol p) {
        return new FeatureSource(name, url, p, kind, conform, where, expandStreets, declaredLicense, license);
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public JsonValue getDeclaredLicense() {
        return declaredLicense;
    }

    /** The licence verdict, or null when the source was not assessed. */
    public LicenseAssessment getLicense() {
        return license;
    }

    /** The National Address Database as processed by Esri (streets already expanded). */
    public static FeatureSource nad() {
        Map<String, List<String>> c = new LinkedHashMap<>();
        c.put("number", Collections.singletonList("addr_housenumber"));
        c.put("street", Collections.singletonList("addr_street"));
        c.put("unit", Collections.singletonList("addr_unit"));
        c.put("city", Collections.singletonList("addr_city"));
        c.put("region", Collections.singletonList("addr_state"));
        c.put("postcode", Collections.singletonList("addr_postcode"));
        return new FeatureSource("National Address Database", NAD_URL, Kind.ADDRESSES, c, null, false).withLicense(Licensing.NAD);
    }

    /** Microsoft building footprints, for use as placement hints. */
    public static FeatureSource microsoftBuildings() {
        Map<String, List<String>> c = new LinkedHashMap<>();
        c.put("id", Collections.singletonList("OBJECTID"));
        return new FeatureSource("Microsoft building footprints", MS_BUILDINGS_URL, Kind.BUILDINGS, c, null, false)
                .withLicense(Licensing.MICROSOFT_BUILDINGS);
    }

    /**
     * This source with its conform field names matched, ignoring case, to the fields the
     * service actually has. Hosted ArcGIS Enterprise layers are case-sensitive, and
     * OpenAddresses definitions do not always match the service's spelling (Indiana's
     * statewide parcels list STATE_PARCEL_ID; the service has state_parcel_id). Fields the
     * service does not have are dropped so the query does not fail outright.
     */
    public FeatureSource withServiceFields(Collection<String> serviceFields) {
        Map<String, String> byLower = new HashMap<>();
        for (String f : serviceFields) {
            byLower.putIfAbsent(f.toLowerCase(Locale.ROOT), f);
        }
        Map<String, List<String>> fixed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conform.entrySet()) {
            List<String> fields = new ArrayList<>();
            for (String f : e.getValue()) {
                String actual = byLower.get(f.toLowerCase(Locale.ROOT));
                if (actual == null) {
                    // Qualified names drift with the database behind the service:
                    // SDE_GISA.Parcel_Boundary.APN in the definition, APN in the service, or
                    // the other way round. Match on the last part when that is unambiguous.
                    actual = uniqueBySuffix(f, serviceFields);
                }
                if (actual != null) {
                    fields.add(actual);
                } else {
                    Logging.info(name + ": field " + f + " is not in the service, ignored");
                }
            }
            if (!fields.isEmpty()) {
                fixed.put(e.getKey(), fields);
            }
        }
        return new FeatureSource(name, url, protocol, kind, fixed, where, expandStreets, declaredLicense, license);
    }

    private static String uniqueBySuffix(String wanted, Collection<String> serviceFields) {
        String tail = lastPart(wanted);
        String found = null;
        for (String f : serviceFields) {
            if (lastPart(f).equalsIgnoreCase(tail)) {
                if (found != null) {
                    return null;
                }
                found = f;
            }
        }
        return found;
    }

    private static String lastPart(String field) {
        int dot = field.lastIndexOf('.');
        return dot < 0 ? field : field.substring(dot + 1);
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public Kind getKind() {
        return kind;
    }

    public Map<String, List<String>> getConform() {
        return conform;
    }

    public String getWhere() {
        return where;
    }

    public boolean isExpandStreets() {
        return expandStreets;
    }

    /** Fields to request from the service; "*" when the conform is empty. */
    public String outFields() {
        List<String> fields = new ArrayList<>();
        for (List<String> l : conform.values()) {
            for (String f : l) {
                if (!fields.contains(f)) {
                    fields.add(f);
                }
            }
        }
        return fields.isEmpty() ? "*" : String.join(",", fields);
    }

    /**
     * Turn a raw ESRI GeoJSON feature into an OpenAddresses-shaped one, or null
     * when it should be dropped (no housenumber, no geometry). Polygon sources
     * feeding the addresses layer are collapsed to their centroid, which is how
     * OpenAddresses handles parcel layers used as address sources.
     */
    public JsonObject toOaFeature(JsonObject feature) {
        JsonValue geomValue = feature.get("geometry");
        if (geomValue == null || geomValue.getValueType() != JsonValue.ValueType.OBJECT) {
            return null;
        }
        JsonObject geometry = geomValue.asJsonObject();
        JsonValue propsValue = feature.get("properties");
        JsonObject props = propsValue != null && propsValue.getValueType() == JsonValue.ValueType.OBJECT
                ? propsValue.asJsonObject() : JsonValue.EMPTY_JSON_OBJECT;
        JsonObjectBuilder out = JsonSupport.JSON.createObjectBuilder();
        switch (kind) {
        case ADDRESSES:
            String number = value(props, "number");
            if (number.isEmpty()) {
                return null;
            }
            for (String k : new String[] {"id", "number", "street", "unit", "city", "district", "region", "postcode"}) {
                out.add(k, value(props, k));
            }
            String type = geometry.getString("type", "");
            if ("Polygon".equals(type) || "MultiPolygon".equals(type)) {
                geometry = centroid(geometry);
                if (geometry == null) {
                    return null;
                }
            }
            break;
        case PARCELS:
            out.add("id", value(props, "id"));
            out.add("pid", value(props, "pid"));
            break;
        default:
            out.add("id", value(props, "id"));
            out.add("height", value(props, "height"));
            break;
        }
        return JsonSupport.JSON.createObjectBuilder().add("type", "Feature").add("properties", out).add("geometry", geometry).build();
    }

    private String value(JsonObject props, String oaKey) {
        List<String> fields = conform.get(oaKey);
        if (fields == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            JsonValue v = props.get(f);
            String s = null;
            if (v instanceof JsonString) {
                s = ((JsonString) v).getString();
            } else if (v != null && v.getValueType() == JsonValue.ValueType.NUMBER) {
                s = v.toString();
            }
            if (s != null && !s.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }

    /** Point geometry at the area centroid of the largest ring. */
    static JsonObject centroid(JsonObject geometry) {
        JsonArray coords = geometry.getJsonArray("coordinates");
        if (coords == null || coords.isEmpty()) {
            return null;
        }
        List<JsonArray> polys = new ArrayList<>();
        if ("Polygon".equals(geometry.getString("type", ""))) {
            polys.add(coords);
        } else {
            for (JsonValue v : coords) {
                polys.add(v.asJsonArray());
            }
        }
        Point best = null;
        double bestArea = -1;
        for (JsonArray poly : polys) {
            if (poly.isEmpty()) {
                continue;
            }
            JsonArray ring = poly.getJsonArray(0);
            if (ring.size() < 4) {
                continue;
            }
            // Work in degrees scaled to be roughly isotropic; fine for a centroid.
            double lat0 = ring.getJsonArray(0).getJsonNumber(1).doubleValue();
            double kx = Math.cos(Math.toRadians(lat0));
            Coordinate[] cs = new Coordinate[ring.size()];
            for (int i = 0; i < ring.size(); i++) {
                JsonArray p = ring.getJsonArray(i);
                cs[i] = new Coordinate(p.getJsonNumber(0).doubleValue() * kx, p.getJsonNumber(1).doubleValue());
            }
            if (!cs[0].equals2D(cs[cs.length - 1])) {
                Coordinate[] closed = new Coordinate[cs.length + 1];
                System.arraycopy(cs, 0, closed, 0, cs.length);
                closed[cs.length] = cs[0];
                cs = closed;
            }
            try {
                LinearRing lr = GF.createLinearRing(cs);
                Polygon pg = GF.createPolygon(lr);
                double a = pg.getArea();
                if (a > bestArea) {
                    bestArea = a;
                    Point c = pg.getCentroid();
                    best = GF.createPoint(new Coordinate(c.getX() / kx, c.getY()));
                }
            } catch (IllegalArgumentException e) {
                // degenerate ring
            }
        }
        if (best == null) {
            return null;
        }
        JsonArrayBuilder c = JsonSupport.JSON.createArrayBuilder().add(best.getX()).add(best.getY());
        return JsonSupport.JSON.createObjectBuilder().add("type", "Point").add("coordinates", c).build();
    }

    @Override
    public String toString() {
        return name + " [" + kind.name().toLowerCase() + "]";
    }
}
