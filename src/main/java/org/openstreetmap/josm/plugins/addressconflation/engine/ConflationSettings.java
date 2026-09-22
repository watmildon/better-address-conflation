// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tunables for one analysis run. Plain data so the engine can be tested
 * without JOSM preferences; the GUI copies preference values into this.
 */
public final class ConflationSettings {

    /** Default building=* values treated as outbuildings. */
    public static final List<String> DEFAULT_OUTBUILDINGS = Collections.unmodifiableList(Arrays.asList(
            "garage", "garages", "shed", "carport", "roof", "outbuilding", "greenhouse", "barn",
            "hut", "cabin", "shelter", "kiosk", "storage_tank", "silo", "service"));

    /**
     * Address-to-cell snap distance and, in Voronoi mode, the furthest a
     * building may be from the address point. Metres.
     */
    public double matchDistanceMeters = 30.0;

    /** runner-up score / primary score at or above this is "ambiguous". */
    public double ambiguityRatio = 0.75;

    /** Score multiplier for outbuildings. */
    public double outbuildingFactor = 0.1;

    /** Score multiplier for a building whose footprint contains the address point. */
    public double containsFactor = 4.0;

    /** Score multiplier for buildings already carrying a different address. */
    public double otherAddressFactor = 0.2;

    /** Minimum fraction of a building inside a cell for it to count as a candidate there. */
    public double minShareInCell = 0.10;

    /** Below this fraction of the building in the cell, a primary counts as spanning cells. */
    public double spanningShare = 0.5;

    /** Delete the source nodes from the address layer after applying. */
    public boolean deleteSourceNodes = true;

    /** Tag key prefixes copied from the address node. */
    public List<String> copyKeyPrefixes = Collections.singletonList("addr:");

    private final Map<String, Double> buildingWeights = new HashMap<>();

    public ConflationSettings() {
        for (String v : DEFAULT_OUTBUILDINGS) {
            buildingWeights.put(v, outbuildingFactor);
        }
    }

    public void setOutbuildings(List<String> values) {
        buildingWeights.clear();
        for (String v : values) {
            buildingWeights.put(v.trim(), outbuildingFactor);
        }
    }

    public double weightFor(String buildingValue) {
        if (buildingValue == null) {
            return 1.0;
        }
        return buildingWeights.getOrDefault(buildingValue, 1.0);
    }

    public boolean shouldCopyKey(String key) {
        for (String p : copyKeyPrefixes) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
