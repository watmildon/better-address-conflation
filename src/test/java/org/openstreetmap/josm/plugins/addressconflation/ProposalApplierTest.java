// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
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
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

class ProposalApplierTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static Proposal only(AnalysisResult r, Bucket b) {
        List<Proposal> ps = r.getProposals().stream().filter(p -> p.getBucket() == b).collect(Collectors.toList());
        assertEquals(1, ps.size(), "expected one " + b + " proposal, got " + r.getProposals());
        return ps.get(0);
    }

    @Test
    void cleanTagsBuildingAndDeletesSourceNode() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Way house = Fixtures.rect(target, 0, 0, 12, 10, "building=house");
        Node addr = Fixtures.node(source, 1, 1, Fixtures.concat(Fixtures.addr("12", "West Olive Avenue"), "testbed:source=way/1"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), s);
        Proposal p = only(r, Bucket.CLEAN);
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), s);
        assertNotNull(a);
        assertNotNull(a.getTargetCommand());
        assertNotNull(a.getSourceCommand());
        assertTrue(a.getTargetCommand().executeCommand());
        assertEquals("12", house.get("addr:housenumber"));
        assertEquals("West Olive Avenue", house.get("addr:street"));
        assertEquals("85302", house.get("addr:postcode"));
        assertNull(house.get("testbed:source"), "only addr:* keys are copied");
        assertTrue(a.getSourceCommand().executeCommand());
        assertTrue(addr.isDeleted());
        a.getTargetCommand().undoCommand();
        assertNull(house.get("addr:housenumber"));
    }

    @Test
    void multiAddressCreatesNodesInsideBuilding() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Way apartments = Fixtures.rect(target, 0, 0, 30, 20, "building=apartments");
        Fixtures.rect(parcels, 0, 0, 60, 50, "oa:pid=P1");
        // three units at the parcel centroid, which is inside the building, plus one outside it
        for (String unit : new String[] {"1", "2", "3"}) {
            Fixtures.node(source, 0, 0, Fixtures.concat(Fixtures.addr("500", "West Olive Avenue"), "addr:unit=" + unit));
        }
        Fixtures.node(source, 25, 20, Fixtures.concat(Fixtures.addr("500", "West Olive Avenue"), "addr:unit=4"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, new ParcelCellSource(parcels, "p"), s);
        Proposal p = only(r, Bucket.MULTI_ADDRESS_BUILDING);
        assertEquals(4, p.getAddresses().size());
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), s);
        assertTrue(a.getTargetCommand().executeCommand());
        List<Node> created = target.getNodes().stream().filter(n -> n.hasKey("addr:unit")).collect(Collectors.toList());
        assertEquals(4, created.size());
        for (Node n : created) {
            assertTrue(OsmGeometry.toPolygon(apartments, r.getProjection()).contains(
                    OsmGeometry.factory().createPoint(r.getProjection().toXY(n.getCoor()))), "node " + n.get("addr:unit") + " is inside the building");
            assertEquals("500", n.get("addr:housenumber"));
        }
        // nodes do not sit on top of each other
        for (int i = 0; i < created.size(); i++) {
            for (int j = i + 1; j < created.size(); j++) {
                assertTrue(created.get(i).getCoor().greatCircleDistance(created.get(j).getCoor()) > 1.5, "nodes spread apart");
            }
        }
        assertNull(apartments.get("addr:housenumber"), "building itself stays untagged");
        assertTrue(a.getSourceCommand().executeCommand());
        assertEquals(0, source.getNodes().stream().filter(n -> !n.isDeleted()).count());
    }

    @Test
    void noBuildingCopiesNode() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Fixtures.rect(target, 200, 200, 10, 10, "building=house");
        Node addr = Fixtures.node(source, 0, 0, Fixtures.addr("9001", "North 52nd Avenue"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), s);
        Proposal p = only(r, Bucket.NO_BUILDING);
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), s);
        assertTrue(a.getTargetCommand().executeCommand());
        Node copy = target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).findFirst().orElseThrow();
        assertEquals("9001", copy.get("addr:housenumber"));
        assertTrue(copy.getCoor().greatCircleDistance(addr.getCoor()) < 0.01);
    }

    @Test
    void sameLayerYieldsOneCommand() {
        DataSet ds = new DataSet();
        Way house = Fixtures.rect(ds, 0, 0, 12, 10, "building=house");
        Node addr = Fixtures.node(ds, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(ds, ds, new VoronoiCellSource(), s);
        Proposal p = only(r, Bucket.CLEAN);
        Applied a = ProposalApplier.build(p, ds, ds, r.getProjection(), s);
        assertNotNull(a.getTargetCommand());
        assertNull(a.getSourceCommand());
        assertTrue(a.getTargetCommand().executeCommand());
        assertEquals("12", house.get("addr:housenumber"));
        assertTrue(addr.isDeleted());
    }

    @Test
    void identicalExistingOnlyDeletesSource() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Fixtures.rect(target, 0, 0, 12, 10, Fixtures.concat(Fixtures.addr("12", "West Olive Avenue"), "building=house"));
        Node addr = Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), s);
        Proposal p = only(r, Bucket.EXISTING_ADDRESS);
        assertTrue(ProposalApplier.isApplicable(p));
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), s);
        assertNull(a.getTargetCommand());
        assertTrue(a.getSourceCommand().executeCommand());
        assertTrue(addr.isDeleted());
    }

    @Test
    void streetVariantIsNotApplicable() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Fixtures.rect(target, 0, 0, 12, 10, Fixtures.concat(Fixtures.addr("12", "W Olive Ave"), "building=house"));
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), s);
        Proposal p = only(r, Bucket.EXISTING_ADDRESS);
        assertFalse(ProposalApplier.isApplicable(p));
        assertNull(ProposalApplier.build(p, target, source, r.getProjection(), s));
    }

    @Test
    void keepSourceNodesWhenPreferenceSaysSo() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Fixtures.rect(target, 0, 0, 12, 10, "building=house");
        Node addr = Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        s.deleteSourceNodes = false;
        AnalysisResult r = Analyzer.analyze(source, target, new VoronoiCellSource(), s);
        Applied a = ProposalApplier.build(only(r, Bucket.CLEAN), target, source, r.getProjection(), s);
        assertNull(a.getSourceCommand());
        assertFalse(addr.isDeleted());
        assertEquals(Collections.emptyList(), source.getNodes().stream().filter(Node::isDeleted).collect(Collectors.toList()));
    }
}
