// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.addressconflation.gui.AddressConflationPreferences;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Kind;
import org.openstreetmap.josm.plugins.addressconflation.io.FeatureSource.Protocol;
import org.openstreetmap.josm.plugins.addressconflation.license.Licensing;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * A source the mapper added: their own county or city GIS layer, offered in the download
 * dialog wherever it covers the view. Its licence is "user provided": the mapper vouches for it.
 */
public final class CustomSource {
    static final String PREF = "addressconflation.customSources";

    private static final Pattern ARCGIS_LAYER = Pattern.compile("(?i).*/(FeatureServer|MapServer)/\\d+/*$");
    private static final Pattern OGC_COLLECTION = Pattern.compile("(?i).*/collections/[^/]+/*$");
    private static final Pattern FIELD_SEPARATOR = Pattern.compile("\\s*[+,]\\s*");

    /** The OpenAddresses properties a mapper maps fields onto, per kind, in display order. */
    private static final Map<Kind, List<String>> KEYS = new LinkedHashMap<>();
    /** Common field names for each property, most specific first, compared without case or punctuation. */
    private static final Map<String, List<String>> GUESSES = new LinkedHashMap<>();

    static {
        KEYS.put(Kind.ADDRESSES, Arrays.asList("number", "street", "unit", "city", "region", "postcode"));
        KEYS.put(Kind.PARCELS, Collections.singletonList("pid"));
        KEYS.put(Kind.BUILDINGS, Collections.emptyList());
        GUESSES.put("number", Arrays.asList("addr_housenumber", "housenumber", "house_number", "housenum", "addnum", "add_number",
                "addressnumber", "addrnum", "streetnumber", "stnum", "hsenum", "number", "num"));
        GUESSES.put("street", Arrays.asList("addr_street", "completestreetname", "fullstreetname", "fullstreet", "fullname", "streetname", "fullstname", "stname", "street",
                "roadname", "road"));
        GUESSES.put("unit", Arrays.asList("addr_unit", "unit", "unitnum", "unitnumber", "apt", "suite"));
        GUESSES.put("city", Arrays.asList("addr_city", "postalcity", "city", "municipality", "community", "place"));
        GUESSES.put("region", Arrays.asList("addr_state", "state", "region"));
        GUESSES.put("postcode", Arrays.asList("addr_postcode", "zipcode", "zip", "zip5", "postcode", "postalcode"));
        GUESSES.put("pid", Arrays.asList("parcelid", "parcelnumber", "parcelno", "parcelnum", "stateparcelid", "pin", "apn", "pid",
                "taxpin", "taxid", "parcel"));
    }

    private final String name;
    private final Protocol protocol;
    private final String url;
    private final Kind kind;
    private final Map<String, List<String>> fields;
    private final Bounds extent;

    /**
     * @param fields   OpenAddresses property ({@code number}, {@code street}, {@code pid}...) to the
     *                 service fields joined for it
     * @param extent   the area the service covers, or null when unknown (offered everywhere)
     */
    public CustomSource(String name, Protocol protocol, String url, Kind kind, Map<String, List<String>> fields, Bounds extent) {
        this.name = name.trim();
        this.protocol = protocol;
        this.url = url.trim();
        this.kind = kind;
        Map<String, List<String>> f = new LinkedHashMap<>();
        for (String key : keysFor(kind)) {
            List<String> v = fields.get(key);
            if (v != null && !v.isEmpty()) {
                f.put(key, Collections.unmodifiableList(new ArrayList<>(v)));
            }
        }
        this.fields = Collections.unmodifiableMap(f);
        this.extent = extent;
    }

    public String getName() {
        return name;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public String getUrl() {
        return url;
    }

    public Kind getKind() {
        return kind;
    }

    public Map<String, List<String>> getFields() {
        return fields;
    }

    public Bounds getExtent() {
        return extent;
    }

    /** True when the source may have data in the view: its coverage overlaps it, or is unknown. */
    public boolean covers(Bounds view) {
        return extent == null || view == null || extent.intersects(view);
    }

    /** The source ready to download, with its user-provided licence. */
    public FeatureSource toFeatureSource() {
        boolean expand = kind == Kind.ADDRESSES && AddressConflationPreferences.isExpandStreets();
        return new FeatureSource(name, url, kind, fields, null, expand)
                .withProtocol(protocol).withLicense(Licensing.userProvided());
    }

    // ---- what the mapper fills in ---------------------------------------------------------

    /** The properties a mapper maps for this kind, in display order. */
    public static List<String> keysFor(Kind kind) {
        return KEYS.get(kind);
    }

    /** True for the properties an address source cannot do without. */
    public static boolean isRequired(Kind kind, String key) {
        return kind == Kind.ADDRESSES && ("number".equals(key) || "street".equals(key));
    }

    /** Label for a property in the editor. */
    public static String label(String key) {
        switch (key) {
        case "number":
            return tr("Housenumber");
        case "street":
            return tr("Street");
        case "unit":
            return tr("Unit");
        case "city":
            return tr("City");
        case "region":
            return tr("State");
        case "postcode":
            return tr("Postcode");
        case "pid":
            return tr("Parcel ID");
        default:
            return key;
        }
    }

    /** The protocol a URL's shape gives away, or null. */
    public static Protocol detect(String url) {
        String path = url == null ? "" : url.trim().replaceFirst("\\?.*$", "");
        if (ARCGIS_LAYER.matcher(path).matches()) {
            return Protocol.ARCGIS;
        }
        if (OGC_COLLECTION.matcher(path).matches()) {
            return Protocol.OGC_FEATURES;
        }
        return null;
    }

    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)https?://[^\\s\"'<>]+");
    private static final Pattern ARCGIS_OPERATION = Pattern.compile("(?i)^(.*/(?:FeatureServer|MapServer)/\\d+)(?:[/?].*)?$");
    private static final Pattern OGC_ITEMS = Pattern.compile("(?i)^(.*/collections/[^/?]+)(/items[^?]*)?/*(?:\\?(.*))?$");
    /** Query parameters of an OGC items request, as opposed to ones the service needs (an API key). */
    private static final List<String> OGC_ITEM_PARAMS = Arrays.asList("bbox", "bbox-crs", "crs", "limit", "offset", "startindex", "f",
            "datetime");

