// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.apply;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.BuildingCandidate;
import org.openstreetmap.josm.plugins.addressconflation.model.ExistingKind;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Turns a proposal into JOSM commands. The edit layer gets one command, the
 * address layer (if different) gets one for the deletions; JOSM cannot put
 * commands for two datasets in a single SequenceCommand.
 */
public final class ProposalApplier {

    /** Commands for the two layers; either may be null. */
    public static final class Applied {
        private final Command targetCommand;
        private final Command sourceCommand;

        Applied(Command targetCommand, Command sourceCommand) {
            this.targetCommand = targetCommand;
            this.sourceCommand = sourceCommand;
        }

        public Command getTargetCommand() {
            return targetCommand;
        }

        public Command getSourceCommand() {
            return sourceCommand;
        }

        public boolean isEmpty() {
            return targetCommand == null && sourceCommand == null;
        }
    }

    private ProposalApplier() {
    }

    /** True when the proposal has a sensible automatic action. */
    public static boolean isApplicable(Proposal p) {
        switch (p.getBucket()) {
        case CLEAN:
        case MULTI_ADDRESS_BUILDING:
        case NO_BUILDING:
        case REVIEW:
        case AMBIGUOUS_BUILDING:
            return true;
        case EXISTING_ADDRESS:
            return p.getExistingKind() == ExistingKind.IDENTICAL;
        default:
            return false;
        }
    }

    /**
     * Build commands for a proposal.
     *
     * @param p the proposal
     * @param targetDs edit layer dataset
     * @param sourceDs address layer dataset (may be the same as targetDs)
     * @param proj projection used by the analysis (for placing nodes inside buildings)
     * @param settings tunables
     * @return commands, or null when the proposal is not applicable
     */
    public static Applied build(Proposal p, DataSet targetDs, DataSet sourceDs, LocalProjection proj, ConflationSettings settings) {
        return build(p, null, targetDs, sourceDs, proj, settings);
    }

    /**
     * Build commands for a proposal, sending it to a building the mapper picked instead of
     * the proposal's own target.
     *
     * @param pick one of the proposal's candidates, or null to use its target; a proposal
     *             that {@link Proposal#requiresPick() requires a pick} gives null without one
     */
    public static Applied build(Proposal p, BuildingCandidate pick, DataSet targetDs, DataSet sourceDs, LocalProjection proj,
            ConflationSettings settings) {
        if (!isApplicable(p)) {
            return null;
        }
        BuildingCandidate target = pick != null ? pick : p.getTarget();
        if (target == null && p.requiresPick()) {
            return null;
        }
        List<Command> targetCmds = new ArrayList<>();
        List<Node> toDelete = new ArrayList<>();
        Bucket bucket = p.getBucket();
        boolean sameLayer = sourceDs == targetDs;
        boolean hinted = target != null && target.isHint();

        if (bucket == Bucket.EXISTING_ADDRESS) {
            // Identical address already mapped: the source node is redundant.
            toDelete.addAll(p.getSourceNodes());
        } else if (bucket == Bucket.CLEAN && p.getExistingKind() == ExistingKind.IDENTICAL && !p.getExisting().isEmpty()) {
            // Already mapped without conflicts: add only what OSM lacks (postcode, state...),
            // never touch a value it already has, and wherever the mapper clicked, keep it on
            // the feature that has it.
            OsmPrimitive existing = p.getExisting().get(0).getPrimitive();
            Map<String, String> added = new TreeMap<>();
            for (Map.Entry<String, String> e : copyTags(p.getAddresses().get(0), settings).entrySet()) {
                if (!existing.hasKey(e.getKey())) {
                    added.put(e.getKey(), e.getValue());
                }
            }
            if (!added.isEmpty()) {
                targetCmds.add(new ChangePropertyCommand(Collections.singleton(existing), added));
            }
            toDelete.addAll(p.getSourceNodes());
        } else if (bucket == Bucket.NO_BUILDING) {
            for (AddressGroup g : p.getAddresses()) {
                if (sameLayer) {
                    // The node already lives in the edit layer at the right place; nothing to move.
                    toDelete.addAll(g.getDuplicates());
                } else {
                    targetCmds.add(new AddCommand(targetDs, copyNode(g, g.getPrimary().getCoor(), settings)));
                    toDelete.addAll(g.getAllNodes());
                }
            }
        } else if (p.getAddresses().size() == 1 && !hinted) {
            AddressGroup g = p.getAddresses().get(0);
            Map<String, String> tags = copyTags(g, settings);
            targetCmds.add(new ChangePropertyCommand(Collections.singleton(target.getPrimitive()), tags));
            toDelete.addAll(g.getAllNodes());
        } else {
            // Several addresses on one building, a building spanning parcels, or a hinted
            // footprint: every address becomes (or stays) a node inside the footprint.
            Geometry building = target.getGeometry();
            // One address on a hint footprint stands for the whole building: put it in the
            // middle, even when the point already falls somewhere inside the footprint.
            boolean toCenter = hinted && p.getAddresses().size() == 1;
            List<Coordinate> placed = new ArrayList<>();
            for (AddressGroup g : p.getAddresses()) {
                Coordinate c = toCenter ? center(building) : placeInside(proj.toXY(g.getPosition()), building, placed);
                placed.add(c);
                if (sameLayer) {
                    // Cleaning up existing nodes: move them, keep their history.
                    Node n = g.getPrimary();
                    org.openstreetmap.josm.data.coor.LatLon to = proj.toLatLon(c);
                    if (n.getCoor().greatCircleDistance(to) > 0.05) {
                        targetCmds.add(new MoveCommand(Collections.singleton(n), n.getEastNorth(),
                                ProjectionRegistry.getProjection().latlon2eastNorth(to)));
                    }
                    toDelete.addAll(g.getDuplicates());
                } else {
                    targetCmds.add(new AddCommand(targetDs, copyNode(g, proj.toLatLon(c), settings)));
                    toDelete.addAll(g.getAllNodes());
                }
            }
        }

        Command deleteCmd = null;
        if (settings.deleteSourceNodes && !toDelete.isEmpty()) {
            deleteCmd = DeleteCommand.delete(toDelete, false, true);
        }

        String name = tr("Conflate address: {0}", p.describe());
        if (sourceDs == targetDs) {
            if (deleteCmd != null) {
                targetCmds.add(deleteCmd);
            }
            return new Applied(sequence(name, targetCmds), null);
        }
        return new Applied(sequence(name, targetCmds), deleteCmd);
    }

