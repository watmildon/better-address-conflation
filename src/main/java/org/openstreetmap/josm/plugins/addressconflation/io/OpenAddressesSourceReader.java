// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

/**
 * Reads an OpenAddresses source definition (schema 2) and returns the layers
 * that can be fetched live: those with the ESRI protocol and a conform made of
 * plain field names. Function-style conforms (regexp, join with a separator
 * ...) are skipped with a log message.
 */
public final class OpenAddressesSourceReader {
    private static final String GITHUB_RAW = "https://raw.githubusercontent.com/openaddresses/openaddresses/master/sources/";

    private OpenAddressesSourceReader() {
    }

    /**
     * Resolve a user-supplied reference to a URL or path. Accepts a full URL,
     * a local file path, or an OpenAddresses source id such as {@code us/az/maricopa}.
     */
    public static String resolve(String ref) {
        String r = ref.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return r;
        }
        if (Files.exists(Paths.get(r))) {
            return r;
        }
        String id = r.endsWith(".json") ? r.substring(0, r.length() - 5) : r;
        return GITHUB_RAW + id.toLowerCase(Locale.ROOT) + ".json";
    }

    /** Fetch and parse the definition at a resolved URL or path. */
    public static List<FeatureSource> load(String urlOrPath, boolean expandStreets) throws IOException {
        String text;
        if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            HttpClient.Response resp = HttpClient.create(URI.create(urlOrPath).toURL()).setConnectTimeout(15000).setReadTimeout(30000).connect();
            try {
                if (resp.getResponseCode() != 200) {
                    throw new IOException("HTTP " + resp.getResponseCode() + " for " + urlOrPath);
                }
                text = resp.fetchContent();
            } finally {
                resp.disconnect();
            }
        } else {
            text = new String(Files.readAllBytes(Paths.get(urlOrPath)), StandardCharsets.UTF_8);
        }
        return parse(text, expandStreets);
    }

    public static List<FeatureSource> parse(InputStream is, boolean expandStreets) throws IOException {
        return parse(new String(is.readAllBytes(), StandardCharsets.UTF_8), expandStreets);
    }

    public static List<FeatureSource> parse(String json, boolean expandStreets) throws IOException {
        JsonObject root;
        try (JsonReader reader = JsonSupport.JSON.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (JsonException e) {
            throw new IOException("Not a valid OpenAddresses source: " + e.getMessage(), e);
        }
        if (root.getInt("schema", 1) != 2) {
            throw new IOException("Only OpenAddresses schema 2 sources are supported");
        }
        String base = coverageName(root);
        List<FeatureSource> out = new ArrayList<>();
        JsonObject layers = root.getJsonObject("layers");
        if (layers == null) {
            return out;
        }
        for (Map.Entry<String, JsonValue> layer : layers.entrySet()) {
            FeatureSource.Kind kind;
            switch (layer.getKey()) {
            case "addresses":
                kind = FeatureSource.Kind.ADDRESSES;
                break;
            case "parcels":
                kind = FeatureSource.Kind.PARCELS;
                break;
            case "buildings":
                kind = FeatureSource.Kind.BUILDINGS;
                break;
            default:
                continue;
            }
            for (JsonValue v : layer.getValue().asJsonArray()) {
                JsonObject entry = v.asJsonObject();
                if (!"ESRI".equals(entry.getString("protocol", ""))) {
                    Logging.info("OpenAddresses layer " + layer.getKey() + " skipped: protocol " + entry.getString("protocol", "?"));
                    continue;
                }
                Map<String, List<String>> conform = new LinkedHashMap<>();
                boolean ok = true;
                JsonObject c = entry.getJsonObject("conform");
                if (c != null) {
                    for (Map.Entry<String, JsonValue> ce : c.entrySet()) {
                        if ("format".equals(ce.getKey())) {
                            continue;
                        }
                        List<String> fields = fieldList(ce.getValue());
                        if (fields == null) {
                            Logging.info("OpenAddresses layer " + layer.getKey() + ": function-style conform for " + ce.getKey() + " not supported, field ignored");
                            continue;
                        }
                        conform.put(ce.getKey(), fields);
                    }
                }
                if (kind == FeatureSource.Kind.ADDRESSES && !conform.containsKey("number")) {
                    ok = false;
                }
                if (!ok) {
                    Logging.info("OpenAddresses layer " + layer.getKey() + " skipped: no usable number field");
                    continue;
                }
                String name = base + " " + layer.getKey() + (entry.containsKey("name") ? " (" + entry.getString("name") + ")" : "");
                JsonValue license = entry.containsKey("license") ? entry.get("license") : root.get("license");
                out.add(new FeatureSource(name, entry.getString("data"), kind, conform, entry.getString("_where", null), expandStreets)
                        .withDeclaredLicense(license));
            }
        }
        return out;
    }

    private static List<String> fieldList(JsonValue v) {
        if (v instanceof JsonString) {
            return List.of(((JsonString) v).getString());
        }
        if (v.getValueType() == JsonValue.ValueType.ARRAY) {
            List<String> out = new ArrayList<>();
            for (JsonValue e : (JsonArray) v) {
                if (!(e instanceof JsonString)) {
                    return null;
                }
                out.add(((JsonString) e).getString());
            }
            return out;
        }
        return null;
    }

    private static String coverageName(JsonObject root) {
        JsonObject cov = root.getJsonObject("coverage");
        if (cov == null) {
            return "OpenAddresses";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : new String[] {"county", "city", "town", "state", "country"}) {
            if (cov.containsKey(k) && cov.get(k) instanceof JsonString) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(cov.getString(k));
            }
        }
        return sb.length() == 0 ? "OpenAddresses" : sb.toString();
    }
}
