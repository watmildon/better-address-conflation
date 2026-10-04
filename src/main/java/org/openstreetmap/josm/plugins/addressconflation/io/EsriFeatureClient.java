// SPDX-License-Identifier: MIT
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

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
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
        source = matchServiceFields(source);
        List<JsonObject> features;
        try {
            features = fetchRaw(source, bounds, monitor);
        } catch (IOException e) {
            throw new IOException(describe(e), e);
        }
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

    /**
     * The source with its field names matched to the service's; unchanged if the service will
     * not describe itself. Fails when the layer cannot be what the source claims: some
     * OpenAddresses "parcel" sources point at address-point layers.
     */
    static EsriFeatureSource matchServiceFields(EsriFeatureSource source) throws IOException {
        JsonObject info;
        try {
            info = get(source.getUrl().replaceAll("/+$", "") + "?f=json");
        } catch (IOException e) {
            Logging.info("Could not read the layer description of " + source.getName() + ": " + e.getMessage());
            return source;
        }
        checkGeometry(source, info.getString("geometryType", ""));
        JsonArray fields = info.containsKey("fields") && info.get("fields").getValueType() == JsonValue.ValueType.ARRAY
                ? info.getJsonArray("fields") : null;
        if (source.getConform().isEmpty() || fields == null || fields.isEmpty()) {
            return source;
        }
        List<String> names = new ArrayList<>();
        for (JsonValue f : fields) {
            if (f.getValueType() == JsonValue.ValueType.OBJECT) {
                names.add(f.asJsonObject().getString("name", ""));
            }
        }
        return source.withServiceFields(names);
    }

    static void checkGeometry(EsriFeatureSource source, String esriGeometryType) throws IOException {
        boolean points = "esriGeometryPoint".equals(esriGeometryType) || "esriGeometryMultipoint".equals(esriGeometryType);
        if (points && source.getKind() != EsriFeatureSource.Kind.ADDRESSES) {
            throw new IOException(tr("this layer holds points, not {0} outlines", source.getKind() == EsriFeatureSource.Kind.PARCELS
                    ? tr("parcel") : tr("building")));
        }
    }

    /** Raw ESRI GeoJSON features for the bounds. */
    public static List<JsonObject> fetchRaw(EsriFeatureSource source, Bounds bounds, ProgressMonitor monitor) throws IOException {
        List<JsonObject> all = new ArrayList<>();
        int offset = 0;
        // Start with GeoJSON; older servers only speak Esri JSON, and when anything goes wrong
        // Esri JSON also carries the clearer error ("Service ... not started", "Token Required").
        String format = "geojson";
        // Some servers refuse resultOffset/resultRecordCount outright.
        boolean paging = true;
        while (true) {
            if (monitor != null) {
                if (monitor.isCanceled()) {
                    throw new IOException(tr("Cancelled"));
                }
                monitor.setCustomText(tr("{0}: {1} features so far", source.getName(), all.size()));
            }
            JsonObject page;
            try {
                page = queryPage(source, bounds, offset, format, paging);
            } catch (IOException e) {
                if (paging && offset == 0 && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("pagination")) {
                    paging = false;
                    continue;
                }
                if ("geojson".equals(format)) {
                    format = "json";
                    continue;
                }
                throw e;
            }
            JsonArray feats = page.getJsonArray("features");
            if (feats == null) {
                throw new IOException(tr("the server sent something other than features"));
            }
            for (JsonValue v : feats) {
                all.add(v.asJsonObject());
            }
            boolean exceeded = exceeded(page);
            if (!paging) {
                if (exceeded) {
                    Logging.warn(source.getName() + " cannot page; only the first " + feats.size() + " features were downloaded");
                }
                break;
            }
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

    /** One query page as GeoJSON, whatever format was asked for; service errors become exceptions. */
    private static JsonObject queryPage(EsriFeatureSource source, Bounds b, int offset, String format, boolean paging) throws IOException {
        JsonObject page = get(queryUrl(source, b, offset, format, paging));
        String error = errorText(page);
        if (error != null) {
            throw new IOException(tr("service error: {0}", error));
        }
        return "json".equals(format) ? toGeoJson(page) : page;
    }

    static String queryUrl(EsriFeatureSource source, Bounds b, int offset, String format) {
        return queryUrl(source, b, offset, format, true);
    }

    static String queryUrl(EsriFeatureSource source, Bounds b, int offset, String format, boolean paging) {
        StringBuilder sb = new StringBuilder(source.getUrl().replaceAll("/+$", "")).append("/query?");
        sb.append("f=").append(format);
        sb.append("&geometry=").append(enc(String.format(Locale.ROOT, "%f,%f,%f,%f", b.getMinLon(), b.getMinLat(), b.getMaxLon(), b.getMaxLat())));
        sb.append("&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects");
        sb.append("&outFields=").append(enc(source.outFields()));
        sb.append("&outSR=4326&returnGeometry=true");
        if (paging) {
            sb.append("&resultOffset=").append(offset).append("&resultRecordCount=").append(PAGE);
        }
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
                String reason = serverMessage(resp.fetchContent());
                Logging.warn("ESRI query failed with HTTP " + resp.getResponseCode() + ": " + url);
                throw new IOException(reason == null ? tr("server error (HTTP {0})", resp.getResponseCode())
                        : tr("server error (HTTP {0}): {1}", resp.getResponseCode(), reason));
            }
            String body = resp.fetchContent();
            try (JsonReader reader = JsonSupport.JSON.createReader(new StringReader(body))) {
                return reader.readObject();
            } catch (JsonException e) {
                // Typically an ArcGIS or proxy error page served with HTTP 200.
                String title = serverMessage(body);
                throw new IOException(title != null ? tr("the server sent a web page instead of data: {0}", title)
                        : tr("the server sent a web page instead of data"), e);
            }
        } finally {
            resp.disconnect();
        }
    }

    /** Longest server message we pass on to the user. */
    private static final int MAX_REASON = 160;

    /**
     * The human part of an ArcGIS error body: the JSON error message, or the HTML page
     * title ArcGIS Enterprise sends for GeoJSON requests. Null when there is none.
     */
    static String serverMessage(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String msg = null;
        try (JsonReader reader = JsonSupport.JSON.createReader(new StringReader(body))) {
            msg = errorText(reader.readObject());
        } catch (JsonException | IllegalStateException e) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<title>\\s*(.*?)\\s*</title>").matcher(body);
            if (m.find()) {
                msg = m.group(1).replaceFirst("^(?i)error:\\s*", "");
            }
        }
        if (msg == null || msg.trim().isEmpty()) {
            return null;
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > MAX_REASON ? msg.substring(0, MAX_REASON) + "..." : msg;
    }

    /**
     * The error a JSON response reports, or null: ArcGIS's {"error": {"message", "details"}}
     * (message is often empty with the reason in details) or the older
     * {"status": "error", "messages": [...]}.
     */
    static String errorText(JsonObject o) {
        List<String> parts = new ArrayList<>();
        if (o.containsKey("error") && o.get("error").getValueType() == JsonValue.ValueType.OBJECT) {
            JsonObject err = o.getJsonObject("error");
            String m = err.getString("message", "");
            if (!m.trim().isEmpty()) {
                parts.add(m.trim());
            }
            if (parts.isEmpty() && err.containsKey("details") && err.get("details").getValueType() == JsonValue.ValueType.ARRAY) {
                for (JsonValue d : err.getJsonArray("details")) {
                    if (d instanceof JsonString && !((JsonString) d).getString().trim().isEmpty()) {
                        parts.add(((JsonString) d).getString().trim());
                    }
                }
            }
            if (parts.isEmpty()) {
                parts.add(tr("error code {0}", err.containsKey("code") ? err.get("code").toString() : "?"));
            }
        } else if ("error".equals(o.getString("status", null)) && o.containsKey("messages")
                && o.get("messages").getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue d : o.getJsonArray("messages")) {
                if (d instanceof JsonString) {
                    parts.add(((JsonString) d).getString().trim());
                }
            }
            if (parts.isEmpty()) {
                parts.add(tr("unknown error"));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        String msg = String.join(" ", parts).replaceAll("\\s+", " ");
        return msg.length() > MAX_REASON ? msg.substring(0, MAX_REASON) + "..." : msg;
    }

    /** A network failure in words a mapper can act on. */
    public static String describe(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) {
                return tr("the server''s security certificate could not be verified");
            }
            if (t instanceof java.net.UnknownHostException) {
                return tr("server {0} not found", t.getMessage());
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return tr("the server did not answer in time");
            }
            if (t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException) {
                return tr("could not connect to the server");
            }
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** Convert an Esri JSON feature set (points and polygons) to GeoJSON shape. */
    static JsonObject toGeoJson(JsonObject esri) throws IOException {
        String error = errorText(esri);
        if (error != null) {
            throw new IOException(tr("service error: {0}", error));
        }
        var features = JsonSupport.JSON.createArrayBuilder();
        JsonArray in = esri.getJsonArray("features");
        if (in != null) {
            for (JsonValue v : in) {
                JsonObject f = v.asJsonObject();
                JsonObject g = f.containsKey("geometry") && f.get("geometry").getValueType() == JsonValue.ValueType.OBJECT ? f.getJsonObject("geometry") : null;
                var geom = JsonSupport.JSON.createObjectBuilder();
                if (g == null) {
                    continue;
                } else if (g.containsKey("x")) {
                    geom.add("type", "Point").add("coordinates", JsonSupport.JSON.createArrayBuilder().add(g.getJsonNumber("x").doubleValue()).add(g.getJsonNumber("y").doubleValue()));
                } else if (g.containsKey("rings")) {
                    geom.add("type", "Polygon").add("coordinates", g.getJsonArray("rings"));
                } else if (g.containsKey("paths")) {
                    if (g.getJsonArray("paths").isEmpty()) {
                        continue;
                    }
                    geom.add("type", "LineString").add("coordinates", g.getJsonArray("paths").getJsonArray(0));
                } else {
                    continue;
                }
                features.add(JsonSupport.JSON.createObjectBuilder().add("type", "Feature")
                        .add("properties", f.containsKey("attributes") ? f.getJsonObject("attributes") : JsonValue.EMPTY_JSON_OBJECT)
                        .add("geometry", geom));
            }
        }
        var out = JsonSupport.JSON.createObjectBuilder().add("type", "FeatureCollection").add("features", features);
        if (esri.containsKey("exceededTransferLimit")) {
            out.add("exceededTransferLimit", esri.getBoolean("exceededTransferLimit", false));
        }
        return out.build();
    }
}