    /**
     * The layer URL in whatever was pasted: the web address found in it (a log line, a
     * sentence), reduced to the layer. An ArcGIS query or other operation becomes the layer it
     * runs on; an OGC items request becomes its collection, keeping parameters such as an API key.
     * Text without a web address comes back trimmed.
     */
    public static String cleanUrl(String pasted) {
        if (pasted == null) {
            return "";
        }
        Matcher m = WEB_ADDRESS.matcher(pasted);
        if (!m.find()) {
            return pasted.trim();
        }
        String u = m.group();
        Matcher a = ARCGIS_OPERATION.matcher(u);
        if (a.matches()) {
            return a.group(1);
        }
        Matcher o = OGC_ITEMS.matcher(u);
        if (o.matches()) {
            List<String> kept = new ArrayList<>();
            if (o.group(3) != null) {
                for (String p : o.group(3).split("&")) {
                    String key = p.replaceFirst("=.*$", "").toLowerCase(Locale.ROOT);
                    if (!p.isEmpty() && !OGC_ITEM_PARAMS.contains(key)) {
                        kept.add(p);
                    }
                }
            }
            return o.group(1) + (kept.isEmpty() ? "" : "?" + String.join("&", kept));
        }
        return u;
    }

    private static final Pattern BUILDING_WORDS = Pattern.compile("(?i)building|footprint|structure|bldg");
    private static final Pattern PARCEL_WORDS = Pattern.compile("(?i)parcel|cadastr|tax[ _-]?lot");
    private static final Pattern ADDRESS_WORDS = Pattern.compile("(?i)address|situs|e911");

    /**
     * What a layer most likely holds, from what Check learned: points can only be addresses;
     * otherwise its name (title or URL) says, as in UtahStatewideParcels; otherwise polygons are
     * most often parcels. Null when there is nothing to go on.
     */
    public static Kind suggestKind(String title, String url, ServiceInspector.Geometry geometry) {
        if (geometry == ServiceInspector.Geometry.POINTS) {
            return Kind.ADDRESSES;
        }
        String name = (title == null ? "" : title) + " " + (url == null ? "" : url.replaceFirst("\\?.*$", ""));
        if (BUILDING_WORDS.matcher(name).find()) {
            return Kind.BUILDINGS;
        }
        if (PARCEL_WORDS.matcher(name).find()) {
            return Kind.PARCELS;
        }
        if (ADDRESS_WORDS.matcher(name).find()) {
            return Kind.ADDRESSES;
        }
        return geometry == ServiceInspector.Geometry.POLYGONS ? Kind.PARCELS : null;
    }

    /** True for something that can be fetched: an http or https address. */
    public static boolean isWebAddress(String url) {
        return url != null && url.matches("(?i)https?://[^\\s]+");
    }

    /** The service field that most likely holds a property, or null. */
    public static String guessField(String key, Collection<String> serviceFields) {
        List<String> wanted = GUESSES.getOrDefault(key, Collections.emptyList());
        for (String w : wanted) {
            for (String f : serviceFields) {
                if (squash(f).equals(squash(w))) {
                    return f;
                }
            }
        }
        return null;
    }

    /** Fields as typed in the editor: {@code PREFIX + NAME + SUFFIX} or {@code A, B} joins several. */
    public static List<String> parseFields(String typed) {
        List<String> out = new ArrayList<>();
        if (typed != null) {
            for (String f : FIELD_SEPARATOR.split(typed.trim())) {
                if (!f.isEmpty()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** The reverse of {@link #parseFields}. */
    public static String formatFields(List<String> fields) {
        return fields == null ? "" : String.join(" + ", fields);
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---- storage -----------------------------------------------------------------------------

    /** The mapper's sources, in the order they were added. */
    public static List<CustomSource> load() {
        List<CustomSource> out = new ArrayList<>();
        for (Map<String, String> m : Config.getPref().getListOfMaps(PREF)) {
            CustomSource s = fromMap(m);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public static void save(List<CustomSource> sources) {
        List<Map<String, String>> maps = new ArrayList<>();
        for (CustomSource s : sources) {
            maps.add(s.toMap());
        }
        Config.getPref().putListOfMaps(PREF, maps);
    }

    Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("protocol", protocol.name());
        m.put("url", url);
        m.put("kind", kind.name());
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            m.put("field." + e.getKey(), String.join(",", e.getValue()));
        }
        if (extent != null) {
            m.put("extent", extent.encodeAsString(","));
        }
        return m;
    }

    /** A stored source, or null when the entry is unusable (hand-edited preferences). */
    static CustomSource fromMap(Map<String, String> m) {
        try {
            Map<String, List<String>> fields = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (e.getKey().startsWith("field.")) {
                    fields.put(e.getKey().substring("field.".length()), parseFields(e.getValue()));
                }
            }
            Bounds extent = m.containsKey("extent") ? new Bounds(m.get("extent"), ",") : null;
            return new CustomSource(m.get("name"), Protocol.valueOf(m.get("protocol")), m.get("url"), Kind.valueOf(m.get("kind")),
                    fields, extent);
        } catch (RuntimeException e) {
            Logging.warn("Ignoring unreadable custom source " + m + ": " + e);
            return null;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
