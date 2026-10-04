// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.model;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Review buckets, named for what the mapper has to do rather than how the match was
 * found (OSM building or hint footprint). Order matters: it is the display order in the
 * dialog, from "apply in bulk" to "needs eyes".
 */
public enum Bucket {
    CLEAN("Clean", "One address, one clear building. An OSM building gets the address tags; on a hint footprint the address becomes a node in its middle. Safe to apply in bulk.", true),
    MULTI_ADDRESS_BUILDING("Multi-address building", "Several addresses land on one OSM building. Each stays its own node, moved inside the building.", true),
    NO_BUILDING("No building", "No building in the address's cell. The node is copied as-is.", true),
    REVIEW("Check, then apply", "Probably right, but look first: a parcel line splits the building, one outline covers several addressed parcels, or several addresses land on one hint footprint. Apply rows one at a time.", false),
    AMBIGUOUS_BUILDING("Ambiguous building", "The runner-up building is close in size to the primary. Pick one.", false),
    EXISTING_ADDRESS("Existing address", "The cell already has an address that matches or conflicts.", false),
    DUPLICATE("Duplicate across cells", "The same address appears in more than one cell.", false),
    OUTSIDE_CELLS("Outside parcels", "The address point is in no parcel and none is within the match distance.", false);

    private final String label;
    private final String description;
    private final boolean bulkApplicable;

    Bucket(String label, String description, boolean bulkApplicable) {
        this.label = label;
        this.description = description;
        this.bulkApplicable = bulkApplicable;
    }

    public String getLabel() {
        return tr(label);
    }

    public String getDescription() {
        return tr(description);
    }

    /** True when "apply whole bucket" is a sensible thing to offer. */
    public boolean isBulkApplicable() {
        return bulkApplicable;
    }
}
