// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.cells;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;

/**
 * Voronoi tessellation of the address points, used when no parcel layer is
 * available. Each address gets the region of the plane closer to it than to
 * any other address, which approximates a lot in a subdivision surprisingly
 * well. Cells are clipped to the address extent plus the match distance.
 */
public class VoronoiCellSource implements CellSource {

    @Override
    public List<Cell> buildCells(LocalProjection proj, List<Coordinate> addressSites, Envelope extent, ConflationSettings settings) {
        List<Cell> cells = new ArrayList<>();
        if (addressSites.isEmpty()) {
            return cells;
        }
        Envelope clip = new Envelope(extent);
        clip.expandBy(Math.max(settings.matchDistanceMeters * 2, 50));
        VoronoiDiagramBuilder builder = new VoronoiDiagramBuilder();
        builder.setSites(addressSites);
        builder.setClipEnvelope(clip);
        builder.setTolerance(0.01);
        Geometry diagram = builder.getDiagram(OsmGeometry.factory());
        for (int i = 0; i < diagram.getNumGeometries(); i++) {
            Geometry g = diagram.getGeometryN(i);
            if (g.isEmpty()) {
                continue;
            }
            // JTS stores the site coordinate as user data; subclasses rely on it.
            cells.add(new Cell("voronoi-" + i, g, true, null));
        }
        return cells;
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }

    @Override
    public String describe() {
        return "Voronoi cells";
    }
}
