// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.model;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Review buckets. Order matters: it is the display order in the dialog, from
 * "apply in bulk" to "needs eyes".
 */
public enum Bucket {
    CLEAN("Clean", "One address, one clear primary building, nothing in the way. Safe to apply in bulk.", true),
    MULTI_ADDRESS_BUILDING("Multi-address building", "Several addresses land on one building. Each stays its own node, moved inside the building.", true),
    HINTED_POSITION("Placed by hint", "No usable OSM building, but a footprint in the hint layer shows where the building is. The node is placed on it; the footprint is not imported.", true),
    NO_BUILDING("No building", "No building in the address's cell. The node is copied as-is.", true),
    BUILDING_SPANS_CELLS("Building spans parcels", "One building covers several addressed parcels (townhouse row mapped as one outline). Nodes go inside the building.", true),
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
