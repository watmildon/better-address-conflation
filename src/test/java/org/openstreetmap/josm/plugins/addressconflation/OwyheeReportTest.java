// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.CellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesReader;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Owyhee County, Idaho: a rural area whose addresses were imported as parcel-centroid
 * nodes and mostly merged onto buildings by hand. Buildings are all building=yes or
 * detached, parcels are large, and 42 nodes were never merged.
 */
class OwyheeReportTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String BED = "owyhee-grandview/";

    private static DataSet oa(String file, OpenAddressesReader.Layer layer) throws IOException {
        try (InputStream is = JosmTestSetup.resource(BED + file)) {
            return OpenAddressesReader.read(is, layer, false);
        }
    }

    private static double score(String title, AnalysisResult r, DataSet buildings) {
        int correct = 0;
        int wrong = 0;
        Map<String, Integer> wrongBy = new TreeMap<>();
        StringBuilder sample = new StringBuilder();
        for (Proposal p : r.getProposals()) {
            if (p.getTarget() == null) {
                continue;
            }
            for (AddressGroup g : p.getAddresses()) {
                String src = g.getPrimary().get("testbed:source");
                if (src == null || !src.startsWith("way/")) {
                    continue;
                }
                long expected = Long.parseLong(src.substring(4));
                if (p.getTarget().getPrimitive().getUniqueId() == expected) {
                    correct++;
                } else {
                    wrong++;
                    wrongBy.merge(p.getBucket().name(), 1, Integer::sum);
                    OsmPrimitive exp = buildings.getPrimitiveById(expected, OsmPrimitiveType.WAY);
                    if (sample.length() < 900) {
                        sample.append("    ").append(p.getBucket()).append(' ').append(g.describe()).append(": expected building=")
                                .append(exp == null ? "?" : exp.get("building")).append(" got ").append(p.getTarget()).append(' ').append(p.getReasons()).append('\n');
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "=== %s%n  correct=%d wrong=%d (%.1f%%) buckets=%s wrong=%s%n%s", title, correct, wrong,
                100.0 * correct / Math.max(1, correct + wrong), r.countByBucket(), wrongBy, sample);
        return (double) correct / Math.max(1, correct + wrong);
    }

    @Test
    void syntheticVariants() throws IOException {
        DataSet parcels = oa("oa/parcels.geojson", OpenAddressesReader.Layer.PARCELS);
        DataSet buildings = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        DataSet centroids = JosmTestSetup.loadDataSet(BED + "addresses-full.osm");
        double a = score("Owyhee: building-centroid points + parcels", Analyzer.analyze(centroids, buildings, new ParcelCellSource(parcels, "p"), new ConflationSettings()), buildings);
        assertTrue(a > 0.95, "" + a);
        DataSet pc = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        double b = score("Owyhee: parcel-centroid points + parcels", Analyzer.analyze(pc, buildings, new ParcelCellSource(parcels, "p"), new ConflationSettings()), buildings);
        pc = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        double c = score("Owyhee: parcel-centroid points + Voronoi (30 m)", Analyzer.analyze(pc, buildings, new VoronoiCellSource(), new ConflationSettings()), buildings);
        pc = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        ConflationSettings wide = new ConflationSettings();
        wide.matchDistanceMeters = 120;
        double d = score("Owyhee: parcel-centroid points + Voronoi (120 m)", Analyzer.analyze(pc, buildings, new VoronoiCellSource(), wide), buildings);
        System.out.printf(Locale.ROOT, "SUMMARY owyhee accuracy: centroid+parcels=%.3f parcelcentroid+parcels=%.3f voronoi30=%.3f voronoi120=%.3f%n", a, b, c, d);
        assertTrue(b > 0.8, "" + b);
    }

    @Test
    void hintsWithoutOsmBuildings() throws IOException {
        DataSet snapshot = JosmTestSetup.loadDataSet(BED + "snapshot.osm");
        DataSet target = JosmTestSetup.loadDataSet(BED + "buildings-stripped.osm");
        for (Way w : target.getWays().stream().filter(w -> w.hasKey("building")).collect(Collectors.toList())) {
            w.setDeleted(true);
        }
        DataSet parcels = oa("oa/parcels.geojson", OpenAddressesReader.Layer.PARCELS);
        DataSet hints = oa("oa/buildings.geojson", OpenAddressesReader.Layer.BUILDINGS);
        DataSet pc = JosmTestSetup.loadDataSet(BED + "addresses-full-parcel.osm");
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(pc, target, hints, new ParcelCellSource(parcels, "p"), s);
        int hinted = 0;
        int inside = 0;
        for (Proposal p : r.getProposals()) {
            if (p.getBucket() != Bucket.CLEAN || p.getTarget() == null || !p.getTarget().isHint()) {
                continue;
            }
            Applied a = ProposalApplier.build(p, target, pc, r.getProjection(), s);
            a.getTargetCommand().executeCommand();
            for (AddressGroup g : p.getAddresses()) {
                String src = g.getPrimary().get("testbed:source");
                if (src == null || !src.startsWith("way/")) {
                    continue;
                }
                OsmPrimitive truth = snapshot.getPrimitiveById(Long.parseLong(src.substring(4)), OsmPrimitiveType.WAY);
                Node created = target.getNodes().stream().filter(n -> !n.isDeleted() && n.hasKey("addr:housenumber")
                        && g.getTags().get("addr:housenumber").equals(n.get("addr:housenumber"))
                        && g.getTags().get("addr:street").equals(n.get("addr:street"))).reduce((x, y) -> y).orElse(null);
                hinted++;
                if (created != null && OsmGeometry.toPolygon(truth, r.getProjection()) != null
                        && OsmGeometry.toPolygon(truth, r.getProjection()).contains(OsmGeometry.factory().createPoint(r.getProjection().toXY(created.getCoor())))) {
                    inside++;
                }
            }
        }
        System.out.println("=== Owyhee: no OSM buildings, Microsoft footprints as hints: buckets=" + r.countByBucket()
                + " hinted=" + hinted + " inside true building=" + inside + " (" + Math.round(100.0 * inside / Math.max(1, hinted)) + "%)");
    }

    @Test
    void cleanupOfUnmergedNodes() throws IOException {
        // The real thing: the snapshot is both source and target; 42 unmerged parcel-centroid
        // nodes, 228 addresses already on buildings, Microsoft footprints as hints.
        DataSet ds = JosmTestSetup.loadDataSet(BED + "snapshot.osm");
        DataSet parcels = oa("oa/parcels.geojson", OpenAddressesReader.Layer.PARCELS);
        DataSet hints = oa("oa/buildings.geojson", OpenAddressesReader.Layer.BUILDINGS);
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(ds, ds, hints, new ParcelCellSource(parcels, "p"), s);
        System.out.println("=== Owyhee cleanup: sources=" + r.getSourceNodes() + " buckets=" + r.countByBucket());
        for (Proposal p : r.getProposals()) {
            String target = p.getTarget() == null ? "-" : p.getTarget().toString();
            double moved = 0;
            if (p.getTarget() != null) {
                moved = p.getTarget().getGeometry().distance(OsmGeometry.factory().createPoint(r.getProjection().toXY(p.getAddresses().get(0).getPosition())));
            }
            System.out.printf(Locale.ROOT, "    %-22s %-34s -> %s  %.0f m  %s%n", p.getBucket(), p.describe(), target, moved, p.getReasons());
        }
        List<Proposal> applicable = r.getProposals().stream().filter(ProposalApplier::isApplicable).collect(Collectors.toList());
        int before = ds.getNodes().size();
        for (Proposal p : applicable) {
            Applied a = ProposalApplier.build(p, ds, ds, r.getProjection(), s);
            if (a != null && a.getTargetCommand() != null) {
                assertTrue(a.getTargetCommand().executeCommand());
            }
        }
        System.out.println("    applied " + applicable.size() + " proposals; node count " + before + " -> " + ds.getNodes().stream().filter(n -> !n.isDeleted()).count());
        assertTrue(r.getSourceNodes() >= 40, "expected the 42 unmerged nodes as sources");
    }
}
