// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.model;

/** A building's membership in one cell: how much of it is inside, and its score there. */
public final class CellBuilding implements Comparable<CellBuilding> {
    private final BuildingCandidate building;
    private final Cell cell;
    private final double areaInCell;
    private final double share;
    private double score;

    public CellBuilding(BuildingCandidate building, Cell cell, double areaInCell) {
        this.building = building;
        this.cell = cell;
        this.areaInCell = areaInCell;
        this.share = building.getArea() > 0 ? areaInCell / building.getArea() : 0;
    }

    public BuildingCandidate getBuilding() {
        return building;
    }

    public Cell getCell() {
        return cell;
    }

    public double getAreaInCell() {
        return areaInCell;
    }

    /** Fraction of the building's footprint that lies in this cell, 0..1. */
    public double getShare() {
        return share;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    @Override
    public int compareTo(CellBuilding o) {
        int c = Double.compare(o.score, score);
        return c != 0 ? c : Long.compare(building.getPrimitive().getUniqueId(), o.building.getPrimitive().getUniqueId());
    }
}
