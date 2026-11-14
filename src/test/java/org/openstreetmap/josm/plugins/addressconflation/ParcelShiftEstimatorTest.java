// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.ParcelShiftEstimator.Shift;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;

/**
 * A parcel layer a few metres off the buildings: houses that fill their lots poke over the lot
 * lines. The estimate finds the shift that puts them back inside their own parcels.
 */
class ParcelShiftEstimatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final double LOT_W = 20;
    private static final double LOT_H = 30;

    private final ConflationSettings settings = new ConflationSettings();

    /** cols x rows lots; each house is 18 x 28 m in the true lot, an address point inside it. */
    private static DataSet[] street(int cols, int rows) {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                double cx = i * LOT_W;
                double cy = j * LOT_H;
                Fixtures.rect(target, cx, cy, LOT_W - 2, LOT_H - 2, "building=house");
                Fixtures.node(source, cx, cy, "addr:housenumber=" + (100 + 2 * (i * rows + j)), "addr:street=Lot Line Road");
            }
        }
        return new DataSet[] {target, source};
    }

    /** The same lots as {@link #street}, drawn (dx, dy) metres off. */
    private static DataSet parcels(int cols, int rows, double dx, double dy) {
        DataSet parcels = new DataSet();
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                Fixtures.rect(parcels, i * LOT_W + dx, j * LOT_H + dy, LOT_W, LOT_H, "oa:pid=" + i + "-" + j);
            }
        }
        return parcels;
    }

    private Shift estimate(DataSet[] street, DataSet parcels) {
        AnalysisResult r = Analyzer.analyze(street[1], street[0], new ParcelCellSource(parcels, "p"), settings);
        return r.getParcelShift();
    }

    @Test
    void findsTheShiftThatPutsHousesBackInTheirLots() {
        DataSet[] street = street(6, 5);
        // Parcels drawn 4 m east and 3 m south of where they belong; each house has 1 m to spare
        // on every side, so any move within a metre of (-4, +3) fits them all.
        Shift s = estimate(street, parcels(6, 5, 4, -3));
        assertNotNull(s);
        assertTrue(s.isSignificant(), s.toString());
        assertTrue(s.getDx() >= -5.25 && s.getDx() <= -2.75, s.toString());
        assertTrue(s.getDy() >= 1.75 && s.getDy() <= 4.25, s.toString());
        assertTrue(s.getShareAfter() > 0.99, s.toString());
        assertTrue(s.getWorsened() == 0, s.toString());

        // Moved by the estimate, the parcels fit and nothing more is suggested.
        Shift again = estimate(street(6, 5), parcels(6, 5, 4 + s.getDx(), -3 + s.getDy()));
        assertFalse(again.isSignificant(), again.toString());
    }

    @Test
    void alignedParcelsNeedNoShift() {
        Shift s = estimate(street(6, 5), parcels(6, 5, 0, 0));
        assertNotNull(s);
        assertFalse(s.isSignificant(), s.toString());
        assertTrue(s.getDistance() < 0.01, "ties go to the smaller shift: " + s);
    }

    @Test
    void anOffsetWithinTheSlackAroundTheHousesIsLeftAlone() {
        // 0.8 m off with 1 m to spare: every house is still inside its own lot.
        Shift s = estimate(street(6, 5), parcels(6, 5, 0.8, 0));
        assertFalse(s.isSignificant(), s.toString());
    }

    @Test
    void tooFewBuildingsSayNothing() {
        Shift s = estimate(street(3, 3), parcels(3, 3, 4, -3));
        assertTrue(s == null || !s.isSignificant(), String.valueOf(s));
    }

    @Test
    void voronoiCellsHaveNoParcelShift() {
        DataSet[] street = street(6, 5);
        assertNull(Analyzer.analyze(street[1], street[0], new VoronoiCellSource(), settings).getParcelShift());
    }

    @Test
    void aLargeAreaStaysQuick() {
        // 600 lots: the estimate measures at most a few hundred of them.
        DataSet[] street = street(30, 20);
        DataSet parcels = parcels(30, 20, -2.5, 2);
        long t0 = System.nanoTime();
        Shift s = estimate(street, parcels);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println(String.format(Locale.ROOT, "parcel shift over 600 lots: %s in %d ms (whole analysis)", s, ms));
        assertTrue(s.isSignificant(), s.toString());
        assertTrue(s.getSamples() <= 300, s.toString());
    }
}
