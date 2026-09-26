// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.license;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.tools.Logging;

/**
 * Sources documented as usable in OpenStreetMap outside their own metadata: permissions on
 * the OSM Contributors page and state laws that remove licence requirements. Bundled as
 * {@code /data/license-clearances.json}.
 */
public final class Clearances {
    /** A recorded permission or state law. */
    static final class Entry {
        final String source;
        final String state;
        final List<String> layers;
        final List<String> urls;
        final boolean overridesDeclared;
        final String basis;
        final String link;

        Entry(String source, String state, List<String> layers, List<String> urls, boolean overridesDeclared, String basis, String link) {
            this.source = source;
            this.state = state;
            this.layers = layers;
            this.urls = urls;
            this.overridesDeclared = overridesDeclared;
            this.basis = basis;
            this.link = link;
        }

        LicenseAssessment assessment(String declared) {
            return new LicenseAssessment(LicenseStatus.COMPATIBLE, basis, declared, link);
        }
    }

    private final List<Entry> sources = new ArrayList<>();
    private final List<Entry> stateLaws = new ArrayList<>();

    private static Clearances bundled;

    Clearances(JsonObject root) {
        for (JsonValue v : root.getJsonArray("sources")) {
            JsonObject o = v.asJsonObject();
            sources.add(new Entry(o.getString("source"), null, strings(o, "layers"), strings(o, "urls"), true,
                    o.getString("basis", null), o.getString("url", null)));
        }
        for (JsonValue v : root.getJsonArray("stateLaw")) {
            JsonObject o = v.asJsonObject();
            stateLaws.add(new Entry(null, o.getString("state"), strings(o, "layers"), List.of(), o.getBoolean("overridesDeclared", false),
                    o.getString("basis", null), o.getString("url", null)));
        }
    }

    /** The list shipped with the plugin; empty if it cannot be read. */
    public static synchronized Clearances bundled() {
        if (bundled == null) {
            try (InputStream is = Clearances.class.getResourceAsStream("/data/license-clearances.json");
                 JsonReader r = JsonSupport.JSON.createReader(is)) {
                bundled = new Clearances(r.readObject());
            } catch (IOException | RuntimeException e) {
                Logging.warn("Could not read the licence clearance list: " + e.getMessage());
                bundled = new Clearances(JsonSupport.JSON.createObjectBuilder().add("sources", JsonSupport.JSON.createArrayBuilder())
                        .add("stateLaw", JsonSupport.JSON.createArrayBuilder()).build());
            }
        }
        return bundled;
    }

    /**
     * A recorded permission for this source's layer, read from this URL: the URL must be on
     * one of the permitted servers, so a source that moves does not keep a permission that
     * was about the old data.
     */
    Entry permission(String sourceId, String layer, String dataUrl) {
        String u = normalize(dataUrl);
        for (Entry e : sources) {
            if (e.source.equals(sourceId) && e.layers.contains(layer) && e.urls.stream().anyMatch(u::contains)) {
                return e;
            }
        }
        return null;
    }

    /** The state law covering this source's layer, from the state part of its id ({@code us/ca/...}). */
    Entry stateLaw(String sourceId, String layer) {
        String[] parts = sourceId == null ? new String[0] : sourceId.split("/");
        if (parts.length < 2 || !"us".equals(parts[0])) {
            return null;
        }
        for (Entry e : stateLaws) {
            if (e.state.equals(parts[1]) && e.layers.contains(layer)) {
                return e;
            }
        }
        return null;
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.containsKey(key)) {
            for (JsonValue v : o.getJsonArray(key)) {
                if (v instanceof JsonString) {
                    out.add(((JsonString) v).getString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
