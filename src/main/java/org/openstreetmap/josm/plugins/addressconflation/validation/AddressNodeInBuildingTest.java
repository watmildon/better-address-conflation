// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;

/**
 * Looks at bare address nodes (no shop, amenity, entrance or name) that sit
 * inside a building outline carrying a different address: one of the two is
 * probably wrong. In a building with several address nodes that is usually
 * just a multi-unit building, so it is only a low-severity note there; so it
 * is for a large building whose node names another street, which is usually
 * its second frontage on the cross street.
 * <p>
 * Identical node and building addresses are left to JOSM's own duplicate
 * house number test. A node inside a building without any address is left
 * alone: whether addresses live on nodes or on outlines varies from place to
 * place, and the plugin's conflation workflow moves them when wanted.
 */
public class AddressNodeInBuildingTest extends Test {
    /** The node's address differs from the address of the building it is in. */
    public static final int CODE_DIFFERS = 47107;

    /** Tags that make a node a feature of its own rather than just an address. */
    static final List<String> POI_KEYS = Collections.unmodifiableList(Arrays.asList(
            "amenity", "shop", "office", "craft", "tourism", "leisure", "healthcare", "emergency", "entrance", "name"));

    /**
     * Square metres from which a building with a different street from its address
     * node counts as large: big enough to front two streets.
     */
    static final double LARGE_BUILDING = 500.0;

    private final Map<DataSet, List<Node>> visited = new LinkedHashMap<>();

    public AddressNodeInBuildingTest() {
        super(tr("Address node inside a building"),
                tr("Finds address nodes inside a building that has a different address"));
    }

    @Override
    public void startTest(ProgressMonitor progressMonitor) {
        super.startTest(progressMonitor);
        visited.clear();
    }

    @Override
    public void visit(Node n) {
        if (n.isUsable() && n.getDataSet() != null && AddressGeometry.placed(n) && n.hasKey("addr:housenumber")
                && POI_KEYS.stream().noneMatch(n::hasKey)) {
            visited.computeIfAbsent(n.getDataSet(), k -> new ArrayList<>()).add(n);
        }
    }

    @Override
    public void endTest() {
        try {
            for (Map.Entry<DataSet, List<Node>> e : visited.entrySet()) {
                Run run = new Run(e.getKey());
                for (Node n : e.getValue()) {
                    if (isCanceled()) {
                        break;
                    }
                    run.check(n);
                }
            }
        } finally {
            visited.clear();
        }
        super.endTest();
    }

    private static boolean isBuildingArea(OsmPrimitive p) {
        String b = p.get("building");
        return b != null && !"no".equals(b) && p.isUsable();
    }

    private static String housenumber(OsmPrimitive p) {
        String v = p.get("addr:housenumber");
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }

    /** Different house number, or the streets differ. */
    private static boolean differs(Node n, OsmPrimitive building) {
        return !housenumber(n).equals(housenumber(building)) || !sameStreet(n, building);
    }

    /** False only when both name a street and the streets differ. */
    private static boolean sameStreet(OsmPrimitive a, OsmPrimitive b) {
        String sa = StreetNames.streetKey(a);
        String sb = StreetNames.streetKey(b);
        return sa == null || sb == null || sa.equals(sb);
    }

    /** One pass over one dataset. */
    private final class Run {
        private final DataSet ds;
        private final Map<OsmPrimitive, Geometry> shapes = new HashMap<>();
        private final Map<OsmPrimitive, Integer> addressNodes = new HashMap<>();
        private LocalProjection proj;

        Run(DataSet ds) {
            this.ds = ds;
        }

        void check(Node n) {
            if (proj == null) {
                proj = new LocalProjection(n.lat(), n.lon());
            }
            OsmPrimitive building = building(n);
            if (building == null || !building.hasKey("addr:housenumber") || !differs(n, building)) {
                return;
            }
            // Several address nodes: a multi-unit building. A different street on a large
            // building: usually its second frontage on the cross street.
            boolean minor = count(building) >= 2
                    || (!sameStreet(n, building) && shape(building).getArea() >= LARGE_BUILDING);
            errors.add(TestError.builder(AddressNodeInBuildingTest.this,
                    minor ? Severity.OTHER : Severity.WARNING, CODE_DIFFERS)
                    .message(tr("Address node differs from the address of its building"),
                            marktr("node {0} {1}, building {2} {3}"),
                            n.get("addr:housenumber"), Objects.toString(n.get("addr:street"), ""),
                            building.get("addr:housenumber"), Objects.toString(building.get("addr:street"), ""))
                    .primitives(n)
                    .highlight(n, building)
                    .build());
        }

        /** The smallest building that holds the node strictly inside, or null. */
        private OsmPrimitive building(Node n) {
            Point pt = OsmGeometry.factory().createPoint(proj.toXY(n.getCoor()));
            OsmPrimitive best = null;
            double bestArea = Double.POSITIVE_INFINITY;
            BBox box = new BBox(n.lon(), n.lat(), n.lon(), n.lat());
            List<OsmPrimitive> candidates = new ArrayList<>();
            for (Way w : ds.searchWays(box)) {
                if (w.isClosed() && isBuildingArea(w)) {
                    candidates.add(w);
                }
                for (OsmPrimitive ref : w.getReferrers()) {
                    if (ref instanceof Relation && ref.isMultipolygon() && isBuildingArea(ref) && !candidates.contains(ref)) {
                        candidates.add(ref);
                    }
                }
            }
            for (OsmPrimitive c : candidates) {
                Geometry g = shape(c);
                if (g != null && g.contains(pt) && g.getArea() < bestArea) {
                    best = c;
                    bestArea = g.getArea();
                }
            }
            return best;
        }

        private Geometry shape(OsmPrimitive building) {
            if (!shapes.containsKey(building)) {
                shapes.put(building, OsmGeometry.toPolygon(building, proj));
            }
            return shapes.get(building);
        }

        /** Nodes with a house number strictly inside the building, whatever else they carry. */
        private int count(OsmPrimitive building) {
            Integer known = addressNodes.get(building);
            if (known != null) {
                return known;
            }
            Geometry g = shape(building);
            int count = 0;
            if (g != null) {
                for (Node m : ds.searchNodes(building.getBBox())) {
                    if (m.isUsable() && m.hasKey("addr:housenumber") && AddressGeometry.placed(m)
                            && g.contains(OsmGeometry.factory().createPoint(proj.toXY(m.getCoor())))) {
                        count++;
                    }
                }
            }
            addressNodes.put(building, count);
            return count;
        }
    }
}
