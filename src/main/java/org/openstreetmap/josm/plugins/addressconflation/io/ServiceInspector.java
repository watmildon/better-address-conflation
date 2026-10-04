// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.addressconflation.io.EsriFeatureSource.Protocol;
import org.openstreetmap.josm.tools.Logging;

/**
 * Reads what a service says about itself, so the source editor can offer its fields and know
 * its geometry and coverage: the ArcGIS layer description, or the OGC API collection document
 * and queryables.
 */
public final class ServiceInspector {

    /** What kind of features the layer holds, as far as the service says. */
    public enum Geometry {
        POINTS, POLYGONS, OTHER, UNKNOWN
    }

    /** The service's own account of the layer. */
    public static final class Info {
        private final String title;
        private final Geometry geometry;
        private final List<String> fields;
        private final Bounds extent;

        Info(String title, Geometry geometry, List<String> fields, Bounds extent) {
            this.title = title;
            this.geometry = geometry;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
            this.extent = extent;
        }

        /** The layer's name, or null. */
        public String getTitle() {
            return title;
        }

        public Geometry getGeometry() {
            return geometry;
        }

        /** Attribute names, in the service's order and spelling. */
        public List<String> getFields() {
            return fields;
        }

        /** Coverage in WGS84, or null when the service does not say. */
        public Bounds getExtent() {
            return extent;
        }
    }

    /**
     * The URL names a whole ArcGIS service, a group layer, or a list of OGC collections rather
     * than one layer. Carries the layers it offers, so the mapper can pick one.
     */
    public static final class NotALayerException extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient Map<String, String> layers;

        NotALayerException(String message, Map<String, String> layers) {
            super(message);
            this.layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
        }

