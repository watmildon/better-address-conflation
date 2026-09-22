// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.openstreetmap.josm.plugins.addressconflation.engine.AddressNormalizer;

/**
 * Turns county-style street names ("N 56TH DR", "W OLIVE AVE") into OSM
 * style ("North 56th Drive", "West Olive Avenue"). Suffixes are only expanded
 * in suffix position, directionals only at either end, so "ST JOHN RD" gives
 * "Saint John Road" and "MAIN ST" gives "Main Street".
 */
public final class StreetExpander {
    private static final Set<String> DIRECTIONALS = Set.of("n", "s", "e", "w", "ne", "nw", "se", "sw");
    private static final Set<String> PREFIX_WORDS = Set.of("st", "mt", "ft");
    private static final Pattern ORDINAL = Pattern.compile("(\\d+)(ST|ND|RD|TH)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private StreetExpander() {
    }

    public static String expand(String raw) {
        if (raw == null) {
            return null;
        }
        String[] words = SPACES.split(raw.trim());
        if (words.length == 0 || words[0].isEmpty()) {
            return raw;
        }
        int n = words.length;
        int last = n - 1;
        boolean lastIsDirectional = n > 1 && DIRECTIONALS.contains(words[last].toLowerCase(Locale.ROOT));
        int suffixIndex = lastIsDirectional ? last - 1 : last;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String w = words[i];
            String lw = w.toLowerCase(Locale.ROOT);
            String out;
            if ((i == 0 || i == last) && DIRECTIONALS.contains(lw) && n > 1) {
                out = capitalize(AddressNormalizer.expansion(lw));
            } else if (i == 0 && PREFIX_WORDS.contains(lw) && n > 1) {
                out = "st".equals(lw) ? "Saint" : capitalize(AddressNormalizer.expansion(lw));
            } else if (i == suffixIndex && i > 0 && AddressNormalizer.expansion(lw) != null && !DIRECTIONALS.contains(lw)) {
                out = capitalize(AddressNormalizer.expansion(lw));
            } else if (ORDINAL.matcher(w).matches()) {
                out = w.toLowerCase(Locale.ROOT);
            } else {
                out = capitalize(lw);
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(out);
        }
        return sb.toString();
    }

    /** Title-case a single word, keeping Mc/Mac and apostrophes sensible. */
    static String capitalize(String lw) {
        if (lw == null || lw.isEmpty()) {
            return lw;
        }
        StringBuilder sb = new StringBuilder(lw.length());
        boolean up = true;
        for (int i = 0; i < lw.length(); i++) {
            char c = lw.charAt(i);
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == '-' || c == '\'' || c == '.';
        }
        if (lw.startsWith("mc") && lw.length() > 2) {
            sb.setCharAt(2, Character.toUpperCase(lw.charAt(2)));
        }
        return sb.toString();
    }
}
