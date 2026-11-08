// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.engine.AddressNormalizer;

/**
 * Street-name helpers shared by the address validators: which ways are
 * streets, which tags hold their names, and the forms names are compared in.
 */
final class StreetNames {
    /**
     * highway=* values that are streets an address can be on. Footways, cycleways,
     * paths and corridors are left out on purpose: a trail that shares a street's
     * name must not hide an address that is on the wrong street.
     */
    static final Set<String> ROAD_HIGHWAYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
            "living_street", "service", "pedestrian", "track", "busway", "road")));

    private static final Set<String> NAME_KEYS = new HashSet<>(Arrays.asList(
            "name", "alt_name", "official_name", "short_name", "loc_name", "old_name", "name_1", "name_2"));

    /** name:* sub-keys that hold metadata about the name rather than a name. */
    private static final String[] NAME_METADATA_PARTS = {"etymology", "pronunciation", "wikidata", "wikipedia"};

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private StreetNames() {
    }

    static boolean isRoad(Way w) {
        String hw = w.get("highway");
        return hw != null && w.isUsable() && ROAD_HIGHWAYS.contains(hw);
    }

    static boolean isNameKey(String key) {
        if (NAME_KEYS.contains(key)) {
            return true;
        }
        if (!key.startsWith("name:")) {
            return false;
        }
        for (String part : NAME_METADATA_PARTS) {
            if (key.contains(part)) {
                return false;
            }
        }
        return true;
    }

    static boolean isStreetRelation(OsmPrimitive r) {
        String type = r.get("type");
        return "associatedStreet".equals(type) || "street".equals(type);
    }

    /**
     * Every name of a road, as {@link #splitValues} gives them: its own name tags
     * plus the name of any street relation it belongs to.
     */
    static List<String> roadNames(Way w) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> tag : w.getKeys().entrySet()) {
            if (isNameKey(tag.getKey())) {
                out.addAll(splitValues(tag.getValue()));
            }
        }
        for (OsmPrimitive ref : w.getReferrers()) {
            if (ref instanceof Relation && ref.isUsable() && isStreetRelation(ref)) {
                out.addAll(splitValues(ref.get("name")));
            }
        }
        return new ArrayList<>(out);
    }

    /** The comparison {@link #key keys} of every name of a road. */
    static Set<String> roadKeys(Way w) {
        Set<String> out = new HashSet<>();
        for (String n : roadNames(w)) {
            out.add(key(n));
        }
        return out;
    }

    /** Values of a ';'-separated tag in NFC, trimmed and single-spaced, without empties and duplicates. */
    static List<String> splitValues(String value) {
        if (value == null) {
            return Collections.emptyList();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : value.split(";")) {
            String t = SPACES.matcher(Normalizer.normalize(s, Normalizer.Form.NFC).trim()).replaceAll(" ");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    /** NFC, trimmed, single-spaced, lower case. */
    static String canonical(String s) {
        return SPACES.matcher(Normalizer.normalize(s, Normalizer.Form.NFC).trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Canonical form with combining marks removed ("peña" gives "pena"). */
    static String fold(String canonical) {
        return Normalizer.normalize(MARKS.matcher(Normalizer.normalize(canonical, Normalizer.Form.NFD)).replaceAll(""),
                Normalizer.Form.NFC);
    }

    /**
     * Abbreviation-expanded form, or null when the name has letters or digits
     * outside ASCII. The normaliser drops those, so two different names in
     * another script would otherwise compare equal.
     */
    static String expand(String folded) {
        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            if (cp > 127 && Character.isLetterOrDigit(cp)) {
                return null;
            }
            i += Character.charCount(cp);
        }
        String e = AddressNormalizer.normalizeStreet(folded);
        return e.isEmpty() ? null : e;
    }

    /**
     * The form two street names are compared in when spelling does not matter:
     * abbreviations expanded, case and accents set aside. "W Olive Ave" and
     * "West Olive Avenue" share a key, and names that {@link #canonical} calls
     * equal always do.
     */
    static String key(String name) {
        String folded = fold(canonical(name));
        String expanded = expand(folded);
        return expanded != null ? expanded : folded;
    }

    /** The key of a single-valued addr:street, or null when it is missing, blank or a ';' list. */
    static String streetKey(OsmPrimitive p) {
        List<String> parts = splitValues(p.get("addr:street"));
        return parts.size() == 1 ? key(parts.get(0)) : null;
    }

    /** The first value of addr:street as {@link #splitValues} gives it, or "" when there is none. */
    static String street(OsmPrimitive p) {
        List<String> parts = splitValues(p.get("addr:street"));
        return parts.isEmpty() ? "" : parts.get(0);
    }
}
