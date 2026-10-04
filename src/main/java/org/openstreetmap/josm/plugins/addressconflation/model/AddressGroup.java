// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;

/**
 * One source address. Holds the node that will be used plus any absolute
 * duplicates (identical addr:* tags) that will be deleted alongside it.
 */
public final class AddressGroup {
    private final Node primary;
    private final List<Node> duplicates = new ArrayList<>();
    private final Map<String, String> tags;
    private final String key;
    private Cell cell;
    private boolean snappedToCell;
    private double distanceToCell;
    private boolean crossCellDuplicate;

    public AddressGroup(Node primary, Map<String, String> addrTags, String key) {
        this.primary = primary;
        this.tags = Collections.unmodifiableMap(new TreeMap<>(addrTags));
        this.key = key;
    }

    public Node getPrimary() {
        return primary;
    }

    public List<Node> getDuplicates() {
        return duplicates;
    }

    /** Primary first, then duplicates. */
    public List<Node> getAllNodes() {
        List<Node> all = new ArrayList<>(duplicates.size() + 1);
        all.add(primary);
        all.addAll(duplicates);
        return all;
    }

    /** addr:* tags of the primary node. */
    public Map<String, String> getTags() {
        return tags;
    }

    /** Normalized housenumber|street|unit key. */
    public String getKey() {
        return key;
    }

    public LatLon getPosition() {
        return primary.getCoor();
    }

    public Cell getCell() {
        return cell;
    }

    public void setCell(Cell cell, boolean snapped, double distance) {
        this.cell = cell;
        this.snappedToCell = snapped;
        this.distanceToCell = distance;
    }

    public boolean isSnappedToCell() {
        return snappedToCell;
    }

    public double getDistanceToCell() {
        return distanceToCell;
    }

    public boolean isCrossCellDuplicate() {
        return crossCellDuplicate;
    }

    public void setCrossCellDuplicate(boolean crossCellDuplicate) {
        this.crossCellDuplicate = crossCellDuplicate;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(tags.getOrDefault("addr:housenumber", "?"));
        if (tags.containsKey("addr:unit")) {
            sb.append(" #").append(tags.get("addr:unit"));
        }
        sb.append(' ').append(tags.getOrDefault("addr:street", ""));
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return "AddressGroup[" + describe() + ", node " + primary.getUniqueId() + "]";
    }
}
