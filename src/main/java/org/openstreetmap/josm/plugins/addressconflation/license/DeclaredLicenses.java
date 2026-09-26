// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.license;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Classifies the {@code license} an OpenAddresses layer declares: an object with
 * {@code text}, {@code url}, {@code attribution}, {@code share-alike}, or a bare string.
 * Rules follow research/license-compatibility.md and are applied in order.
 */
public final class DeclaredLicenses {
    private static final Pattern RLIS = Pattern.compile("rlis open database");
    private static final Pattern NON_COMMERCIAL = Pattern.compile("by-nc|non-?commercial|not for commercial");
    private static final Pattern SHARE_ALIKE = Pattern.compile("by-sa|share-?alike");
    private static final Pattern ODBL = Pattern.compile("\\bodbl\\b|opendatacommons\\.org/licenses/odbl");
    private static final Pattern PUBLIC_DOMAIN = Pattern.compile(
            "cc ?0|publicdomain/zero|public-domain/#cc0|pddl|public domain|^pd\\b|\\bpd \\+|no restrictions|without attribution");
    private static final Pattern CC_BY = Pattern.compile("cc by|licenses/by/");
    private static final Pattern INDEMNIFICATION = Pattern.compile("indemn");

    private DeclaredLicenses() {
    }

    /** Assessment of a declared licence; {@code null} or JSON null means nothing is declared. */
    public static LicenseAssessment classify(JsonValue declared) {
        String text = "";
        String url = null;
        Boolean attribution = null;
        Boolean shareAlike = null;
        if (declared instanceof JsonString) {
            String s = ((JsonString) declared).getString().trim();
            if (s.startsWith("http://") || s.startsWith("https://")) {
                url = s;
            } else {
                text = s;
            }
        } else if (declared instanceof JsonObject) {
            JsonObject o = (JsonObject) declared;
            text = string(o, "text");
            url = o.containsKey("url") && o.get("url") instanceof JsonString ? o.getString("url") : null;
            attribution = bool(o, "attribution");
            shareAlike = bool(o, "share-alike");
        } else {
            return new LicenseAssessment(LicenseStatus.UNKNOWN, tr("The source declares no licence"), null, null);
        }
        String shown = describe(text, url);
        String t = (text + " " + (url == null ? "" : url)).toLowerCase(Locale.ROOT).trim();
        LicenseStatus status;
        if (t.isEmpty() || "unknown".equals(t)) {
            status = attribution == Boolean.TRUE ? LicenseStatus.NEEDS_WAIVER : LicenseStatus.UNKNOWN;
        } else if (RLIS.matcher(t).find()) {
            status = LicenseStatus.COMPATIBLE;
        } else if (NON_COMMERCIAL.matcher(t).find()) {
            status = LicenseStatus.NOT_COMPATIBLE;
        } else if (SHARE_ALIKE.matcher(t).find() || (shareAlike == Boolean.TRUE && !ODBL.matcher(t).find())) {
            status = LicenseStatus.NOT_COMPATIBLE;
        } else if (ODBL.matcher(t).find()) {
            status = LicenseStatus.COMPATIBLE;
        } else if (PUBLIC_DOMAIN.matcher(t).find() && !INDEMNIFICATION.matcher(t).find()) {
            status = LicenseStatus.COMPATIBLE;
        } else if (CC_BY.matcher(t).find()) {
            status = LicenseStatus.NEEDS_WAIVER;
        } else if (INDEMNIFICATION.matcher(t).find()) {
            status = LicenseStatus.CHECK_TERMS;
        } else if (attribution == Boolean.TRUE) {
            status = LicenseStatus.NEEDS_WAIVER;
        } else {
            status = LicenseStatus.CHECK_TERMS;
        }
        String basis = shown == null ? tr("The source declares attribution is required") : tr("Declared: {0}", shown);
        return new LicenseAssessment(status, basis, shown, url);
    }

    private static String describe(String text, String url) {
        String t = text == null ? "" : text.trim();
        if (t.length() > 120) {
            t = t.substring(0, 117) + "...";
        }
        if (!t.isEmpty()) {
            return t;
        }
        return url;
    }

    private static String string(JsonObject o, String key) {
        return o.containsKey(key) && o.get(key) instanceof JsonString ? o.getString(key) : "";
    }

    private static Boolean bool(JsonObject o, String key) {
        JsonValue v = o.get(key);
        if (v == JsonValue.TRUE) {
            return Boolean.TRUE;
        }
        return v == JsonValue.FALSE ? Boolean.FALSE : null;
    }
}
