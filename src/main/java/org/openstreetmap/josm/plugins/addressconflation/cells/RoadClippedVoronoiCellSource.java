// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.cells;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.polygonize.Polygonizer;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;
import org.openstreetmap.josm.tools.Logging;

/**
 * Voronoi cells cut along road centrelines. A parcel never crosses a public
 * road, so a Voronoi cell that reaches across the street to the house on the
 * other side is trimmed back to the piece its own address point sits in.
 * Service roads, footways and paths do not count: driveways and alleys run
 * inside or between lots.
 */
public class RoadClippedVoronoiCellSource extends VoronoiCellSource {
    /** highway=* values that separate parcels. Railways are matched on the railway key instead. */
    public static final Set<String> DIVIDING_HIGHWAYS = new HashSet<>(Arrays.asList(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"));

    private final DataSet roads;

    /** @param roads dataset holding the highway ways (normally the edit layer) */
    public RoadClippedVoronoiCellSource(DataSet roads) {
        this.roads = roads;
    }

    @Override
    public List<Cell> buildCells(LocalProjection proj, List<Coordinate> addressSites, Envelope extent, ConflationSettings settings) {
        List<Cell> plain = super.buildCells(proj, addressSites, extent, settings);
        STRtree roadIndex = indexRoads(proj, extent, settings);
        if (roadIndex == null) {
            return plain;
        }
        List<Cell> out = new ArrayList<>(plain.size());
        int clipped = 0;
        for (Cell cell : plain) {
            Geometry g = clip(cell.getGeometry(), roadIndex);
            if (g != cell.getGeometry()) {
                clipped++;
            }
            out.add(new Cell(cell.getId(), g, true, null));
        }
        Logging.debug("Road-clipped Voronoi: " + clipped + " of " + plain.size() + " cells trimmed");
        return out;
    }

    @SuppressWarnings("unchecked")
    private STRtree indexRoads(LocalProjection proj, Envelope extent, ConflationSettings settings) {
        Envelope search = new Envelope(extent);
        search.expandBy(settings.matchDistanceMeters * 2 + 100);
        STRtree index = new STRtree();
        int n = 0;
        for (Way w : roads.getWays()) {
            if (!w.isUsable() || w.getNodesCount() < 2 || w.isIncomplete() || w.hasIncompleteNodes()) {
                continue;
            }
            String hw = w.get("highway");
            boolean rail = w.hasKey("railway") && !w.hasTag("railway", "abandoned", "razed", "platform") && !w.hasKey("building");
            if (!(hw != null && DIVIDING_HIGHWAYS.contains(hw)) && !rail) {
                continue;
            }
            List<Coordinate> cs = new ArrayList<>(w.getNodesCount());
            for (Node nd : w.getNodes()) {
                if (nd.getCoor() != null) {
                    cs.add(proj.toXY(nd.getCoor()));
                }
            }
            if (cs.size() < 2) {
                continue;
            }
            LineString ls = OsmGeometry.factory().createLineString(cs.toArray(new Coordinate[0]));
            if (!ls.getEnvelopeInternal().intersects(search)) {
                continue;
            }
            index.insert(ls.getEnvelopeInternal(), ls);
            n++;
        }
        if (n == 0) {
            return null;
        }
        index.build();
        return index;
    }

    /** Cut the cell along every road crossing it and keep the piece holding the site. */
    @SuppressWarnings("unchecked")
    static Geometry clip(Geometry cell, STRtree roadIndex) {
        Object site = cell.getUserData();
        List<LineString> hits = new ArrayList<>();
        for (LineString ls : (List<LineString>) roadIndex.query(cell.getEnvelopeInternal())) {
            if (ls.intersects(cell)) {
                hits.add(ls);
            }
        }
        if (hits.isEmpty()) {
            return cell;
        }
        try {
            // Node the whole road lines against the cell boundary. Clipping the roads to the
            // cell first leaves their cut ends only approximately on the boundary, the noder
            // misses the touch, and the polygonizer sees dangles instead of a cut.
            Geometry lines = cell.getBoundary();
            for (LineString ls : hits) {
                lines = lines.union(ls);
            }
            Polygonizer polygonizer = new Polygonizer();
            polygonizer.add(lines);
            List<Polygon> pieces = new ArrayList<>();
            for (Polygon piece : (Collection<Polygon>) polygonizer.getPolygons()) {
                // Roads can close rings outside the cell (a loop, a block); those are not cell pieces.
                if (cell.covers(piece.getInteriorPoint())) {
                    pieces.add(piece);
                }
            }
            if (pieces.size() <= 1) {
                return cell;
            }
            Point p = site instanceof Coordinate ? cell.getFactory().createPoint((Coordinate) site) : cell.getCentroid();
            Polygon best = null;
            for (Polygon piece : pieces) {
                if (piece.covers(p) && (best == null || piece.getArea() > best.getArea())) {
                    best = piece;
                }
            }
            if (best == null) {
                // Site exactly on a road: take the nearest piece.
                double d = Double.MAX_VALUE;
                for (Polygon piece : pieces) {
                    double pd = piece.distance(p);
                    if (pd < d) {
                        d = pd;
                        best = piece;
                    }
                }
            }
            if (best == null) {
                return cell;
            }
            best.setUserData(site);
            return best;
        } catch (TopologyException e) {
            Logging.trace(e);
            return cell;
        }
    }

    @Override
    public String describe() {
        return "Voronoi cells clipped by roads";
    }
}
