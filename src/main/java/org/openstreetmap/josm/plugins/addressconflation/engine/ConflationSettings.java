// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.osm.OsmPrimitive;

/**
 * Tunables for one analysis run. Plain data so the engine can be tested
 * without JOSM preferences; the GUI copies preference values into this.
 */
public final class ConflationSettings {

    /** Default building=* values treated as outbuildings. */
    public static final List<String> DEFAULT_OUTBUILDINGS = Collections.unmodifiableList(Arrays.asList(
            "garage", "garages", "shed", "carport", "roof", "canopy", "outbuilding", "greenhouse", "barn",
            "hut", "cabin", "shelter", "kiosk", "storage_tank", "silo", "service"));

    /**
     * Address-to-cell snap distance and, in Voronoi mode, the furthest a
     * building may be from the address point. Metres.
     */
    public double matchDistanceMeters = 30.0;

    /**
     * Voronoi mode: each cell is trimmed to a circle around its address point whose radius
     * is this many times the local address spacing (median nearest-neighbour distance among
     * the nearby addresses). Keeps cells on the edge of a cluster, or beside a gap in it,
     * from reaching buildings far beyond a typical lot. 0 turns trimming off.
     */
    public double voronoiReachFactor = 2.0;

    /** Voronoi mode: the trimming circle is never smaller than this. Metres. */
    public double voronoiMinReachMeters = 15.0;

    /** runner-up score / primary score at or above this is "ambiguous". */
    public double ambiguityRatio = 0.75;

    /** Score multiplier for outbuildings. */
    public double outbuildingFactor = 0.1;

    /** Roofs and canopies are not buildings at all unless a POI tag says otherwise. */
    public static final List<String> ROOF_VALUES = Collections.unmodifiableList(Arrays.asList("roof", "canopy"));

    /** building=* values that say nothing about what the building is. */
    public static final List<String> GENERIC_VALUES = Collections.unmodifiableList(Arrays.asList("yes", "true", "1"));

    public static boolean isGeneric(String buildingValue) {
        return buildingValue == null || GENERIC_VALUES.contains(buildingValue);
    }
    public double roofFactor = 0.02;

    /**
     * For a multi-address cell: runner-up / primary score at or above this means the
     * addresses could belong to different buildings, so the proposal needs review.
     */
    public double multiAddressAmbiguityRatio = 0.25;

    /**
     * When OSM only offers an outbuilding, a hint footprint scoring more than this many
     * times the outbuilding's score takes the address instead.
     */
    public double hintOverOutbuildingRatio = 3.0;

    /**
     * When a cell also holds a building with an explicit value (house, detached, retail...),
     * plain building=yes is probably the barn or the shop, not the address carrier.
     */
    public double genericBesideExplicitFactor = 0.3;

    /**
     * A match further than this from the address point is sent to review even when it is
     * the only building in the parcel: on big rural parcels that is often a pump house.
     */
    public double farMatchMeters = 100.0;

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
        setOutbuildings(DEFAULT_OUTBUILDINGS);
    }

    public void setOutbuildings(List<String> values) {
        buildingWeights.clear();
        for (String v : values) {
            buildingWeights.put(v.trim(), outbuildingFactor);
        }
        for (String v : ROOF_VALUES) {
            buildingWeights.put(v, roofFactor);
        }
    }

    /** Keys that make a primitive a feature in its own right, whatever its building=* value. */
    public static final List<String> FEATURE_KEYS = Collections.unmodifiableList(Arrays.asList(
            "amenity", "shop", "office", "craft", "leisure", "tourism", "healthcare", "emergency", "public_transport"));

    public double weightFor(String buildingValue) {
        if (buildingValue == null) {
            return 1.0;
        }
        return buildingWeights.getOrDefault(buildingValue, 1.0);
    }

    /**
     * Weight for a building primitive. A gas-station canopy tagged
     * {@code building=roof} + {@code amenity=fuel} is the feature that carries the
     * address, so a POI tag cancels the outbuilding penalty.
     */
    public double weightFor(org.openstreetmap.josm.data.osm.OsmPrimitive prim) {
        for (String k : FEATURE_KEYS) {
            if (prim.hasKey(k) && !NON_ADDRESS_FEATURES.contains(k + "=" + prim.get(k))) {
                return 1.0;
            }
        }
        return weightFor(prim.get("building"));
    }

    /** Feature tags that sit on canopies and sheds without making them address carriers. */
    public static final java.util.Set<String> NON_ADDRESS_FEATURES = java.util.Set.of(
            "amenity=parking", "amenity=parking_space", "amenity=parking_entrance", "amenity=bicycle_parking",
            "amenity=shelter", "amenity=bench", "amenity=waste_basket", "amenity=waste_disposal", "amenity=recycling",
            "amenity=fountain", "amenity=grave_yard", "amenity=loading_dock", "leisure=picnic_table", "leisure=pitch",
            "leisure=swimming_pool", "leisure=playground", "public_transport=platform");

    /** Keys a bare address node may carry besides addr:* and still be only an address. */
    public static final List<String> ADDRESS_NODE_META_KEYS = Collections.unmodifiableList(Arrays.asList(
            "source", "note", "fixme", "FIXME", "check_date", "created_by", "survey:date", "import_uuid"));

    /**
     * True for a node that is nothing but an address: addr:* plus bookkeeping keys. A node
     * that also says building=*, amenity=*, name=... is a feature, and folding its address
     * into a building outline and deleting it would throw those tags away.
     */
    public static boolean isPlainAddressNode(OsmPrimitive p) {
        for (String k : p.keySet()) {
            if (k.startsWith("addr:")) {
                continue;
            }
            boolean meta = false;
            for (String m : ADDRESS_NODE_META_KEYS) {
                if (k.equals(m) || k.startsWith(m + ":")) {
                    meta = true;
                    break;
                }
            }
            if (!meta) {
                return false;
            }
        }
        return true;
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
