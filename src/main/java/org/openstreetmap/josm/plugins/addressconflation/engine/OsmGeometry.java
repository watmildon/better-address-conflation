// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.openstreetmap.josm.data.osm.MultipolygonBuilder;
import org.openstreetmap.josm.data.osm.MultipolygonBuilder.JoinedPolygon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Pair;

/** Converts OSM ways and multipolygon relations to JTS polygons in local metres. */
public final class OsmGeometry {
    private static final GeometryFactory GF = new GeometryFactory();

    private OsmGeometry() {
    }

    public static GeometryFactory factory() {
        return GF;
    }

    /**
     * Polygon for a closed way or a multipolygon relation, or null when the
     * primitive is incomplete or not an area.
     */
    public static Geometry toPolygon(OsmPrimitive prim, LocalProjection proj) {
        try {
            if (prim instanceof Way) {
                return wayToPolygon((Way) prim, proj);
            }
            if (prim instanceof Relation) {
                return relationToPolygon((Relation) prim, proj);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static Geometry wayToPolygon(Way way, LocalProjection proj) {
        if (!way.isClosed() || way.isIncomplete() || way.hasIncompleteNodes() || way.getNodesCount() < 4) {
            return null;
        }
        LinearRing ring = ring(way.getNodes(), proj);
        if (ring == null) {
            return null;
        }
        return fix(GF.createPolygon(ring));
    }

    private static Geometry relationToPolygon(Relation rel, LocalProjection proj) {
        if (rel.isIncomplete() || rel.hasIncompleteMembers()) {
            return null;
        }
        Pair<List<JoinedPolygon>, List<JoinedPolygon>> rings;
        try {
            rings = MultipolygonBuilder.joinWays(rel);
        } catch (RuntimeException e) {
            // JoinedPolygonCreationException and friends: broken multipolygon
            return null;
        }
        if (rings == null || rings.a.isEmpty()) {
            return null;
        }
        List<Polygon> outers = new ArrayList<>();
        for (JoinedPolygon jp : rings.a) {
            LinearRing r = ring(jp.getNodes(), proj);
            if (r != null) {
                outers.add(GF.createPolygon(r));
            }
        }
        if (outers.isEmpty()) {
            return null;
        }
        Geometry result = fix(GF.createMultiPolygon(outers.toArray(new Polygon[0])));
        for (JoinedPolygon jp : rings.b) {
            LinearRing r = ring(jp.getNodes(), proj);
            if (r != null) {
                result = result.difference(fix(GF.createPolygon(r)));
            }
        }
        return result;
    }

    private static LinearRing ring(List<Node> nodes, LocalProjection proj) {
        List<Coordinate> coords = new ArrayList<>(nodes.size() + 1);
        for (Node n : nodes) {
            if (n.getCoor() == null) {
                return null;
            }
            coords.add(proj.toXY(n.getCoor()));
        }
        if (!coords.get(0).equals2D(coords.get(coords.size() - 1))) {
            coords.add(coords.get(0));
        }
        if (coords.size() < 4) {
            return null;
        }
        return GF.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    private static Geometry fix(Geometry g) {
        return g.isValid() ? g : GeometryFixer.fix(g);
    }
}
