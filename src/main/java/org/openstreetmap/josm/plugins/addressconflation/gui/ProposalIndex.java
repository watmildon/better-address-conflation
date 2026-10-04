// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.gui;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.plugins.addressconflation.model.BuildingCandidate;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Finds the proposals behind what the mapper selects on the map: address nodes in the
 * address layer, footprints in the hint layer, parcels in the parcel layer. A footprint
 * only finds proposals that would place their address on it, so applying what it selects
 * never ignores the footprint. The edit layer's buildings are left out on purpose:
 * selecting one of them is how the mapper picks the building for an ambiguous proposal.
 */
final class ProposalIndex {
    private final DataSet addresses;
    private final DataSet hints;
    private final DataSet parcels;
    private final Map<OsmPrimitive, Set<Proposal>> byAddress = new HashMap<>();
    private final Map<OsmPrimitive, Set<Proposal>> byHint = new HashMap<>();
    private final Map<OsmPrimitive, Set<Proposal>> byParcel = new HashMap<>();

    /**
     * @param addresses the address layer's dataset
     * @param hints the hint layer's dataset, or null
     * @param parcels the parcel layer's dataset, or null for Voronoi cells
     */
    ProposalIndex(List<Proposal> proposals, DataSet addresses, DataSet hints, DataSet parcels) {
        this.addresses = addresses;
        this.hints = hints;
        this.parcels = parcels;
        for (Proposal p : proposals) {
            for (Node n : p.getSourceNodes()) {
                put(byAddress, n, p);
            }
            BuildingCandidate target = p.getTarget();
            if (target != null && target.isHint()) {
                put(byHint, target.getPrimitive(), p);
            }
            Cell cell = p.getCell();
            if (cell != null && cell.getSourcePrimitive() != null) {
                put(byParcel, cell.getSourcePrimitive(), p);
            }
        }
    }

    private static void put(Map<OsmPrimitive, Set<Proposal>> map, OsmPrimitive prim, Proposal p) {
        map.computeIfAbsent(prim, k -> new LinkedHashSet<>()).add(p);
    }

    /** Proposals behind the primitives selected in a dataset; empty when the dataset plays no part. */
    Set<Proposal> find(DataSet ds, Collection<? extends OsmPrimitive> selected) {
        Set<Proposal> out = new LinkedHashSet<>();
        if (ds == null) {
            return out;
        }
        for (OsmPrimitive prim : selected) {
            if (ds == addresses) {
                addAll(out, byAddress.get(prim));
            }
            if (ds == hints) {
                addAll(out, byHint.get(prim));
            }
            if (ds == parcels) {
                addAll(out, byParcel.get(prim));
                // A multipolygon parcel gets selected by clicking one of its ways.
                for (OsmPrimitive r : prim.getReferrers()) {
                    addAll(out, byParcel.get(r));
                }
            }
        }
        return out;
    }

    private static void addAll(Set<Proposal> out, Set<Proposal> found) {
        if (found != null) {
            out.addAll(found);
        }
    }
}
