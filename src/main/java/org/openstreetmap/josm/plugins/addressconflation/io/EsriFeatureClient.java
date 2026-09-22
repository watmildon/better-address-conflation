// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

/**
 * Fetches every feature of an ESRI layer inside a bounding box, paging through
 * the service's record limit, and converts them to a dataset. Modeled on the
 * NAD client in TIGER-ROAR.
 */
public final class EsriFeatureClient {
    /** Largest bbox we will fetch, in square degrees (~0.14° x 0.14°, about 15 km x 12 km). */
    public static final double MAX_AREA_DEGREES = 0.02;
    private static final int PAGE = 1000;
    private static final int MAX_FEATURES = 200_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private EsriFeatureClient() {
    }

    /** Human-readable reason the bounds are too big, or null when they are fine. */
    public static String areaProblem(Bounds bounds) {
        double area = bounds.getArea();
        if (area > MAX_AREA_DEGREES) {
            return tr("The view is too large to download ({0} square degrees, limit {1}). Zoom in.",
                    String.format(Locale.ROOT, "%.4f", area), String.format(Locale.ROOT, "%.3f", MAX_AREA_DEGREES));
        }
        return null;
    }

    /** Fetch and convert. */
    public static DataSet download(EsriFeatureSource source, Bounds bounds, ProgressMonitor monitor) throws IOException {
        List<JsonObject> features = fetchRaw(source, bounds, monitor);
        List<JsonObject> oa = new ArrayList<>(features.size());
        for (JsonObject f : features) {
            JsonObject o = source.toOaFeature(f);
            if (o != null) {
                oa.add(o);
            }
        }
        OpenAddressesReader.Layer layer;
        switch (source.getKind()) {
        case PARCELS:
            layer = OpenAddressesReader.Layer.PARCELS;
            break;
        case BUILDINGS:
            layer = OpenAddressesReader.Layer.BUILDINGS;
            break;
        default:
            layer = OpenAddressesReader.Layer.ADDRESSES;
        }
        return OpenAddressesReader.fromFeatures(oa, layer, source.isExpandStreets());
    }

    /** Raw ESRI GeoJSON features for the bounds. */
    public static List<JsonObject> fetchRaw(EsriFeatureSource source, Bounds bounds, ProgressMonitor monitor) throws IOException {
        List<JsonObject> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            if (monitor != null) {
                if (monitor.isCanceled()) {
                    throw new IOException(tr("Cancelled"));
                }
                monitor.setCustomText(tr("{0}: {1} features so far", source.getName(), all.size()));
            }
            String url = queryUrl(source, bounds, offset, "geojson");
            JsonObject page = get(url);
            if (page.containsKey("error")) {
                // Older MapServers do not speak GeoJSON; fall back to the Esri JSON format.
                page = toGeoJson(get(queryUrl(source, bounds, offset, "json")));
            }
            JsonArray feats = page.getJsonArray("features");
            if (feats == null) {
                throw new IOException(tr("Unexpected response from {0}", source.getUrl()));
            }
            for (JsonValue v : feats) {
                all.add(v.asJsonObject());
            }
            boolean exceeded = exceeded(page);
            if (feats.size() < PAGE && !exceeded || feats.isEmpty()) {
                break;
            }
            offset += feats.size();
            if (all.size() >= MAX_FEATURES) {
                Logging.warn("Feature download hit the safety limit of " + MAX_FEATURES);
                break;
            }
        }
        return all;
    }

    private static boolean exceeded(JsonObject page) {
        JsonObject props = page.containsKey("properties") && page.get("properties").getValueType() == JsonValue.ValueType.OBJECT
                ? page.getJsonObject("properties") : null;
        if (props != null && props.containsKey("exceededTransferLimit")) {
            return props.getBoolean("exceededTransferLimit", false);
        }
        return page.getBoolean("exceededTransferLimit", false);
    }

    static String queryUrl(EsriFeatureSource source, Bounds b, int offset, String format) {
        StringBuilder sb = new StringBuilder(source.getUrl().replaceAll("/+$", "")).append("/query?");
        sb.append("f=").append(format);
        sb.append("&geometry=").append(enc(String.format(Locale.ROOT, "%f,%f,%f,%f", b.getMinLon(), b.getMinLat(), b.getMaxLon(), b.getMaxLat())));
        sb.append("&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects");
        sb.append("&outFields=").append(enc(source.outFields()));
        sb.append("&outSR=4326&returnGeometry=true");
        sb.append("&resultOffset=").append(offset).append("&resultRecordCount=").append(PAGE);
        if (source.getWhere() != null && !source.getWhere().isEmpty()) {
            sb.append("&where=").append(enc(source.getWhere()));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonObject get(String url) throws IOException {
        Logging.info("ESRI query: " + url);
        HttpClient.Response resp = HttpClient.create(URI.create(url).toURL())
                .setAccept("application/geo+json, application/json")
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .connect();
        try {
            if (resp.getResponseCode() != 200) {
                throw new IOException(tr("HTTP {0} from {1}", resp.getResponseCode(), url));
            }
            String body = resp.fetchContent();
            try (JsonReader reader = Json.createReader(new StringReader(body))) {
                return reader.readObject();
            } catch (JsonException e) {
                throw new IOException(tr("Not JSON: {0}", body.length() > 120 ? body.substring(0, 120) : body), e);
            }
        } finally {
            resp.disconnect();
        }
    }

    /** Convert an Esri JSON feature set (points and polygons) to GeoJSON shape. */
    static JsonObject toGeoJson(JsonObject esri) throws IOException {
        if (esri.containsKey("error")) {
            JsonObject err = esri.getJsonObject("error");
            throw new IOException(tr("Service error: {0}", err.getString("message", err.toString())));
        }
        var features = Json.createArrayBuilder();
        JsonArray in = esri.getJsonArray("features");
        if (in != null) {
            for (JsonValue v : in) {
                JsonObject f = v.asJsonObject();
                JsonObject g = f.containsKey("geometry") && f.get("geometry").getValueType() == JsonValue.ValueType.OBJECT ? f.getJsonObject("geometry") : null;
                var geom = Json.createObjectBuilder();
                if (g == null) {
                    continue;
                } else if (g.containsKey("x")) {
                    geom.add("type", "Point").add("coordinates", Json.createArrayBuilder().add(g.getJsonNumber("x").doubleValue()).add(g.getJsonNumber("y").doubleValue()));
                } else if (g.containsKey("rings")) {
                    geom.add("type", "Polygon").add("coordinates", g.getJsonArray("rings"));
                } else if (g.containsKey("paths")) {
                    geom.add("type", "LineString").add("coordinates", g.getJsonArray("paths").getJsonArray(0));
                } else {
                    continue;
                }
                features.add(Json.createObjectBuilder().add("type", "Feature")
                        .add("properties", f.containsKey("attributes") ? f.getJsonObject("attributes") : JsonValue.EMPTY_JSON_OBJECT)
                        .add("geometry", geom));
            }
        }
        var out = Json.createObjectBuilder().add("type", "FeatureCollection").add("features", features);
        if (esri.containsKey("exceededTransferLimit")) {
            out.add("exceededTransferLimit", esri.getBoolean("exceededTransferLimit", false));
        }
        return out.build();
    }
}
