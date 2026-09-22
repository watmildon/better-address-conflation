// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.cells;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;

/** Produces the cells (parcels or stand-ins) that addresses and buildings are assigned to. */
public interface CellSource {

    /**
     * Build cells.
     *
     * @param proj local projection to build geometries in
     * @param addressSites projected positions of every source address node
     * @param extent projected extent of the source addresses
     * @param settings tunables
     * @return the cells
     */
    List<Cell> buildCells(LocalProjection proj, List<Coordinate> addressSites, Envelope extent, ConflationSettings settings);

    /** True when the cells are synthetic (Voronoi), not real parcels. */
    boolean isSynthetic();

    String describe();
}
