// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.BuildingCandidate;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/** Buildings mapped as a node, and features with addresses in cleanup mode. */
class BuildingNodeTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static List<Proposal> in(AnalysisResult r, Bucket b) {
        return r.getProposals().stream().filter(p -> p.getBucket() == b).collect(Collectors.toList());
    }

    private static void run(Applied a) {
        assertNotNull(a);
        if (a.getTargetCommand() != null) {
            a.getTargetCommand().executeCommand();
        }
        if (a.getSourceCommand() != null) {
            a.getSourceCommand().executeCommand();
        }
    }

    @Test
    void cleanupLeavesFeatureNodesAlone() {
        // A cafe mapped as a node with its address, beside an outline. Folding the address
        // into the outline and deleting the node used to drop amenity, name and building.
        DataSet ds = new DataSet();
        Way outline = Fixtures.rect(ds, 0, 10, 12, 10, "building=yes");
        Node cafe = Fixtures.node(ds, 0, 0, Fixtures.concat(Fixtures.addr("12", "Main Street"),
                "building=house", "amenity=cafe", "name=Joe's"));
        Node plain = Fixtures.node(ds, 60, 0, Fixtures.concat(Fixtures.addr("20", "Main Street"), "source=county"));
        Way other = Fixtures.rect(ds, 60, 10, 12, 10, "building=yes");
        AnalysisResult r = Analyzer.analyze(ds, ds, new VoronoiCellSource(), new ConflationSettings());
        assertEquals(1, r.getSourceNodes(), "only the plain address node is a source");
        Proposal p = in(r, Bucket.CLEAN).get(0);
        assertSame(other, p.getTarget().getPrimitive());
        run(ProposalApplier.build(p, ds, ds, r.getProjection(), new ConflationSettings()));
        assertTrue(plain.isDeleted());
        assertEquals("20", other.get("addr:housenumber"));
        assertFalse(cafe.isDeleted());
        assertEquals("cafe", cafe.get("amenity"));
        assertNull(outline.get("addr:housenumber"));
    }

    @Test
    void plainAddressNodeTest() {
        DataSet ds = new DataSet();
        assertTrue(ConflationSettings.isPlainAddressNode(Fixtures.node(ds, 0, 0,
                Fixtures.concat(Fixtures.addr("1", "A Street"), "source=NAD", "source:date=2024", "note=check", "check_date=2026-01-01"))));
        assertFalse(ConflationSettings.isPlainAddressNode(Fixtures.node(ds, 0, 0,
                Fixtures.concat(Fixtures.addr("1", "A Street"), "building=house"))));
        assertFalse(ConflationSettings.isPlainAddressNode(Fixtures.node(ds, 0, 0,
                Fixtures.concat(Fixtures.addr("1", "A Street"), "name=The Oaks"))));
    }

    @Test
    void soleBuildingNodeGetsTheAddress() {
        DataSet target = new DataSet();
        Node house = Fixtures.node(target, 0, 8, "building=house");
        DataSet source = new DataSet();
        Node nad = Fixtures.node(source, 0, 0, Fixtures.addr("14", "Main Street"));
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), new ConflationSettings());
        Proposal p = in(r, Bucket.CLEAN).get(0);
        assertSame(house, p.getTarget().getPrimitive());
        assertTrue(p.getTarget().isNode());
        run(ProposalApplier.build(p, target, source, r.getProjection(), new ConflationSettings()));
        assertEquals("14", house.get("addr:housenumber"));
        assertEquals("house", house.get("building"));
        assertEquals(1, target.getNodes().size(), "no extra address node beside the building node");
        assertTrue(nad.isDeleted());
    }

    @Test
    void cleanupFoldsLooseAddressIntoBuildingNode() {
        DataSet ds = new DataSet();
        Node house = Fixtures.node(ds, 0, 8, "building=house");
        Node loose = Fixtures.node(ds, 0, 0, Fixtures.addr("16", "Main Street"));
        AnalysisResult r = Analyzer.analyze(ds, ds, new VoronoiCellSource(), new ConflationSettings());
        Proposal p = in(r, Bucket.CLEAN).get(0);
        assertSame(house, p.getTarget().getPrimitive());
        run(ProposalApplier.build(p, ds, ds, r.getProjection(), new ConflationSettings()));
        assertEquals("16", house.get("addr:housenumber"));
        assertTrue(loose.isDeleted());
    }

    @Test
    void severalBuildingNodesNeedAPick() {
        // A parcel with two building nodes: the nearer one is the garage. No default.
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Node garage = Fixtures.node(target, 0, 5, "building=garage");
        Node house = Fixtures.node(target, 0, 20, "building=house");
        Fixtures.node(source, 0, 0, Fixtures.addr("18", "Main Street"));
        AnalysisResult r = Analyzer.analyze(source, target, new ParcelCellSource(parcels, "p"), new ConflationSettings());
        Proposal p = in(r, Bucket.AMBIGUOUS_BUILDING).get(0);
        assertTrue(p.requiresPick());
        assertNull(p.getTarget());
        assertEquals(2, p.getCandidates().size());
        assertTrue(p.getHighlightPrimitives().contains(garage) && p.getHighlightPrimitives().contains(house),
                "both candidates are shown so the mapper can pick");
        assertNull(ProposalApplier.build(p, target, source, r.getProjection(), new ConflationSettings()), "no pick, no action");
        BuildingCandidate picked = p.getCandidates().stream().map(c -> c.getBuilding())
                .filter(b -> b.getPrimitive() == house).findFirst().get();
        run(ProposalApplier.build(p, picked, target, source, r.getProjection(), new ConflationSettings()));
        assertEquals("18", house.get("addr:housenumber"));
        assertNull(garage.get("addr:housenumber"));
    }

    @Test
    void outlineBeatsBuildingNode() {
        DataSet target = new DataSet();
        Way outline = Fixtures.rect(target, 0, 12, 12, 10, "building=yes");
        Fixtures.node(target, 0, 4, "building=house");
        DataSet source = new DataSet();
        Fixtures.node(source, 0, 0, Fixtures.addr("22", "Main Street"));
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), new ConflationSettings());
        assertSame(outline, in(r, Bucket.CLEAN).get(0).getTarget().getPrimitive());
    }

    @Test
    void buildingNodeBeatsHintFootprint() {
        DataSet target = new DataSet();
        Node house = Fixtures.node(target, 0, 10, "building=house");
        DataSet hints = new DataSet();
        Fixtures.rect(hints, 0, 10, 14, 12, "building=yes");
        DataSet source = new DataSet();
        Fixtures.node(source, 0, 0, Fixtures.addr("24", "Main Street"));
        AnalysisResult r = Analyzer.analyze(source, target, hints, new VoronoiCellSource(), new ConflationSettings());
        assertTrue(r.getProposals().stream().noneMatch(p -> p.getTarget() != null && p.getTarget().isHint()), "no row uses the hint");
        assertSame(house, in(r, Bucket.CLEAN).get(0).getTarget().getPrimitive());
    }

    @Test
    void severalAddressesAtOneBuildingNodeStayNodes() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Node duplex = Fixtures.node(target, 0, 10, "building=house");
        Fixtures.node(source, -5, 0, Fixtures.addr("30", "Main Street"));
        Fixtures.node(source, 5, 0, Fixtures.addr("32", "Main Street"));
        AnalysisResult r = Analyzer.analyze(source, target, new ParcelCellSource(parcels, "p"), new ConflationSettings());
        Proposal p = in(r, Bucket.MULTI_ADDRESS_BUILDING).get(0);
        assertSame(duplex, p.getTarget().getPrimitive());
        run(ProposalApplier.build(p, target, source, r.getProjection(), new ConflationSettings()));
        assertNull(duplex.get("addr:housenumber"), "the building node is not given one of several addresses");
        List<Node> added = target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).collect(Collectors.toList());
        assertEquals(2, added.size());
        for (Node n : added) {
            assertTrue(n.getCoor().greatCircleDistance(duplex.getCoor()) < 10, "placed beside the building node");
        }
    }
}
