// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.ShiftEstimator;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;

class ShiftEstimatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void rooftopPointsShowNoShift() {
        DataSet buildings = JosmTestSetup.loadDataSet("glendale-olive/buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet("glendale-olive/addresses-full.osm");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, new VoronoiCellSource(), new ConflationSettings());
        ShiftEstimator.Shift s = r.getShift();
        assertNotNull(s);
        assertTrue(s.getDistance() < 1.0, s.toString());
        assertFalse(s.isSignificant());
    }

    @Test
    void recoversASystematicOffset() {
        DataSet buildings = JosmTestSetup.loadDataSet("glendale-olive/buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet("glendale-olive/addresses-full.osm");
        // Push every point 12 m west and 8 m south, as a bad export would.
        double dLat = -8.0 / 110540.0;
        double dLon = -12.0 / (111320.0 * Math.cos(Math.toRadians(33.565)));
        for (Node n : addresses.getNodes()) {
            n.setCoor(new LatLon(n.lat() + dLat, n.lon() + dLon));
        }
        AnalysisResult r = Analyzer.analyze(addresses, buildings, new VoronoiCellSource(), new ConflationSettings());
        ShiftEstimator.Shift s = r.getShift();
        assertNotNull(s);
        assertTrue(s.isSignificant(), s.toString());
        assertEquals(12.0, s.getDx(), 1.0, s.toString());
        assertEquals(8.0, s.getDy(), 1.0, s.toString());
        assertTrue(s.getSamples() > 500, s.toString());
    }
}
