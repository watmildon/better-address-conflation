// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.openstreetmap.josm.plugins.addressconflation.model.ExistingKind;

/** Normalizes addr:* values for keys and comparisons. US-centric expansions. */
public final class AddressNormalizer {
    private static final Map<String, String> EXPANSIONS = new HashMap<>();
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    static {
        String[][] pairs = {
            {"ave", "avenue"}, {"av", "avenue"}, {"blvd", "boulevard"}, {"cir", "circle"}, {"ct", "court"},
            {"dr", "drive"}, {"expy", "expressway"}, {"hwy", "highway"}, {"ln", "lane"}, {"pkwy", "parkway"},
            {"pl", "place"}, {"rd", "road"}, {"st", "street"}, {"ter", "terrace"}, {"terr", "terrace"},
            {"trl", "trail"}, {"tr", "trail"}, {"way", "way"}, {"n", "north"}, {"s", "south"}, {"e", "east"},
            {"w", "west"}, {"ne", "northeast"}, {"nw", "northwest"}, {"se", "southeast"}, {"sw", "southwest"},
            {"mt", "mount"}, {"ft", "fort"}, {"sq", "square"}, {"hts", "heights"}, {"jct", "junction"},
            {"lp", "loop"}, {"pt", "point"}, {"rte", "route"}, {"xing", "crossing"}, {"cv", "cove"},
            {"byp", "bypass"}, {"aly", "alley"}, {"plz", "plaza"}, {"rdg", "ridge"}, {"vw", "view"},
        };
        for (String[] p : pairs) {
            EXPANSIONS.put(p[0], p[1]);
        }
    }

    private AddressNormalizer() {
    }

    /** Expansion for a lower-case abbreviation ("ave" gives "avenue"), or null. */
    public static String expansion(String abbreviation) {
        return EXPANSIONS.get(abbreviation);
    }

    /** Lower-case, punctuation-free, abbreviation-expanded street name. */
    public static String normalizeStreet(String street) {
        if (street == null) {
            return "";
        }
        String s = NON_ALNUM.matcher(street.toLowerCase(Locale.ROOT).replace('-', ' ')).replaceAll("");
        StringBuilder sb = new StringBuilder();
        for (String w : SPACES.split(s.trim())) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(EXPANSIONS.getOrDefault(w, w));
        }
        return sb.toString();
    }

    public static String normalizeHousenumber(String hn) {
        return hn == null ? "" : SPACES.matcher(hn.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    public static String normalizeUnit(String unit) {
        if (unit == null) {
            return "";
        }
        String u = unit.trim().toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"unit ", "apt ", "apt. ", "suite ", "ste ", "ste. ", "#"}) {
            if (u.startsWith(prefix)) {
                u = u.substring(prefix.length()).trim();
            }
        }
        return u;
    }

    /** housenumber|street|unit key, normalized. */
    public static String key(Map<String, String> tags) {
        return normalizeHousenumber(tags.get("addr:housenumber")) + '|' + normalizeStreet(tags.get("addr:street")) + '|'
                + normalizeUnit(tags.get("addr:unit"));
    }

    /**
     * The addr:* keys both sides carry with different values, ignoring case and surrounding
     * spaces. A key only one side has is not a conflict: a source adding addr:postcode or
     * addr:state to an address OSM already has is expected.
     */
    public static List<String> conflictingKeys(Map<String, String> source, Map<String, String> existing) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(source).entrySet()) {
            String other = existing.get(e.getKey());
            if (e.getKey().startsWith("addr:") && other != null && !other.trim().equalsIgnoreCase(e.getValue().trim())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Exact-duplicate key: every addr:* tag, verbatim. */
    public static String exactKey(Map<String, String> tags) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(tags).entrySet()) {
            if (e.getKey().startsWith("addr:")) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\u0001');
            }
        }
        return sb.toString();
    }

    /** How a source address relates to an existing one, or null if they are unrelated. */
    public static ExistingKind compare(Map<String, String> source, Map<String, String> existing) {
        if (exactKey(source).equals(exactKey(existing))) {
            return ExistingKind.IDENTICAL;
        }
        String hnA = normalizeHousenumber(source.get("addr:housenumber"));
        String hnB = normalizeHousenumber(existing.get("addr:housenumber"));
        if (hnA.isEmpty() || !hnA.equals(hnB)) {
            return null;
        }
        String stA = normalizeStreet(source.get("addr:street"));
        String stB = normalizeStreet(existing.get("addr:street"));
        String unA = normalizeUnit(source.get("addr:unit"));
        String unB = normalizeUnit(existing.get("addr:unit"));
        boolean streetSame = stA.equals(stB) || stA.isEmpty() || stB.isEmpty();
        boolean streetRawSame = String.valueOf(source.get("addr:street")).equalsIgnoreCase(String.valueOf(existing.get("addr:street")));
        if (streetSame && unA.equals(unB)) {
            // Same housenumber, street and unit; only city/postcode/etc differ, or the
            // street differs by abbreviation only.
            return streetRawSame ? ExistingKind.IDENTICAL : ExistingKind.STREET_VARIANT;
        }
        if (streetSame) {
            return ExistingKind.UNIT_DIFF;
        }
        if (unA.equals(unB)) {
            return ExistingKind.STREET_DIFF;
        }
        return null;
    }
}
