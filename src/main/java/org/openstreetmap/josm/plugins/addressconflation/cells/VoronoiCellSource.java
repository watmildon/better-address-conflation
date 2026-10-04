// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.cells;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;
import org.openstreetmap.josm.tools.Logging;

/**
 * Voronoi tessellation of the address points, used when no parcel layer is
 * available. Each address gets the region of the plane closer to it than to
 * any other address, which approximates a lot in a subdivision surprisingly
 * well. Cells are clipped to the address extent plus the match distance, then
 * each is trimmed to a circle scaled by the local address spacing, so a cell on
 * the edge of the cluster (which has no neighbour on its outer side) is no
 * bigger than a typical lot there.
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
        LocalSpacing spacing = settings.voronoiReachFactor > 0 ? new LocalSpacing(addressSites) : null;
        for (int i = 0; i < diagram.getNumGeometries(); i++) {
            Geometry g = diagram.getGeometryN(i);
            if (g.isEmpty()) {
                continue;
            }
            if (spacing != null && g.getUserData() instanceof Coordinate) {
                g = trim(g, (Coordinate) g.getUserData(), spacing, settings);
            }
            // JTS stores the site coordinate as user data; subclasses rely on it.
            cells.add(new Cell("voronoi-" + i, g, true, null));
        }
        return cells;
    }

    /** Intersect the cell with a circle around its site sized by the local spacing. */
    static Geometry trim(Geometry cell, Coordinate site, LocalSpacing spacing, ConflationSettings settings) {
        double local = spacing.at(site);
        if (Double.isNaN(local)) {
            return cell;
        }
        double reach = Math.max(settings.voronoiMinReachMeters, settings.voronoiReachFactor * local);
        Envelope env = cell.getEnvelopeInternal();
        // Cheap test first: most interior cells lie well inside the circle.
        double far = Math.max(Math.max(Math.abs(env.getMinX() - site.x), Math.abs(env.getMaxX() - site.x)),
                Math.max(Math.abs(env.getMinY() - site.y), Math.abs(env.getMaxY() - site.y)));
        if (far * Math.sqrt(2) <= reach) {
            return cell;
        }
        Geometry circle = OsmGeometry.factory().createPoint(site).buffer(reach, 8);
        Geometry trimmed;
        try {
            trimmed = cell.intersection(circle);
        } catch (TopologyException e) {
            Logging.trace(e);
            return cell;
        }
        if (trimmed.isEmpty() || !(trimmed instanceof Polygon)) {
            return cell;
        }
        trimmed.setUserData(site);
        return trimmed;
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
