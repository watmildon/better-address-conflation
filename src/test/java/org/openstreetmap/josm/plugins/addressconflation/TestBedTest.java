// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.plugins.addressconflation.cells.CellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesReader;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Scores the engine against the Glendale test bed. Generated address nodes
 * have id = -(source building id), so the expected target is known.
 */
class TestBedTest {

    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String BED = "glendale-olive/";

    /** Accuracy of building assignments plus bucket counts. */
    static final class Score {
        int assigned;
        int correct;
        int expectedBuildingButNone;
        int standaloneToBuilding;
        final Map<Bucket, Integer> buckets = new EnumMap<>(Bucket.class);
        final Map<Bucket, Integer> wrongByBucket = new EnumMap<>(Bucket.class);

        double accuracy() {
            return assigned == 0 ? 0 : (double) correct / assigned;
        }

        @Override
        public String toString() {
            return String.format("assigned=%d correct=%d (%.1f%%) missedBuilding=%d standaloneToBuilding=%d buckets=%s wrong=%s",
                    assigned, correct, accuracy() * 100, expectedBuildingButNone, standaloneToBuilding, buckets, wrongByBucket);
        }
    }

    static Score score(AnalysisResult r, DataSet buildings) {
        Score s = new Score();
        int shown = 0;
        for (Proposal p : r.getProposals()) {
            s.buckets.merge(p.getBucket(), 1, Integer::sum);
            for (AddressGroup g : p.getAddresses()) {
                for (Node n : g.getAllNodes()) {
                    String src = n.get("testbed:source");
                    boolean expectsBuilding = src != null && src.startsWith("way/");
                    long expected = expectsBuilding ? Long.parseLong(src.substring(4)) : 0;
                    if (p.getTarget() != null && !expectsBuilding) {
                        // OSM had this as a standalone node; putting it inside a building is a
                        // judgment call, not an error.
                        s.standaloneToBuilding++;
                    } else if (p.getTarget() != null) {
                        s.assigned++;
                        if (p.getTarget().getPrimitive().getUniqueId() == expected) {
                            s.correct++;
                        } else {
                            s.wrongByBucket.merge(p.getBucket(), 1, Integer::sum);
                            if (shown++ < 6) {
                                OsmPrimitive exp = expectsBuilding ? buildings.getPrimitiveById(expected, OsmPrimitiveType.WAY) : null;
                                System.out.println("  WRONG " + p.getBucket() + " " + g.describe() + " expected " + src + " (building="
                                        + (exp == null ? "?" : exp.get("building")) + ") got " + p.getTarget() + " reasons=" + p.getReasons());
                            }
                        }
                    } else if (expectsBuilding && p.getBucket() == Bucket.NO_BUILDING) {
                        s.expectedBuildingButNone++;
                    }
                }
            }
        }
        return s;
    }

    private static DataSet parcels() throws IOException {
        try (InputStream is = JosmTestSetup.resource(BED + "oa/parcels.geojson")) {
            return OpenAddressesReader.read(is, OpenAddressesReader.Layer.PARCELS, false);
        }
    }

    @Test
    void centroidsWithVoronoi() {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet(BED + "addresses-full.osm");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, new VoronoiCellSource(), new ConflationSettings());
        Score s = score(r, buildings);
        System.out.println("centroids+voronoi: " + s + " cells=" + r.getCells().size());
        assertEquals(1128, r.getSourceNodes());
        assertTrue(s.accuracy() > 0.95, "accuracy " + s.accuracy());
        assertTrue(s.buckets.getOrDefault(Bucket.CLEAN, 0) > 900, "clean " + s.buckets);
    }

    @Test
    void centroidsWithParcels() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet(BED + "addresses-full.osm");
        CellSource cells = new ParcelCellSource(parcels(), "parcels");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, cells, new ConflationSettings());
        Score s = score(r, buildings);
        System.out.println("centroids+parcels: " + s + " cells=" + r.getCells().size());
        assertTrue(s.accuracy() > 0.95, "accuracy " + s.accuracy());
    }

    @Test
    void parcelCentroidsWithParcels() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        CellSource cells = new ParcelCellSource(parcels(), "parcels");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, cells, new ConflationSettings());
        Score s = score(r, buildings);
        System.out.println("parcel-centroids+parcels: " + s);
        // The 5201 W Olive complex (206 outlines, one parcel, one point position) can only go to
        // one building, so ~80% is the ceiling for this variant.
        assertTrue(s.accuracy() > 0.70, "accuracy " + s.accuracy());
    }

    @Test
    void parcelCentroidsWithVoronoi() {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, new VoronoiCellSource(), new ConflationSettings());
        Score s = score(r, buildings);
        System.out.println("parcel-centroids+voronoi: " + s);
        assertTrue(s.accuracy() > 0.70, "accuracy " + s.accuracy());
    }

    @Test
    void genericBuildingsWithParcels() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped-generic.osm");
        DataSet addresses = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        CellSource cells = new ParcelCellSource(parcels(), "parcels");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, cells, new ConflationSettings());
        Score s = score(r, buildings);
        System.out.println("generic+parcels: " + s);
        assertTrue(s.accuracy() > 0.70, "accuracy " + s.accuracy());
    }

    @Test
    void overlapDetectsExisting() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-partial.osm");
        DataSet addresses = JosmTestSetup.loadDataSet(BED + "addresses-overlap.osm");
        CellSource cells = new ParcelCellSource(parcels(), "parcels");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, cells, new ConflationSettings());
        Score s = score(r, buildings);
        System.out.println("overlap+parcels: " + s);
        int existing = s.buckets.getOrDefault(Bucket.EXISTING_ADDRESS, 0);
        // 74 overlap addresses are still on buildings; nearly all must be caught.
        assertTrue(existing >= 60, "existing " + existing);
        assertTrue(s.accuracy() > 0.75, "accuracy " + s.accuracy());
    }

    @Test
    void countyPointsWithParcels() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        DataSet addresses;
        try (InputStream is = JosmTestSetup.resource(BED + "oa/addresses.geojson")) {
            addresses = OpenAddressesReader.read(is, OpenAddressesReader.Layer.ADDRESSES, true);
        }
        CellSource cells = new ParcelCellSource(parcels(), "parcels");
        AnalysisResult r = Analyzer.analyze(addresses, buildings, cells, new ConflationSettings());
        System.out.println("county+parcels: sources=" + r.getSourceNodes() + " dups=" + r.getDuplicatesRemoved() + " buckets=" + r.countByBucket());
        assertTrue(r.getProposals().size() > 1000);
    }
}
