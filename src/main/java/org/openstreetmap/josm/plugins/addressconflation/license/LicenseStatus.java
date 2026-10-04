// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.license;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;

/**
 * Whether a source's data may be used for OpenStreetMap, following the OSMF Licensing
 * Working Group's compatibility guidance. The plugin informs; it never blocks.
 */
public enum LicenseStatus {
    /** Public domain, CC0, PDDL, ODbL, or a recorded permission. */
    COMPATIBLE(new Color(0x2E7D32)),
    /** CC BY or other attribution terms: usable with a signed waiver or permission. */
    NEEDS_WAIVER(new Color(0xB26A00)),
    /** Share-alike, non-commercial, no-derivatives terms. */
    NOT_COMPATIBLE(new Color(0xC62828)),
    /** Terms exist (a terms page, an indemnification clause) and need reading. */
    CHECK_TERMS(new Color(0xB26A00)),
    /** Nothing is known about the licence. */
    UNKNOWN(new Color(0x616161));

    private final Color color;

    LicenseStatus(Color color) {
        this.color = color;
    }

    /** Colour for the badge; always shown together with {@link #label()}. */
    public Color color() {
        return color;
    }

    public String label() {
        switch (this) {
        case COMPATIBLE:
            return tr("Compatible");
        case NEEDS_WAIVER:
            return tr("Needs waiver");
        case NOT_COMPATIBLE:
            return tr("Not compatible");
        case CHECK_TERMS:
            return tr("Check terms");
        default:
            return tr("Unknown");
        }
    }

    /** What the status means for a mapper, one sentence. */
    public String explanation() {
        switch (this) {
        case COMPATIBLE:
            return tr("Can be used for OpenStreetMap.");
        case NEEDS_WAIVER:
            return tr("Usable for OpenStreetMap only with a signed waiver or permission from the data owner.");
        case NOT_COMPATIBLE:
            return tr("The licence is not compatible with OpenStreetMap; permission from the data owner is needed.");
        case CHECK_TERMS:
            return tr("The source has terms of use that need reading before the data is used for OpenStreetMap.");
        default:
            return tr("No licence is known. Without documented terms the data should not be used for OpenStreetMap.");
        }
    }
}
