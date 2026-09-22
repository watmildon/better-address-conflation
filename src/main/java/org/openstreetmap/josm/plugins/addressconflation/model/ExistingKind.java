// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.model;

/** How a source address relates to an address already in the edit layer. */
public enum ExistingKind {
    /** Every addr:* tag is identical. */
    IDENTICAL,
    /** Same housenumber and street, only addr:unit differs (or is missing on one side). */
    UNIT_DIFF,
    /** Same housenumber, street differs only by abbreviation, case or directional. */
    STREET_VARIANT,
    /** Same housenumber and unit but a genuinely different street name. */
    STREET_DIFF,
    /** The target building already carries a different address. */
    OTHER_ADDRESS_ON_BUILDING
}
