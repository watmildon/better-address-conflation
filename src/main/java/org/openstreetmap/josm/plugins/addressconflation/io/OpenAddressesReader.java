// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;

/**
 * Reads OpenAddresses output files (line-delimited GeoJSON, one Feature per
 * line; a plain FeatureCollection is accepted too) into a DataSet.
 *
 * <ul>
 * <li>addresses: number, street, unit, city, district, region, postcode become addr:* tags</li>
 * <li>parcels: pid becomes {@code oa:pid}; polygons become closed ways or multipolygons</li>
 * <li>buildings: building=yes plus height</li>
 * </ul>
 */
public final class OpenAddressesReader {

    /** Which OpenAddresses layer a file holds. AUTO sniffs the first feature. */
    public enum Layer {
        ADDRESSES, PARCELS, BUILDINGS, AUTO
    }

    /** Looking the provider up per call goes through ServiceLoader; do it once. */
    private static final JsonReaderFactory READER_FACTORY = JsonSupport.JSON.createReaderFactory(null);

    private final DataSet ds = new DataSet();
    private final Map<String, Node> nodeCache = new HashMap<>();
    private final boolean expandStreets;
    private Layer layer;
    private int skipped;

    private OpenAddressesReader(Layer layer, boolean expandStreets) {
        this.layer = layer;
        this.expandStreets = expandStreets;
    }

    /**
     * Read a file.
     *
     * @param is the file contents
     * @param layer which layer it is, or AUTO
     * @param expandStreets expand "N 56TH DR" to "North 56th Drive" for address layers
     * @return a new dataset
     * @throws IOException on read or parse failure
     */
    public static DataSet read(InputStream is, Layer layer, boolean expandStreets) throws IOException {
        OpenAddressesReader r = new OpenAddressesReader(layer, expandStreets);
        r.parse(is);
        return r.ds;
    }

    /**
     * Build a dataset from already-parsed OpenAddresses-shaped features (as produced by
     * {@link FeatureSource#toOaFeature}).
     */
    public static DataSet fromFeatures(Iterable<JsonObject> features, Layer layer, boolean expandStreets) {
        OpenAddressesReader r = new OpenAddressesReader(layer, expandStreets);
        for (JsonObject f : features) {
            r.feature(f);
        }
        return r.ds;
    }

    public static Layer sniff(JsonObject feature) {
        JsonObject props = feature.getJsonObject("properties");
        if (props == null) {
            return Layer.BUILDINGS;
        }
        if (props.containsKey("pid")) {
            return Layer.PARCELS;
        }
        if (props.containsKey("number") || props.containsKey("street")) {
            return Layer.ADDRESSES;
        }
        return Layer.BUILDINGS;
    }

