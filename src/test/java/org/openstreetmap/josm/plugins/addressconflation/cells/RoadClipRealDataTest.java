// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.cells;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;

/**
 * On real data (slanted Voronoi edges, floating-point road crossings) no cell may keep a
 * dividing road that runs right across it. Roads that end inside a cell cannot split it and
 * are allowed.
 */
class RoadClipRealDataTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void noRoadRunsAcrossAClippedCell() {
        DataSet buildings = JosmTestSetup.loadDataSet("owyhee-grandview/buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet("owyhee-grandview/addresses-full-parcel.osm");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, new RoadClippedVoronoiCellSource(buildings), new ConflationSettings());
        List<LineString> roads = new ArrayList<>();
        for (Way w : buildings.getWays()) {
            if (w.isUsable() && RoadClippedVoronoiCellSource.DIVIDING_HIGHWAYS.contains(w.get("highway"))) {
                List<Coordinate> cs = new ArrayList<>();
                for (Node n : w.getNodes()) {
                    cs.add(r.getProjection().toXY(n.getCoor()));
                }
                roads.add(OsmGeometry.factory().createLineString(cs.toArray(new Coordinate[0])));
            }
        }
        assertTrue(roads.size() > 50, "fixture should have roads: " + roads.size());
        int across = 0;
        for (Cell c : r.getCells()) {
            Geometry cell = c.getGeometry();
            Geometry inner = cell.buffer(-1.0);
            for (LineString road : roads) {
                if (!road.intersects(inner)) {
                    continue;
                }
                Geometry in = road.intersection(cell);
                for (int i = 0; i < in.getNumGeometries(); i++) {
                    if (in.getGeometryN(i) instanceof LineString) {
                        LineString part = (LineString) in.getGeometryN(i);
                        if (part.getLength() > 2 && cell.getBoundary().distance(part.getStartPoint()) < 0.01
                                && cell.getBoundary().distance(part.getEndPoint()) < 0.01 && part.intersects(inner)) {
                            across++;
                        }
                    }
                }
            }
        }
        assertEquals(0, across, "cells with a road running right across them");
    }
}
