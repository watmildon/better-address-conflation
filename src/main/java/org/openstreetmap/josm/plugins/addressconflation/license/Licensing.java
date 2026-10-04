// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.license;

import static org.openstreetmap.josm.tools.I18n.tr;

import jakarta.json.JsonValue;

/**
 * One place that decides a source's licence status. Precedence: a recorded permission for
 * this source and server, then a state law that overrides declared licences, then the
 * declared licence, then a state law that only fills in when nothing is declared.
 */
public final class Licensing {
    /** The National Address Database as hosted by Esri. */
    public static final LicenseAssessment NAD = new LicenseAssessment(LicenseStatus.COMPATIBLE,
            tr("Public domain (U.S. Department of Transportation and its partners)"), "Public Domain",
            "https://www.transportation.gov/gis/national-address-database");
    /** Microsoft's US building footprints. */
    public static final LicenseAssessment MICROSOFT_BUILDINGS = new LicenseAssessment(LicenseStatus.COMPATIBLE,
            tr("Open Database License (ODbL), the same licence as OpenStreetMap"), "ODbL",
            "https://github.com/microsoft/USBuildingFootprints");

    private Licensing() {
    }

    /** A source the mapper added: their word that it may be used. */
    public static LicenseAssessment userProvided() {
        return new LicenseAssessment(LicenseStatus.USER_PROVIDED, tr("Added by you"), null, null);
    }

    /**
     * @param sourceId OpenAddresses source id such as {@code us/ca/placer}
     * @param layer    {@code addresses}, {@code parcels} or {@code buildings}
     * @param dataUrl  the service the layer is read from
     * @param declared the layer's {@code license} value from the source definition, or null
     */
    public static LicenseAssessment assess(String sourceId, String layer, String dataUrl, JsonValue declared) {
        return assess(Clearances.bundled(), sourceId, layer, dataUrl, declared);
    }

    static LicenseAssessment assess(Clearances clearances, String sourceId, String layer, String dataUrl, JsonValue declared) {
        LicenseAssessment fromDeclared = DeclaredLicenses.classify(declared);
        Clearances.Entry permission = clearances.permission(sourceId, layer, dataUrl);
        if (permission != null) {
            return permission.assessment(fromDeclared.getDeclared());
        }
        Clearances.Entry law = clearances.stateLaw(sourceId, layer);
        if (law != null && (law.overridesDeclared || fromDeclared.getStatus() == LicenseStatus.UNKNOWN)) {
            return law.assessment(fromDeclared.getDeclared());
        }
        return fromDeclared;
    }
}
