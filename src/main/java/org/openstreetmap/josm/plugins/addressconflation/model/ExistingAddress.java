// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.model;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.openstreetmap.josm.data.osm.OsmPrimitive;

/** An address already present in the edit layer (building, node, POI, landuse...). */
public final class ExistingAddress {
    private final OsmPrimitive primitive;
    private final Map<String, String> tags;

    public ExistingAddress(OsmPrimitive primitive) {
        this.primitive = primitive;
        this.tags = new TreeMap<>(primitive.getKeys().entrySet().stream()
                .filter(e -> e.getKey().startsWith("addr:"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public OsmPrimitive getPrimitive() {
        return primitive;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public boolean isBuilding() {
        return primitive.hasKey("building");
    }
}
