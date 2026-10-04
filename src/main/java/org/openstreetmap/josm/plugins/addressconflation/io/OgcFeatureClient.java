// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.Logging;

/**
 * Fetches the features of an OGC API - Features collection inside a bounding box, following
 * the server's "next" links, and converts them through the source's conform like an ArcGIS
 * layer. Coordinates are the standard's default, CRS84 longitude/latitude.
 */
public final class OgcFeatureClient {
    static final int PAGE = 1000;
    /** Features looked at to learn the service's field spelling. */
    private static final int FIELD_SAMPLE = 200;

    private OgcFeatureClient() {
    }

    /** Fetch and convert. */
    public static DataSet download(FeatureSource source, Bounds bounds, ProgressMonitor monitor) throws IOException {
        List<JsonObject> features;
        try {
            features = fetchRaw(source, bounds, monitor);
        } catch (IOException e) {
            throw new IOException(EsriFeatureClient.describe(e), e);
        }
        checkGeometry(source, features);
        return EsriFeatureClient.toDataSet(matchFields(source, features), features);
    }

    /** Raw GeoJSON features for the bounds, every page. */
    static List<JsonObject> fetchRaw(FeatureSource source, Bounds bounds, ProgressMonitor monitor) throws IOException {
        List<JsonObject> all = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String next = itemsUrl(source.getUrl(), bounds);
        while (next != null && visited.add(next)) {
            if (monitor != null) {
                if (monitor.isCanceled()) {
                    throw new IOException(tr("Cancelled"));
                }
                monitor.setCustomText(tr("{0}: {1} features so far", source.getName(), all.size()));
            }
            JsonObject page = EsriFeatureClient.get(next);
            JsonValue feats = page.get("features");
            if (feats == null || feats.getValueType() != JsonValue.ValueType.ARRAY) {
                throw new IOException(tr("the server sent something other than features"));
            }
            for (JsonValue v : feats.asJsonArray()) {
                if (v.getValueType() == JsonValue.ValueType.OBJECT) {
                    all.add(v.asJsonObject());
                }
            }
            if (feats.asJsonArray().isEmpty()) {
                break;
            }
            if (all.size() >= EsriFeatureClient.MAX_FEATURES) {
                Logging.warn("Feature download hit the safety limit of " + EsriFeatureClient.MAX_FEATURES);
                break;
            }
            next = link(page, "next", next);
        }
        return all;
    }

    /** The items request for a collection URL, keeping any query the URL already has (an API key). */
    static String itemsUrl(String collectionUrl, Bounds b) {
        String base = collectionUrl.trim();
        String query = "";
        int q = base.indexOf('?');
        if (q >= 0) {
            query = base.substring(q + 1);
            base = base.substring(0, q);
        }
        StringBuilder sb = new StringBuilder(base.replaceAll("/+$", "")).append("/items?");
        if (!query.isEmpty()) {
            sb.append(query).append('&');
        }
        sb.append("bbox=").append(EsriFeatureClient.enc(String.format(Locale.ROOT, "%f,%f,%f,%f",
                b.getMinLon(), b.getMinLat(), b.getMaxLon(), b.getMaxLat())));
        sb.append("&limit=").append(PAGE);
        return sb.toString();
    }

    /**
     * The href of the first JSON link with this relation, resolved against the page's own URL;
     * null when there is none. An HTML alternate of the same relation is skipped.
     */
    static String link(JsonObject doc, String rel, String base) {
        JsonValue links = doc.get("links");
        if (links == null || links.getValueType() != JsonValue.ValueType.ARRAY) {
            return null;
        }
        for (JsonValue v : (JsonArray) links) {
            if (v.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            JsonObject l = v.asJsonObject();
            String type = l.getString("type", "");
            if (rel.equals(l.getString("rel", "")) && (type.isEmpty() || type.contains("json")) && !l.getString("href", "").isEmpty()) {
                return URI.create(base).resolve(l.getString("href")).toString();
            }
        }
        return null;
    }

    /** Point features cannot be parcels or building outlines. */
    static void checkGeometry(FeatureSource source, List<JsonObject> features) throws IOException {
        if (source.getKind() == FeatureSource.Kind.ADDRESSES || features.isEmpty()) {
            return;
        }
        for (JsonObject f : features) {
            JsonValue g = f.get("geometry");
            String type = g != null && g.getValueType() == JsonValue.ValueType.OBJECT ? g.asJsonObject().getString("type", "") : "";
            if (!"Point".equals(type) && !"MultiPoint".equals(type)) {
                return;
            }
        }
        throw new IOException(tr("this layer holds points, not {0} outlines", source.getKind() == FeatureSource.Kind.PARCELS
                ? tr("parcel") : tr("building")));
    }

    /** The source with its field names matched, ignoring case, to the properties the features carry. */
    static FeatureSource matchFields(FeatureSource source, List<JsonObject> features) {
        if (source.getConform().isEmpty() || features.isEmpty()) {
            return source;
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonObject f : features.subList(0, Math.min(FIELD_SAMPLE, features.size()))) {
            JsonValue props = f.get("properties");
            if (props != null && props.getValueType() == JsonValue.ValueType.OBJECT) {
                names.addAll(props.asJsonObject().keySet());
            }
        }
        return names.isEmpty() ? source : source.withServiceFields(names);
    }
}
