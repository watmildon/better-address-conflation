package org.openstreetmap.josm.plugins.addressconflation;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.cells.CellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.RoadClippedVoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.AddressNormalizer;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesReader;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/** Detailed report for the "county address points at parcel centroids" case. Prints only. */
class ParcelCentroidReportTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static DataSet parcels() throws IOException {
        return parcels("glendale-olive");
    }

    private static DataSet parcels(String bed) throws IOException {
        try (InputStream is = JosmTestSetup.resource(bed + "/oa/parcels.geojson")) {
            return OpenAddressesReader.read(is, OpenAddressesReader.Layer.PARCELS, false);
        }
    }

    private static void report(String title, AnalysisResult r, DataSet buildings) {
        Map<String, int[]> byBucket = new TreeMap<>();
        Map<String, Integer> wrongByParcel = new HashMap<>();
        Map<String, Integer> wrongByReason = new LinkedHashMap<>();
        int standalone = 0;
        StringBuilder details = new StringBuilder();
        for (Proposal p : r.getProposals()) {
            int[] c = byBucket.computeIfAbsent(p.getBucket().name(), k -> new int[3]); // proposals, correct, wrong
            c[0]++;
            for (AddressGroup g : p.getAddresses()) {
                for (Node n : g.getAllNodes()) {
                    String src = n.get("testbed:source");
                    if (p.getTarget() == null) {
                        continue;
                    }
                    if (src == null || !src.startsWith("way/")) {
                        standalone++;
                        continue;
                    }
                    long expected = Long.parseLong(src.substring(4));
                    if (p.getTarget().getPrimitive().getUniqueId() == expected) {
                        c[1]++;
                    } else {
                        c[2]++;
                        String cell = p.getCell() == null ? "?" : p.getCell().getId();
                        wrongByParcel.merge(cell, 1, Integer::sum);
                        OsmPrimitive exp = buildings.getPrimitiveById(expected, org.openstreetmap.josm.data.osm.OsmPrimitiveType.WAY);
                        String reason = "expected building=" + (exp == null ? "?" : exp.get("building")) + " got building=" + p.getTarget().getBuildingValue();
                        wrongByReason.merge(reason, 1, Integer::sum);
                        if (p.getAddresses().size() < 50 && details.length() < 4000) {
                            details.append("    ").append(p.getBucket()).append(' ').append(g.describe()).append(" cell ").append(cell)
                                .append(": ").append(reason).append(' ').append(p.getReasons()).append('\n');
                        }
                    }
                }
            }
        }
        System.out.println("=== " + title);
        int tc = 0, tw = 0;
        for (Map.Entry<String, int[]> e : byBucket.entrySet()) {
            int[] c = e.getValue();
            tc += c[1];
            tw += c[2];
            System.out.printf("  %-24s proposals=%4d correct=%4d wrong=%4d%n", e.getKey(), c[0], c[1], c[2]);
        }
        System.out.printf("  TOTAL correct=%d wrong=%d (%.1f%%) standalone-truth assigned=%d%n", tc, tw, 100.0 * tc / Math.max(1, tc + tw), standalone);
        System.out.println("  wrong by cell: " + wrongByParcel);
        System.out.println("  wrong by kind: " + wrongByReason);
        System.out.print(details);
    }

    @Test
    void parcelCentroids() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet("glendale-olive/buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet("glendale-olive/addresses-full-parcel.osm");
        report("parcel-centroid points + county parcels", Analyzer.analyze(addresses, buildings, new ParcelCellSource(parcels(), "parcels"), new ConflationSettings()), buildings);
        addresses = JosmTestSetup.loadDataSet("glendale-olive/addresses-full-parcel.osm");
        report("parcel-centroid points + Voronoi", Analyzer.analyze(addresses, buildings, new VoronoiCellSource(), new ConflationSettings()), buildings);
        addresses = JosmTestSetup.loadDataSet("glendale-olive/addresses-full-parcel.osm");
        DataSet generic = JosmTestSetup.loadDataSet("glendale-olive/buildings-stripped-generic.osm");
        report("parcel-centroid points + parcels, all building=yes", Analyzer.analyze(addresses, generic, new ParcelCellSource(parcels(), "parcels"), new ConflationSettings()), generic);
    }

    @Test
    void countyPoints() throws IOException {
        countyLayer("glendale-olive", "oa/addresses.geojson", "Maricopa county address points (rooftop)");
    }

    @Test
    void parcelSitusPoints() throws IOException {
        countyLayer("glendale-olive", "oa/addresses-parcel-situs.geojson", "Maricopa parcel situs addresses at parcel centroids");
    }

    @Test
    void colonieParcelCentroids() throws IOException {
        // Real parcel-centroid addresses: this is the case the plugin exists for.
        double[] acc = countyLayer("colonie-ny", "oa/addresses.geojson", "NYS tax parcel centroids (Colonie)");
        assertTrue(acc[0] > 0.97, "parcel-centroid addresses with parcels: " + acc[0]);
        assertTrue(acc[1] > 0.95, "parcel-centroid addresses with Voronoi: " + acc[1]);
        assertTrue(acc[2] >= acc[1], "road clipping must not make Voronoi worse: " + acc[2] + " vs " + acc[1]);
        double[] sam = countyLayer("colonie-ny", "oa/addresses-sam.geojson", "NYS SAM points, mostly rooftop (Colonie)");
        assertTrue(sam[0] > 0.98, "SAM with parcels: " + sam[0]);
    }

    @Test
    void maricopaParcelSitusAccuracy() throws IOException {
        double[] acc = countyLayer("glendale-olive", "oa/addresses-parcel-situs.geojson", "Maricopa parcel situs addresses at parcel centroids");
        assertTrue(acc[0] > 0.98, "parcel situs with parcels: " + acc[0]);
    }

    @Test
    void colonieVariants() throws IOException {
        DataSet buildings = JosmTestSetup.loadDataSet("colonie-ny/buildings-stripped.osm");
        DataSet addresses = JosmTestSetup.loadDataSet("colonie-ny/addresses-full.osm");
        report("Colonie: centroid points + parcels", Analyzer.analyze(addresses, buildings, new ParcelCellSource(parcels("colonie-ny"), "parcels"), new ConflationSettings()), buildings);
        addresses = JosmTestSetup.loadDataSet("colonie-ny/addresses-full-parcel.osm");
        report("Colonie: parcel-centroid points + parcels", Analyzer.analyze(addresses, buildings, new ParcelCellSource(parcels("colonie-ny"), "parcels"), new ConflationSettings()), buildings);
        addresses = JosmTestSetup.loadDataSet("colonie-ny/addresses-full-parcel.osm");
        report("Colonie: parcel-centroid points + Voronoi", Analyzer.analyze(addresses, buildings, new VoronoiCellSource(), new ConflationSettings()), buildings);
        DataSet generic = JosmTestSetup.loadDataSet("colonie-ny/buildings-stripped-generic.osm");
        addresses = JosmTestSetup.loadDataSet("colonie-ny/addresses-full-parcel.osm");
        report("Colonie: parcel-centroid points + parcels, all building=yes", Analyzer.analyze(addresses, generic, new ParcelCellSource(parcels("colonie-ny"), "parcels"), new ConflationSettings()), generic);
    }

    /**
     * Real county points, scored by matching housenumber|street|unit back to the snapshot's
     * buildings. Returns accuracy with parcels and with Voronoi cells.
     */
    private static double[] countyLayer(String bed, String resource, String label) throws IOException {
        double[] accuracies = new double[3];
        int run = 0;
        DataSet snapshot = JosmTestSetup.loadDataSet(bed + "/snapshot.osm");
        Map<String, Long> truth = new HashMap<>();
        Map<String, Integer> truthCount = new HashMap<>();
        for (Way w : snapshot.getWays()) {
            if (w.hasKey("building") && w.hasKey("addr:housenumber")) {
                String k = AddressNormalizer.key(w.getKeys());
                truth.put(k, w.getUniqueId());
                truthCount.merge(k, 1, Integer::sum);
            }
        }
        DataSet buildings = JosmTestSetup.loadDataSet(bed + "/buildings-stripped.osm");
        DataSet addresses;
        try (InputStream is = JosmTestSetup.resource(bed + "/" + resource)) {
            addresses = OpenAddressesReader.read(is, OpenAddressesReader.Layer.ADDRESSES, true);
        }
        for (CellSource cs : new CellSource[] {new ParcelCellSource(parcels(bed), "parcels"), new VoronoiCellSource(),
                new RoadClippedVoronoiCellSource(buildings)}) {
            AnalysisResult r = Analyzer.analyze(addresses, buildings, cs, new ConflationSettings());
            int correct = 0, wrong = 0, unknown = 0, noTarget = 0, unknownNoTarget = 0;
            Map<String, Integer> wrongBuckets = new TreeMap<>();
            StringBuilder sample = new StringBuilder();
            for (Proposal p : r.getProposals()) {
                for (AddressGroup g : p.getAddresses()) {
                    String k = AddressNormalizer.key(g.getTags());
                    Long expected = truth.get(k);
                    if (p.getTarget() == null) {
                        if (expected == null) unknownNoTarget++; else noTarget++;
                        continue;
                    }
                    if (expected == null) {
                        unknown++;
                    } else if (expected == p.getTarget().getPrimitive().getUniqueId()) {
                        correct++;
                    } else {
                        wrong++;
                        wrongBuckets.merge(p.getBucket().name(), 1, Integer::sum);
                        if (sample.length() < 2500) {
                            sample.append("    ").append(p.getBucket()).append(' ').append(g.describe()).append(" -> got ")
                                .append(p.getTarget()).append(" expected way ").append(expected).append(' ').append(p.getReasons()).append('\n');
                        }
                    }
                }
            }
            System.out.println("=== " + label + " + " + cs.describe());
            System.out.printf("  in-snapshot addresses: correct=%d wrong=%d (%.1f%%), no building assigned=%d%n", correct, wrong, 100.0 * correct / Math.max(1, correct + wrong), noTarget);
            System.out.printf("  addresses not in snapshot (outside area or never mapped): assigned=%d, no target=%d%n", unknown, unknownNoTarget);
            System.out.println("  buckets: " + r.countByBucket() + " wrong by bucket: " + wrongBuckets);
            System.out.print(sample);
            accuracies[run++] = (double) correct / Math.max(1, correct + wrong);
        }
        return accuracies;
    }
}