        /** Layer URL to a label such as "3 Address Points", in the service's order. */
        public Map<String, String> getLayers() {
            return layers;
        }
    }

    private ServiceInspector() {
    }

    /** Ask the service about the layer; fails with a reason the mapper can act on. */
    public static Info inspect(String url, Protocol protocol) throws IOException {
        try {
            return protocol == Protocol.OGC_FEATURES ? ogc(url.trim()) : arcgis(url.trim());
        } catch (NotALayerException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException(EsriFeatureClient.describe(e), e);
        }
    }

    // ---- ArcGIS ------------------------------------------------------------------------------

    private static Info arcgis(String url) throws IOException {
        String base = url.replaceFirst("\\?.*$", "").replaceAll("/+$", "");
        JsonObject info = EsriFeatureClient.get(base + "?f=json");
        String error = EsriFeatureClient.errorText(info);
        if (error != null) {
            throw new IOException(tr("service error: {0}", error));
        }
        if (!info.containsKey("fields") && info.containsKey("layers")) {
            throw notALayer(tr("this is a whole service, not one layer"), info.get("layers"), serviceRoot(base));
        }
        if (!info.containsKey("fields") && info.containsKey("subLayers") && !info.getJsonArray("subLayers").isEmpty()) {
            throw notALayer(tr("this is a group of layers, not one layer"), info.get("subLayers"), serviceRoot(base));
        }
        List<String> fields = new ArrayList<>();
        JsonValue fv = info.get("fields");
        if (fv != null && fv.getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue f : fv.asJsonArray()) {
                if (f.getValueType() == JsonValue.ValueType.OBJECT && !f.asJsonObject().getString("name", "").isEmpty()) {
                    fields.add(f.asJsonObject().getString("name"));
                }
            }
        }
        String type = info.getString("geometryType", "");
        Geometry geometry = type.isEmpty() ? Geometry.UNKNOWN
                : type.equals("esriGeometryPoint") || type.equals("esriGeometryMultipoint") ? Geometry.POINTS
                : type.equals("esriGeometryPolygon") ? Geometry.POLYGONS : Geometry.OTHER;
        // The description's own extent first: free, and asking the server to compute one can take
        // a minute and then fail on a big layer (Utah's 1.6 million statewide parcels).
        Bounds extent = describedExtent(info);
        if (extent == null) {
            extent = arcgisExtent(base);
        }
        return new Info(blankToNull(info.getString("name", null)), geometry, fields, extent);
    }

    /** The layers a service root or group layer lists, as layer URL to "id name". */
    private static NotALayerException notALayer(String message, JsonValue list, String serviceRoot) {
        Map<String, String> layers = new LinkedHashMap<>();
        if (list != null && list.getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue v : list.asJsonArray()) {
                if (v.getValueType() == JsonValue.ValueType.OBJECT && v.asJsonObject().containsKey("id")) {
                    JsonObject l = v.asJsonObject();
                    String id = l.get("id").toString();
                    layers.put(serviceRoot + "/" + id, (id + " " + l.getString("name", "")).trim());
                }
            }
        }
        return new NotALayerException(layers.isEmpty() ? message + "; " + tr("it lists no layers") : message, layers);
    }

    /** {@code .../FeatureServer} for a service or one of its layers. */
    private static String serviceRoot(String base) {
        return base.replaceFirst("(?i)(/(FeatureServer|MapServer))/\\d+$", "$1");
    }

    /** How long to wait for the server to compute an extent; coverage is a nicety. */
    private static final int EXTENT_TIMEOUT_MS = 15_000;

    /**
     * The layer's extent, reprojected by the server to WGS84, for when the description's extent
     * is in a projection we cannot convert (state plane and the like).
     */
    private static Bounds arcgisExtent(String base) {
        try {
            JsonObject r = EsriFeatureClient.get(base + "/query?where=1%3D1&returnExtentOnly=true&outSR=4326&f=json",
                    "application/json", EXTENT_TIMEOUT_MS);
            JsonValue ev = r.get("extent");
            if (ev == null || ev.getValueType() != JsonValue.ValueType.OBJECT) {
                return null;
            }
            JsonObject e = ev.asJsonObject();
            return bounds(num(e, "ymin"), num(e, "xmin"), num(e, "ymax"), num(e, "xmax"));
        } catch (IOException | RuntimeException e) {
            Logging.info("No extent for " + base + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The extent in the layer description, when it is in a projection that converts without a
     * projection library: WGS84 or NAD83 degrees, or Web Mercator (most hosted layers).
     */
    static Bounds describedExtent(JsonObject info) {
        try {
            JsonObject e = info.getJsonObject("extent");
            JsonObject sr = e.getJsonObject("spatialReference");
            int wkid = sr.containsKey("latestWkid") ? sr.getInt("latestWkid") : sr.getInt("wkid");
            double xmin = num(e, "xmin");
            double ymin = num(e, "ymin");
            double xmax = num(e, "xmax");
            double ymax = num(e, "ymax");
            if (wkid == 4326 || wkid == 4269) {
                return bounds(ymin, xmin, ymax, xmax);
            }
            if (wkid == 3857 || wkid == 102100) {
                return bounds(mercatorLat(ymin), mercatorLon(xmin), mercatorLat(ymax), mercatorLon(xmax));
            }
        } catch (RuntimeException ex) {
            Logging.trace(ex);
        }
        return null;
    }

    private static final double EARTH_RADIUS = 6378137.0;

    private static double mercatorLon(double x) {
        return Math.toDegrees(x / EARTH_RADIUS);
    }

    private static double mercatorLat(double y) {
        return Math.toDegrees(2 * Math.atan(Math.exp(y / EARTH_RADIUS)) - Math.PI / 2);
    }

    // ---- OGC API - Features --------------------------------------------------------------------

    private static Info ogc(String url) throws IOException {
        JsonObject collection = EsriFeatureClient.get(url, "application/json");
        if (collection.containsKey("collections") && !collection.containsKey("extent")) {
            throw collectionList(url, collection);
        }
        String title = blankToNull(collection.getString("title", null));
        if (title == null) {
            title = blankToNull(collection.getString("id", null));
        }
        Set<String> fields = new LinkedHashSet<>(queryables(url, collection));
        Geometry geometry = Geometry.UNKNOWN;
        // One feature tells the geometry type, and the fields when queryables are not offered.
        try {
            JsonObject sample = EsriFeatureClient.get(sampleUrl(url));
            JsonValue feats = sample.get("features");
            if (feats != null && feats.getValueType() == JsonValue.ValueType.ARRAY && !feats.asJsonArray().isEmpty()) {
                JsonObject f = feats.asJsonArray().getJsonObject(0);
                JsonValue props = f.get("properties");
                if (props != null && props.getValueType() == JsonValue.ValueType.OBJECT) {
                    fields.addAll(props.asJsonObject().keySet());
                }
                JsonValue g = f.get("geometry");
                String type = g != null && g.getValueType() == JsonValue.ValueType.OBJECT ? g.asJsonObject().getString("type", "") : "";
                geometry = type.endsWith("Point") ? Geometry.POINTS : type.endsWith("Polygon") ? Geometry.POLYGONS
                        : type.isEmpty() ? Geometry.UNKNOWN : Geometry.OTHER;
            }
        } catch (IOException | RuntimeException e) {
            Logging.info("No sample feature from " + url + ": " + e.getMessage());
        }
        return new Info(title, geometry, new ArrayList<>(fields), ogcExtent(collection));
    }

    /** The collections a landing page or collections list offers, as collection URL to title. */
    private static NotALayerException collectionList(String url, JsonObject doc) {
        String base = url.replaceFirst("\\?.*$", "").replaceAll("/+$", "");
        String prefix = base.endsWith("/collections") ? base + "/" : base + "/collections/";
        Map<String, String> collections = new LinkedHashMap<>();
        for (JsonValue v : doc.getJsonArray("collections")) {
            if (v.getValueType() == JsonValue.ValueType.OBJECT && !v.asJsonObject().getString("id", "").isEmpty()) {
                JsonObject c = v.asJsonObject();
                String title = c.getString("title", "");
                collections.put(prefix + c.getString("id"), title.isEmpty() || title.equals(c.getString("id")) ? c.getString("id")
                        : title + " (" + c.getString("id") + ")");
            }
        }
        String message = tr("this lists collections, not one collection");
        return new NotALayerException(collections.isEmpty() ? message + "; " + tr("it lists none") : message, collections);
    }

    /** Attribute names from the collection's queryables (OGC API - Features Part 3), if offered. */
    private static List<String> queryables(String url, JsonObject collection) {
        String href = OgcFeatureClient.link(collection, "http://www.opengis.net/def/rel/ogc/1.0/queryables", url);
        if (href == null) {
            href = url.replaceFirst("\\?.*$", "").replaceAll("/+$", "") + "/queryables";
        }
        List<String> out = new ArrayList<>();
        try {
            JsonValue props = EsriFeatureClient.get(href, "application/schema+json, application/json").get("properties");
            if (props != null && props.getValueType() == JsonValue.ValueType.OBJECT) {
                for (String k : props.asJsonObject().keySet()) {
                    if (!"geometry".equalsIgnoreCase(k)) {
                        out.add(k);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Logging.trace(e);
        }
        return out;
    }

    private static String sampleUrl(String url) {
        String base = url;
        String query = "";
        int q = base.indexOf('?');
        if (q >= 0) {
            query = base.substring(q + 1) + "&";
            base = base.substring(0, q);
        }
        return base.replaceAll("/+$", "") + "/items?" + query + "limit=1";
    }

    /** extent.spatial.bbox[0], CRS84 longitude/latitude. */
    private static Bounds ogcExtent(JsonObject collection) {
        try {
            JsonArray bbox = collection.getJsonObject("extent").getJsonObject("spatial").getJsonArray("bbox").getJsonArray(0);
            if (bbox.size() == 4) {
                return bounds(num(bbox, 1), num(bbox, 0), num(bbox, 3), num(bbox, 2));
            }
            if (bbox.size() == 6) {
                return bounds(num(bbox, 1), num(bbox, 0), num(bbox, 4), num(bbox, 3));
            }
        } catch (RuntimeException e) {
            Logging.trace(e);
        }
        return null;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static double num(JsonObject o, String key) {
        return ((JsonNumber) o.get(key)).doubleValue();
    }

    private static double num(JsonArray a, int i) {
        return a.getJsonNumber(i).doubleValue();
    }

    /** Valid WGS84 bounds, or null for an empty or nonsense extent (some servers send NaN or 0s). */
    private static Bounds bounds(double minLat, double minLon, double maxLat, double maxLon) {
        if (!(minLat >= -90 && maxLat <= 90 && minLon >= -180 && maxLon <= 180 && minLat <= maxLat && minLon <= maxLon)
                || (minLat == maxLat && minLon == maxLon)) {
            return null;
        }
        return new Bounds(minLat, minLon, maxLat, maxLon);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
