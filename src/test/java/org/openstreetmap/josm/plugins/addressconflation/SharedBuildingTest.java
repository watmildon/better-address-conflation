// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * An address goes on a building outline only when it is the building's only address. With
 * another one in the building, from the source or already in OSM, every address stays a node
 * where the source has it.
 */
class SharedBuildingTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private final DataSet target = new DataSet();
    private final DataSet source = new DataSet();
    private final DataSet parcels = new DataSet();
    private final ConflationSettings settings = new ConflationSettings();
    private Way building;

    /** One parcel with one building, x -10..10, y 0..12. */
    private void lot() {
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        building = Fixtures.rect(target, 0, 6, 20, 12, "building=yes");
    }

    private static String[] addr(String number) {
        return new String[] {"addr:housenumber=" + number, "addr:street=Watson Circle"};
    }

    private AnalysisResult run() {
        return Analyzer.analyze(source, target, null, new ParcelCellSource(parcels, "p"), settings);
    }

    private static Proposal proposalFor(AnalysisResult r, Node n) {
        return r.getProposals().stream().filter(p -> p.getSourceNodes().contains(n)).findFirst()
                .orElseThrow(() -> new AssertionError("no proposal for " + n + " in " + r.getProposals()));
    }

    /** Apply the proposal and return the address nodes it left in the edit layer. */
    private List<Node> apply(AnalysisResult r, Proposal p) {
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), settings);
        assertTrue(a.getTargetCommand().executeCommand());
        return target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber") && n.isNew()).collect(Collectors.toList());
    }

    @Test
    void besideADuplicateOfAnExistingAddressTheOtherStaysANode() {
        lot();
        Fixtures.node(target, -4, 6, addr("10")); // OSM already has 10 as a node in the building
        Node dup = Fixtures.node(source, -4, 6, addr("10"));
        Node fresh = Fixtures.node(source, 4, 6, addr("12"));
        AnalysisResult r = run();
        assertFalse(proposalFor(r, dup).getExisting().isEmpty(), "10 is already mapped");
        Proposal p = proposalFor(r, fresh);
        assertEquals(Bucket.MULTI_ADDRESS_BUILDING, p.getBucket(), p.getReasons().toString());
        assertTrue(p.keepsNode());
        List<Node> added = apply(r, p);
        assertFalse(building.hasKey("addr:housenumber"), "the outline is not tagged");
        assertEquals(1, added.stream().filter(n -> "12".equals(n.get("addr:housenumber"))).count());
    }

    @Test
    void besideAnAmenityWithAnAddressTheNewAddressStaysANodeWhereTheSourceHasIt() {
        lot();
        Fixtures.node(target, -4, 6, Fixtures.concat(addr("10"), "amenity=cafe", "name=Corner Cafe"));
        Node fresh = Fixtures.node(source, 4, 8, addr("12"));
        LatLon where = fresh.getCoor();
        AnalysisResult r = run();
        Proposal p = proposalFor(r, fresh);
        assertTrue(p.keepsNode(), p.getReasons().toString());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("OSM already has an address inside")), p.getReasons().toString());
        List<Node> added = apply(r, p).stream().filter(n -> "12".equals(n.get("addr:housenumber"))).collect(Collectors.toList());
        assertFalse(building.hasKey("addr:housenumber"));
        assertEquals(1, added.size());
        assertTrue(added.get(0).getCoor().greatCircleDistance(where) < 0.01, "stays where the source has it");
    }

    @Test
    void anotherSourceAddressInTheBuildingInAnotherBucketKeepsThisOneANode() {
        lot();
        // 14 is spelled differently in OSM nearby, so it goes to Existing address for review.
        Fixtures.node(target, 0, -20, "addr:housenumber=14", "addr:street=Watson Cir");
        Node variant = Fixtures.node(source, 4, 6, addr("14"));
        Node fresh = Fixtures.node(source, -4, 6, addr("12"));
        AnalysisResult r = run();
        assertEquals(Bucket.EXISTING_ADDRESS, proposalFor(r, variant).getBucket());
        Proposal p = proposalFor(r, fresh);
        assertTrue(p.keepsNode());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("Another source address is inside this building (14 Watson Circle)")),
                p.getReasons().toString());
    }

    @Test
    void theOnlyAddressStillTagsTheOutline() {
        lot();
        // An address on an entrance of the outline belongs to the building, not beside it.
        Node entrance = building.getNode(0);
        entrance.put("entrance", "main");
        entrance.put("addr:housenumber", "12");
        entrance.put("addr:street", "Watson Circle");
        Node fresh = Fixtures.node(source, 0, 6, addr("12"));
        AnalysisResult r = run();
        Proposal p = proposalFor(r, fresh);
        assertFalse(p.keepsNode(), p.getReasons().toString());
    }

    @Test
    void anAddressMergedByHandAfterTheAnalysisIsNoticedAtApply() {
        lot();
        Node fresh = Fixtures.node(source, 4, 6, addr("12"));
        AnalysisResult r = run();
        Proposal p = proposalFor(r, fresh);
        assertEquals(Bucket.CLEAN, p.getBucket());
        Fixtures.node(target, -4, 6, addr("10")); // the mapper merged 10 into OSM meanwhile
        List<Node> added = apply(r, p);
        assertFalse(building.hasKey("addr:housenumber"), "a second address in the building: no tagging the outline");
        assertEquals(1, added.stream().filter(n -> "12".equals(n.get("addr:housenumber"))).count());
    }

    @Test
    void aLoneUnitNeedsACheckAndStaysANode() {
        lot();
        Node unit = Fixtures.node(source, 4, 6, Fixtures.concat(addr("12"), "addr:unit=2"));
        AnalysisResult r = run();
        Proposal p = proposalFor(r, unit);
        assertEquals(Bucket.REVIEW, p.getBucket(), p.getReasons().toString());
        assertTrue(p.keepsNode());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("Has a unit (addr:unit=2)")), p.getReasons().toString());
        apply(r, p);
        assertFalse(building.hasKey("addr:unit"), "the outline does not take one unit's address");
    }

    @Test
    void unitsWithOutlinesOfTheirOwnGoOnThem() {
        // Each unit of 5201 drawn as its own outline, as in Glendale's apartment complex.
        Fixtures.rect(parcels, 0, 0, 20, 40, "oa:pid=A");
        Fixtures.rect(parcels, 20, 0, 20, 40, "oa:pid=B");
        Way unitA = Fixtures.rect(target, 0, 0, 8, 8, "building=apartments");
        Fixtures.rect(target, 20, 0, 8, 8, "building=apartments");
        Node one = Fixtures.node(source, 0, 0, Fixtures.concat(addr("5201"), "addr:unit=1"));
        Fixtures.node(source, 20, 0, Fixtures.concat(addr("5201"), "addr:unit=2"));
        AnalysisResult r = run();
        Proposal p = proposalFor(r, one);
        assertEquals(Bucket.CLEAN, p.getBucket(), p.getReasons().toString());
        assertFalse(p.keepsNode());
        apply(r, p);
        assertEquals("1", unitA.get("addr:unit"));
    }

    @Test
    void anOutlineCarryingAnotherAddressSaysHowToSortItOut() {
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Fixtures.rect(target, 0, 6, 20, 12, Fixtures.concat(addr("10"), "building=yes"));
        Node fresh = Fixtures.node(source, 4, 6, addr("12"));
        Proposal p = proposalFor(run(), fresh);
        assertEquals(Bucket.EXISTING_ADDRESS, p.getBucket());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("move the outline's address onto a node of its own")),
                p.getReasons().toString());
    }

    @Test
    void besideABuildingNodeWithAnotherAddressTheNewOneStaysANode() {
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Node house = Fixtures.node(target, 0, 0, "building=house");
        Fixtures.node(target, -3, 2, Fixtures.concat(addr("10"), "shop=bakery"));
        Node fresh = Fixtures.node(source, 0, 0, addr("12")); // right on top of the building node
        AnalysisResult r = run();
        Proposal p = proposalFor(r, fresh);
        assertTrue(p.keepsNode(), p.getReasons().toString());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("within 6 m of this building node")), p.getReasons().toString());
        Node placed = apply(r, p).stream().filter(n -> "12".equals(n.get("addr:housenumber"))).findFirst().orElseThrow();
        assertFalse(house.hasKey("addr:housenumber"), "the building node is not tagged");
        double off = placed.getCoor().greatCircleDistance(house.getCoor());
        assertTrue(off > 1.5 && off < 2.5, "a couple of metres off the building node, not on top of it: " + off);
    }

    @Test
    void pointsInsideStayPutEvenWhenClose() {
        lot();
        Node a = Fixtures.node(source, 2, 6, addr("12"));
        Node b = Fixtures.node(source, 3, 6, addr("14")); // a metre apart: units, not stacked
        LatLon whereA = a.getCoor();
        LatLon whereB = b.getCoor();
        AnalysisResult r = run();
        Proposal p = proposalFor(r, a);
        assertEquals(Bucket.MULTI_ADDRESS_BUILDING, p.getBucket());
        List<Node> added = apply(r, p);
        assertEquals(2, added.size());
        assertTrue(added.stream().anyMatch(n -> n.getCoor().greatCircleDistance(whereA) < 0.01));
        assertTrue(added.stream().anyMatch(n -> n.getCoor().greatCircleDistance(whereB) < 0.01));
    }
}
