// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.model;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.data.osm.OsmPrimitive;

/** A building in the edit layer, with its geometry in local metres. */
public final class BuildingCandidate {
    private final OsmPrimitive primitive;
    private final Geometry geometry;
    private final double area;
    private final double tagFactor;
    private final Map<String, String> addrTags;
    private final boolean hint;

    public BuildingCandidate(OsmPrimitive primitive, Geometry geometry, double tagFactor) {
        this(primitive, geometry, tagFactor, false);
    }

    /**
     * @param hint true when the footprint comes from a hint layer (MapWithAI, county or
     *             Microsoft footprints) that is read for position only and never edited
     */
    public BuildingCandidate(OsmPrimitive primitive, Geometry geometry, double tagFactor, boolean hint) {
        this.primitive = primitive;
        this.geometry = geometry;
        this.area = geometry.getArea();
        this.tagFactor = tagFactor;
        this.hint = hint;
        this.addrTags = new TreeMap<>(primitive.getKeys().entrySet().stream()
                .filter(e -> e.getKey().startsWith("addr:"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public OsmPrimitive getPrimitive() {
        return primitive;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    /** Footprint area in square metres. */
    public double getArea() {
        return area;
    }

    /** Multiplier from the building=* value (garages score low). */
    public double getTagFactor() {
        return tagFactor;
    }

    public boolean hasAddress() {
        return addrTags.containsKey("addr:housenumber");
    }

    public Map<String, String> getAddrTags() {
        return addrTags;
    }

    public String getBuildingValue() {
        return primitive.get("building");
    }

    /** True for a building mapped as a node; its geometry is a small circle around it. */
    public boolean isNode() {
        return primitive instanceof org.openstreetmap.josm.data.osm.Node;
    }

    /** True when this footprint is a hint, not an OSM building to tag. */
    public boolean isHint() {
        return hint;
    }

    @Override
    public String toString() {
        return (hint ? "Hint[" : "Building[") + primitive.getType() + " " + primitive.getUniqueId() + " " + getBuildingValue()
                + " " + Math.round(area) + "m2]";
    }
}
