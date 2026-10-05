// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;

/** What the engine suggests doing with one or more source addresses. */
public final class Proposal {
    private final Bucket bucket;
    private final List<AddressGroup> addresses;
    private final BuildingCandidate target;
    private final List<CellBuilding> candidates;
    private final double confidence;
    private final List<String> reasons;
    private final ExistingKind existingKind;
    private final List<ExistingAddress> existing;
    private final Cell cell;
    /** The building has other addresses: this one stays a node instead of tagging the outline. */
    private final boolean keepsNode;

    public Proposal(Bucket bucket, List<AddressGroup> addresses, BuildingCandidate target,
            List<CellBuilding> candidates, double confidence, List<String> reasons,
            ExistingKind existingKind, List<ExistingAddress> existing, Cell cell) {
        this.bucket = bucket;
        this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
        this.target = target;
        this.candidates = candidates == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(candidates));
        this.confidence = confidence;
        this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        this.existingKind = existingKind;
        this.existing = existing == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(existing));
        this.cell = cell;
        this.keepsNode = false;
    }

    private Proposal(Proposal p, Bucket bucket, List<String> reasons) {
        this.bucket = bucket;
        this.addresses = p.addresses;
        this.target = p.target;
        this.candidates = p.candidates;
        this.confidence = p.confidence;
        this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        this.existingKind = p.existingKind;
        this.existing = p.existing;
        this.cell = p.cell;
        this.keepsNode = true;
    }

    /**
     * This proposal with its address kept as a node inside the building, because the building
     * holds other addresses too.
     *
     * @param newBucket the bucket that fits the new action
     * @param why       the reasons, added to the others
     */
    public Proposal keepingNode(Bucket newBucket, List<String> why) {
        List<String> r = new ArrayList<>(reasons);
        r.addAll(why);
        return new Proposal(this, newBucket, r);
    }

    /** True when the address stays a node inside the building rather than tagging it. */
    public boolean keepsNode() {
        return keepsNode;
    }

    public Bucket getBucket() {
        return bucket;
    }

    public List<AddressGroup> getAddresses() {
        return addresses;
    }

    /** Building that would receive the address(es); null for NO_BUILDING and OUTSIDE_CELLS. */
    public BuildingCandidate getTarget() {
        return target;
    }

    /** All buildings considered, best first. */
    public List<CellBuilding> getCandidates() {
        return candidates;
    }

    /** True when there is no default building: the mapper has to pick one of the candidates. */
    public boolean requiresPick() {
        return target == null && !candidates.isEmpty() && bucket == Bucket.AMBIGUOUS_BUILDING;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public ExistingKind getExistingKind() {
        return existingKind;
    }

    public List<ExistingAddress> getExisting() {
        return existing;
    }

    public Cell getCell() {
        return cell;
    }

    /** Every source node touched by this proposal (primaries and duplicates). */
    public List<Node> getSourceNodes() {
        List<Node> nodes = new ArrayList<>();
        for (AddressGroup g : addresses) {
            nodes.addAll(g.getAllNodes());
        }
        return nodes;
    }

    /** Primitives to highlight on the map: source nodes, target, existing. */
    public List<OsmPrimitive> getHighlightPrimitives() {
        List<OsmPrimitive> prims = new ArrayList<>(getSourceNodes());
        if (target != null) {
            prims.add(target.getPrimitive());
        }
        if (bucket == Bucket.AMBIGUOUS_BUILDING) {
            // show the alternatives so the mapper can pick one on the map
            for (CellBuilding c : candidates) {
                if (!c.getBuilding().isHint() && !prims.contains(c.getBuilding().getPrimitive())) {
                    prims.add(c.getBuilding().getPrimitive());
                }
            }
        }
        for (ExistingAddress e : existing) {
            prims.add(e.getPrimitive());
        }
        return prims;
    }

    public String describe() {
        if (addresses.size() == 1) {
            return addresses.get(0).describe();
        }
        return addresses.size() + " addresses: " + addresses.get(0).describe() + " ...";
    }

    @Override
    public String toString() {
        return bucket + " " + describe() + (target != null ? " -> " + target : "") + " (" + Math.round(confidence * 100) + "%)";
    }
}
