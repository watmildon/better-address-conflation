// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
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

/** Building footprints from a hint layer guide placement without being imported. */
class HintLayerTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static Proposal only(AnalysisResult r, Bucket b) {
        List<Proposal> ps = r.getProposals().stream().filter(p -> p.getBucket() == b).collect(Collectors.toList());
        assertEquals(1, ps.size(), "expected one " + b + ", got " + r.getProposals());
        return ps.get(0);
    }

    @Test
    void hintPlacesNodeWhereOsmHasNoBuilding() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet hints = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Way footprint = Fixtures.rect(hints, 0, 15, 12, 10, "building=yes");
        Node addr = Fixtures.node(source, 0, -20, Fixtures.addr("12", "West Olive Avenue")); // badly placed, in the yard
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), s);
        Proposal p = only(r, Bucket.CLEAN);
        assertSame(footprint, p.getTarget().getPrimitive());
        assertTrue(p.getTarget().isHint());
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), s);
        assertTrue(a.getTargetCommand().executeCommand());
        Node placed = target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).findFirst().orElseThrow();
        assertEquals("12", placed.get("addr:housenumber"));
        assertTrue(OsmGeometry.toPolygon(footprint, r.getProjection()).contains(
                OsmGeometry.factory().createPoint(r.getProjection().toXY(placed.getCoor()))), "node sits on the hinted footprint");
        assertTrue(hints.getWays().stream().noneMatch(w -> w.hasKey("addr:housenumber")), "hint layer untouched");
        assertEquals(0, target.getWays().size(), "no footprint imported");
        assertTrue(a.getSourceCommand().executeCommand());
        assertTrue(addr.isDeleted());
    }

    @Test
    void addressAlreadyOnTheFootprintMovesToItsCentre() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet hints = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Fixtures.rect(hints, 0, 15, 12, 10, "building=yes");
        Fixtures.node(source, 4, 18, Fixtures.addr("12", "West Olive Avenue")); // inside, near a corner
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), s);
        Proposal p = only(r, Bucket.CLEAN);
        assertTrue(ProposalApplier.build(p, target, source, r.getProjection(), s).getTargetCommand().executeCommand());
        Node placed = target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).findFirst().orElseThrow();
        assertTrue(placed.getCoor().greatCircleDistance(Fixtures.at(0, 15)) < 0.5,
                "placed " + placed.getCoor().greatCircleDistance(Fixtures.at(0, 15)) + " m from the footprint centre");
    }

    @Test
    void lShapedFootprintStillGetsTheNodeInside() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet hints = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 60, 60, "oa:pid=A");
        // Thin L: its centroid lies in the empty corner.
        Way footprint = Fixtures.polygon(hints, new String[] {"building=yes"},
                Fixtures.at(0, 0), Fixtures.at(20, 0), Fixtures.at(20, 3), Fixtures.at(3, 3), Fixtures.at(3, 20), Fixtures.at(0, 20));
        Fixtures.node(source, 10, -10, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), s);
        Proposal p = only(r, Bucket.CLEAN);
        assertTrue(ProposalApplier.build(p, target, source, r.getProjection(), s).getTargetCommand().executeCommand());
        Node placed = target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).findFirst().orElseThrow();
        assertTrue(OsmGeometry.toPolygon(footprint, r.getProjection()).contains(
                OsmGeometry.factory().createPoint(r.getProjection().toXY(placed.getCoor()))), "node sits on the L");
    }

    @Test
    void hintBeatsALoneShedButNotAHouse() {
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        DataSet hints = new DataSet();
        Way footprint = Fixtures.rect(hints, 0, 15, 12, 10, "building=yes");

        DataSet target = new DataSet();
        Fixtures.rect(target, 0, -20, 4, 4, "building=shed");
        DataSet source = new DataSet();
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        Proposal p = only(Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), new ConflationSettings()), Bucket.CLEAN);
        assertSame(footprint, p.getTarget().getPrimitive());
        assertTrue(p.getReasons().get(0).contains("building=shed"), p.getReasons().toString());

        DataSet target2 = new DataSet();
        Way house = Fixtures.rect(target2, 0, 15, 11, 9, "building=house");
        DataSet source2 = new DataSet();
        Fixtures.node(source2, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        p = only(Analyzer.analyze(source2, target2, hints, new ParcelCellSource(parcels, "p"), new ConflationSettings()), Bucket.CLEAN);
        assertSame(house, p.getTarget().getPrimitive());
    }

    @Test
    void cleanupMovesExistingNodesInPlace() {
        // The edit layer is both source and target: a badly placed OSM address node gets moved
        // onto the hinted footprint, not deleted and recreated.
        DataSet ds = new DataSet();
        DataSet hints = new DataSet();
        Way footprint = Fixtures.rect(hints, 0, 15, 12, 10, "building=yes");
        Node addr = Fixtures.node(ds, 0, -10, Fixtures.addr("12", "West Olive Avenue"));
        LatLon before = addr.getCoor();
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(ds, ds, hints, new VoronoiCellSource(), s);
        Proposal p = only(r, Bucket.CLEAN);
        Applied a = ProposalApplier.build(p, ds, ds, r.getProjection(), s);
        assertNull(a.getSourceCommand());
        assertTrue(a.getTargetCommand().executeCommand());
        assertTrue(addr.isUsable() && !addr.isDeleted(), "same node survives");
        assertTrue(addr.getCoor().greatCircleDistance(before) > 15, "and it moved");
        assertEquals(1, ds.getNodes().size(), "no extra node created");
        a.getTargetCommand().undoCommand();
        assertEquals(before, addr.getCoor());
    }

    @Test
    void realFootprintsRecoverBuildingPositionsWithoutOsmBuildings() throws IOException {
        // Remove every OSM building; use the city's footprint layer as hints. Score: does the
        // placed node land inside the OSM building that really carries that address?
        DataSet snapshot = JosmTestSetup.loadDataSet("glendale-olive/snapshot.osm");
        DataSet target = JosmTestSetup.loadDataSet("glendale-olive/buildings-stripped.osm");
        for (Way w : target.getWays().stream().filter(w -> w.hasKey("building")).collect(Collectors.toList())) {
            w.setDeleted(true);
        }
        DataSet addresses = JosmTestSetup.loadDataSet("glendale-olive/addresses-full-parcel.osm");
        DataSet hints;
        try (InputStream is = JosmTestSetup.resource("glendale-olive/oa/buildings.geojson")) {
            hints = OpenAddressesReader.read(is, OpenAddressesReader.Layer.BUILDINGS, false);
        }
        DataSet parcels;
        try (InputStream is = JosmTestSetup.resource("glendale-olive/oa/parcels.geojson")) {
            parcels = OpenAddressesReader.read(is, OpenAddressesReader.Layer.PARCELS, false);
        }
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(addresses, target, hints, new ParcelCellSource(parcels, "p"), s);
        int hinted = 0;
        int inside = 0;
        int noBuilding = 0;
        for (Proposal p : r.getProposals()) {
            if (p.getBucket() == Bucket.NO_BUILDING) {
                noBuilding++;
            }
            if (p.getTarget() == null || !p.getTarget().isHint() || p.getBucket() != Bucket.CLEAN) {
                continue; // ambiguous stacks (the 206-unit complex) go to review, not to the metric
            }
            Applied a = ProposalApplier.build(p, target, addresses, r.getProjection(), s);
            if (a == null || a.getTargetCommand() == null) {
                continue;
            }
            a.getTargetCommand().executeCommand();
            for (AddressGroup g : p.getAddresses()) {
                String src = g.getPrimary().get("testbed:source");
                if (src == null || !src.startsWith("way/")) {
                    continue;
                }
                OsmPrimitive truth = snapshot.getPrimitiveById(Long.parseLong(src.substring(4)), org.openstreetmap.josm.data.osm.OsmPrimitiveType.WAY);
                hinted++;
                // find the node we just created for this group: same tags, newest
                Node created = target.getNodes().stream().filter(n -> !n.isDeleted() && n.hasKey("addr:housenumber")
                        && g.getTags().get("addr:housenumber").equals(n.get("addr:housenumber"))
                        && g.getTags().getOrDefault("addr:unit", "").equals(java.util.Objects.toString(n.get("addr:unit"), ""))
                        && g.getTags().get("addr:street").equals(n.get("addr:street"))).reduce((x, y) -> y).orElse(null);
                if (created != null && truth instanceof Way && OsmGeometry.toPolygon(truth, r.getProjection()) != null
                        && OsmGeometry.toPolygon(truth, r.getProjection()).contains(OsmGeometry.factory().createPoint(r.getProjection().toXY(created.getCoor())))) {
                    inside++;
                }
            }
        }
        System.out.println("HINTS glendale: buckets=" + r.countByBucket() + " hinted-with-truth=" + hinted + " placed inside true building=" + inside
                + " (" + Math.round(100.0 * inside / Math.max(1, hinted)) + "%) noBuilding=" + noBuilding);
        assertTrue(hinted > 700, "hinted " + hinted);
        assertTrue(inside > hinted * 0.95, "inside " + inside + " of " + hinted);
    }
}
