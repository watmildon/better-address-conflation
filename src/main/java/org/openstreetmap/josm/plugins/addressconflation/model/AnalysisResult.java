// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.ShiftEstimator;

/** Output of one analysis run. */
public final class AnalysisResult {
    private final List<Proposal> proposals;
    private final List<Cell> cells;
    private final LocalProjection projection;
    private final int sourceNodes;
    private final int duplicatesRemoved;
    private final boolean synthetic;
    private ShiftEstimator.Shift shift;

    public AnalysisResult(List<Proposal> proposals, List<Cell> cells, LocalProjection projection,
            int sourceNodes, int duplicatesRemoved, boolean synthetic) {
        this.proposals = Collections.unmodifiableList(proposals);
        this.cells = Collections.unmodifiableList(cells);
        this.projection = projection;
        this.sourceNodes = sourceNodes;
        this.duplicatesRemoved = duplicatesRemoved;
        this.synthetic = synthetic;
    }

    public List<Proposal> getProposals() {
        return proposals;
    }

    public List<Cell> getCells() {
        return cells;
    }

    public LocalProjection getProjection() {
        return projection;
    }

    public int getSourceNodes() {
        return sourceNodes;
    }

    /** Source nodes that were absolute duplicates of another node in the same cell. */
    public int getDuplicatesRemoved() {
        return duplicatesRemoved;
    }

    /** Systematic offset of the address points, or null when none was estimated. */
    public ShiftEstimator.Shift getShift() {
        return shift;
    }

    public void setShift(ShiftEstimator.Shift shift) {
        this.shift = shift;
    }

    /** True when cells were Voronoi cells rather than parcels. */
    public boolean isSynthetic() {
        return synthetic;
    }

    public Map<Bucket, Integer> countByBucket() {
        Map<Bucket, Integer> counts = new EnumMap<>(Bucket.class);
        for (Proposal p : proposals) {
            counts.merge(p.getBucket(), 1, Integer::sum);
        }
        return counts;
    }
}