    private void parse(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String first = null;
        String line;
        List<String> lines = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (first == null) {
                first = line;
            }
            lines.add(line);
        }
        if (first == null) {
            return;
        }
        boolean collection = lines.size() == 1 || !first.trim().endsWith("}") || first.contains("\"FeatureCollection\"");
        if (collection) {
            JsonObject root = parseObject(String.join("\n", lines));
            if ("FeatureCollection".equals(root.getString("type", ""))) {
                JsonArray feats = root.getJsonArray("features");
                for (JsonValue v : feats) {
                    feature(v.asJsonObject());
                }
            } else {
                feature(root);
            }
        } else {
            for (String l : lines) {
                try {
                    feature(parseObject(l));
                } catch (JsonException e) {
                    skipped++;
                }
            }
        }
    }

    private static JsonObject parseObject(String text) throws IOException {
        try (JsonReader reader = READER_FACTORY.createReader(new StringReader(text))) {
            return reader.readObject();
        } catch (JsonException e) {
            throw new IOException("Not valid GeoJSON: " + e.getMessage(), e);
        }
    }

    private void feature(JsonObject feature) {
        if (layer == Layer.AUTO) {
            layer = sniff(feature);
        }
        JsonObject geometry = feature.containsKey("geometry") && feature.get("geometry").getValueType() == JsonValue.ValueType.OBJECT
                ? feature.getJsonObject("geometry") : null;
        JsonObject props = feature.containsKey("properties") && feature.get("properties").getValueType() == JsonValue.ValueType.OBJECT
                ? feature.getJsonObject("properties") : JsonValue.EMPTY_JSON_OBJECT;
        if (geometry == null) {
            skipped++;
            return;
        }
        Map<String, String> tags = tagsFor(props);
        String type = geometry.getString("type", "");
        JsonArray coords = geometry.getJsonArray("coordinates");
        if (coords == null || coords.isEmpty()) {
            skipped++;
            return;
        }
        switch (type) {
        case "Point":
            Node n = node(coords, false);
            n.setKeys(tags);
            break;
        case "LineString":
            Way w = way(coords, false);
            w.setKeys(tags);
            break;
        case "Polygon":
            polygon(coords, tags);
            break;
        case "MultiPolygon":
            if (coords.size() == 1) {
                polygon(coords.getJsonArray(0), tags);
            } else {
                Relation r = new Relation();
                for (JsonValue poly : coords) {
                    addPolygonMembers(r, poly.asJsonArray());
                }
                tags.put("type", "multipolygon");
                r.setKeys(tags);
                ds.addPrimitive(r);
            }
            break;
        case "MultiPoint":
            for (JsonValue p : coords) {
                Node mp = node(p.asJsonArray(), false);
                mp.setKeys(tags);
            }
            break;
        default:
            skipped++;
        }
    }

    private void polygon(JsonArray rings, Map<String, String> tags) {
        if (rings.isEmpty()) {
            skipped++;
            return;
        }
        if (rings.size() == 1) {
            Way w = way(rings.getJsonArray(0), true);
            w.setKeys(tags);
            return;
        }
        Relation r = new Relation();
        addPolygonMembers(r, rings);
        tags.put("type", "multipolygon");
        r.setKeys(tags);
        ds.addPrimitive(r);
    }

    private void addPolygonMembers(Relation r, JsonArray rings) {
        for (int i = 0; i < rings.size(); i++) {
            Way w = way(rings.getJsonArray(i), true);
            r.addMember(new RelationMember(i == 0 ? "outer" : "inner", w));
        }
    }

    private Way way(JsonArray coords, boolean closed) {
        Way w = new Way();
        List<Node> nodes = new ArrayList<>(coords.size());
        for (JsonValue c : coords) {
            nodes.add(node(c.asJsonArray(), true));
        }
        if (closed && !nodes.isEmpty() && nodes.get(0) != nodes.get(nodes.size() - 1)) {
            nodes.add(nodes.get(0));
        }
        w.setNodes(nodes);
        ds.addPrimitive(w);
        return w;
    }

    private Node node(JsonArray coord, boolean shared) {
        double lon = coord.getJsonNumber(0).doubleValue();
        double lat = coord.getJsonNumber(1).doubleValue();
        if (shared) {
            String key = String.format(Locale.ROOT, "%.7f,%.7f", lat, lon);
            Node cached = nodeCache.get(key);
            if (cached != null) {
                return cached;
            }
            Node n = new Node(new LatLon(lat, lon));
            ds.addPrimitive(n);
            nodeCache.put(key, n);
            return n;
        }
        Node n = new Node(new LatLon(lat, lon));
        ds.addPrimitive(n);
        return n;
    }

    private Map<String, String> tagsFor(JsonObject props) {
        Map<String, String> tags = new HashMap<>();
        switch (layer) {
        case ADDRESSES:
            put(tags, "addr:housenumber", str(props, "number"));
            String street = str(props, "street");
            put(tags, "addr:street", expandStreets ? StreetExpander.expand(street) : street);
            put(tags, "addr:unit", str(props, "unit"));
            put(tags, "addr:city", titleIfCaps(str(props, "city")));
            put(tags, "addr:postcode", str(props, "postcode"));
            put(tags, "addr:state", str(props, "region"));
            put(tags, "addr:district", titleIfCaps(str(props, "district")));
            break;
        case PARCELS:
            put(tags, ParcelCellSource.PID_KEY, str(props, "pid"));
            tags.put("oa:layer", "parcels");
            break;
        default:
            tags.put("building", "yes");
            put(tags, "height", str(props, "height"));
            break;
        }
        return tags;
    }

    private static void put(Map<String, String> tags, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            tags.put(key, value.trim());
        }
    }

    private static String str(JsonObject o, String key) {
        JsonValue v = o.get(key);
        if (v == null) {
            return null;
        }
        switch (v.getValueType()) {
        case STRING:
            return ((JsonString) v).getString();
        case NUMBER:
            return v.toString();
        case NULL:
            return null;
        default:
            return v.toString();
        }
    }

    private static String titleIfCaps(String s) {
        if (s == null || !s.equals(s.toUpperCase(Locale.ROOT))) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(StreetExpander.capitalize(w.toLowerCase(Locale.ROOT)));
        }
        return sb.toString();
    }

    /** Number of features that could not be converted. */
    public int getSkipped() {
        return skipped;
    }

    /** Anything with a pid is a parcel, so the reader recognises its own output. */
    public static boolean isParcelDataSet(DataSet ds) {
        for (OsmPrimitive p : ds.allPrimitives()) {
            if (p.hasKey(ParcelCellSource.PID_KEY)) {
                return true;
            }
        }
        return false;
    }
}