    private static Command sequence(String name, List<Command> cmds) {
        if (cmds.isEmpty()) {
            return null;
        }
        if (cmds.size() == 1) {
            return cmds.get(0);
        }
        return new SequenceCommand(name, cmds);
    }

    private static Map<String, String> copyTags(AddressGroup g, ConflationSettings settings) {
        Map<String, String> tags = new TreeMap<>();
        for (Map.Entry<String, String> e : g.getPrimary().getKeys().entrySet()) {
            if (settings.shouldCopyKey(e.getKey())) {
                tags.put(e.getKey(), e.getValue());
            }
        }
        return tags;
    }

    private static Node copyNode(AddressGroup g, org.openstreetmap.josm.data.coor.LatLon at, ConflationSettings settings) {
        Node n = new Node(at);
        n.setKeys(copyTags(g, settings));
        return n;
    }

    /** The footprint's centroid, or an interior point when the centroid falls outside (an L-shaped house). */
    static Coordinate center(Geometry building) {
        Point centroid = building.getCentroid();
        if (!centroid.isEmpty() && building.contains(centroid)) {
            return centroid.getCoordinate();
        }
        return building.getInteriorPoint().getCoordinate();
    }

    /**
     * Keep the address where it is if that is inside the building; otherwise
     * find a spot inside the building at least 3 m from nodes already placed.
     */
    static Coordinate placeInside(Coordinate original, Geometry building, List<Coordinate> taken) {
        Point p = OsmGeometry.factory().createPoint(original);
        if (building.contains(p) && farEnough(original, taken)) {
            return original;
        }
        Coordinate center = building.getInteriorPoint().getCoordinate();
        if (farEnough(center, taken)) {
            return center;
        }
        double step = 3.0;
        for (int ring = 1; ring < 40; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
                        continue;
                    }
                    Coordinate c = new Coordinate(center.x + dx * step, center.y + dy * step);
                    if (building.contains(OsmGeometry.factory().createPoint(c)) && farEnough(c, taken)) {
                        return c;
                    }
                }
            }
        }
        return center;
    }

    private static boolean farEnough(Coordinate c, List<Coordinate> taken) {
        for (Coordinate t : taken) {
            if (t.distance(c) < 2.0) {
                return false;
            }
        }
        return true;
    }
}
