// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.addressconflation.validation.HousenumberSequenceTest;
import org.openstreetmap.josm.spi.preferences.Config;

class HousenumberSequenceValidatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String ELM = "Elm Street";

    private static List<TestError> run(DataSet ds) {
        HousenumberSequenceTest test = new HousenumberSequenceTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.endTest();
        return test.getErrors();
    }

    private static List<TestError> withCode(List<TestError> errors, int code) {
        return errors.stream().filter(e -> e.getCode() == code).collect(Collectors.toList());
    }

    /** East-west road along y, from x0 to x1. */
    private static Way road(DataSet ds, String name, double y, double x0, double x1) {
        return Fixtures.way(ds, new String[] {"highway=residential", "name=" + name}, Fixtures.at(x0, y), Fixtures.at(x1, y));
    }

    /** Address nodes along y, 20 m apart from x = 0. */
    private static List<Node> houses(DataSet ds, String street, double y, String... numbers) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < numbers.length; i++) {
            out.add(Fixtures.node(ds, i * 20.0, y, Fixtures.addr(numbers[i], street)));
        }
        return out;
    }

    private static Way wayThrough(DataSet ds, List<Node> nodes, String... tags) {
        Way w = new Way();
        w.setNodes(nodes);
        ds.addPrimitive(w);
        Fixtures.tag(w, tags);
        return w;
    }

    private static TestError single(List<TestError> errors, int code, OsmPrimitive p) {
        assertEquals(1, errors.size(), errors.toString());
        TestError e = errors.get(0);
        assertEquals(code, e.getCode(), e.getDescription());
        assertEquals(Collections.singletonList(p), List.copyOf(e.getPrimitives()));
        return e;
    }

    @Test
    void orderlyStreetPasses() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4", "6", "8", "10");
        houses(ds, ELM, -25, "1", "3", "5", "7", "9");
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void oddNumberOnEvenSideIsFlagged() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        List<Node> north = houses(ds, ELM, 25, "2", "4", "6", "7", "10");
        TestError e = single(run(ds), HousenumberSequenceTest.CODE_PARITY, north.get(3));
        assertTrue(e.getDescription().contains("7"), e.getDescription());
        assertTrue(e.getDescription().contains("even"), e.getDescription());
        assertTrue(e.getDescription().contains(ELM), e.getDescription());
        assertFalse(e.getMessage().contains("edge"), e.getMessage());
    }

    @Test
    void parityBelowThresholdsIsSilent() {
        DataSet mixed = new DataSet();
        road(mixed, ELM, 0, -500, 500);
        // 3 of 5 even: 60 %, under the 80 % bar.
        houses(mixed, ELM, 25, "2", "4", "5", "7", "10");
        assertTrue(withCode(run(mixed), HousenumberSequenceTest.CODE_PARITY).isEmpty());

        DataSet few = new DataSet();
        road(few, ELM, 0, -500, 500);
        // Only three numbered addresses on the side.
        houses(few, ELM, 25, "2", "4", "5");
        assertTrue(run(few).isEmpty());
    }

    @Test
    void parityCheckCanBeTurnedOff() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4", "6", "7", "10");
        try {
            Config.getPref().putBoolean(HousenumberSequenceTest.PREF_PARITY, false);
            assertTrue(run(ds).isEmpty());
        } finally {
            Config.getPref().put(HousenumberSequenceTest.PREF_PARITY, null);
        }
        assertEquals(1, run(ds).size());
    }

    @Test
    void localInversionIsFlaggedWithItsNeighbours() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        List<Node> north = houses(ds, ELM, 25, "2", "4", "6", "18", "10", "12", "14");
        TestError e = single(run(ds), HousenumberSequenceTest.CODE_SEQUENCE, north.get(3));
        assertTrue(e.getHighlighted().contains(north.get(2)), e.getHighlighted().toString());
        assertTrue(e.getHighlighted().contains(north.get(4)), e.getHighlighted().toString());
        assertTrue(e.getDescription().contains("18 between 6 and 10"), e.getDescription());
    }

    @Test
    void decreasingStreetUsesItsOwnDirection() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        List<Node> north = houses(ds, ELM, 25, "14", "12", "10", "2", "8", "6", "4");
        single(run(ds), HousenumberSequenceTest.CODE_SEQUENCE, north.get(3));
    }

    @Test
    void equalNumbersAreNeverAnInversion() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4", "6", "6", "8", "8", "10");
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void suffixesAndRangesUseTheLeadingNumber() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4A", "6 1/2", "8-12", "10;12", "Rear", "14");
        assertTrue(run(ds).isEmpty(), run(ds).toString());

        DataSet bad = new DataSet();
        road(bad, ELM, 0, -500, 500);
        List<Node> north = houses(bad, ELM, 25, "2", "4", "16B", "8", "10");
        single(run(bad), HousenumberSequenceTest.CODE_SEQUENCE, north.get(2));
    }

    @Test
    void streetSplitIntoWaysIsOneChain() {
        DataSet ds = new DataSet();
        Node a = Fixtures.node(ds, -300, 0);
        Node b = Fixtures.node(ds, -100, 0);
        Node c = Fixtures.node(ds, 100, 0);
        Node d = Fixtures.node(ds, 300, 0);
        String[] tags = {"highway=residential", "name=" + ELM};
        wayThrough(ds, List.of(a, b), tags);
        wayThrough(ds, List.of(c, b), tags); // drawn the other way
        wayThrough(ds, List.of(c, d), tags);
        // Two houses per way: no single way could see the inversion.
        String[] numbers = {"2", "4", "16", "8", "10", "12"};
        double[] xs = {-250, -150, -50, 50, 150, 250};
        List<Node> houses = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            houses.add(Fixtures.node(ds, xs[i], 25, Fixtures.addr(numbers[i], ELM)));
        }
        single(run(ds), HousenumberSequenceTest.CODE_SEQUENCE, houses.get(2));
    }

    @Test
    void dividedRoadHalvesAreNotJoinedIntoAUTurn() {
        DataSet ds = new DataSet();
        Node west = Fixtures.node(ds, -300, 0);
        Node east = Fixtures.node(ds, 300, 0);
        String[] tags = {"highway=residential", "name=" + ELM, "oneway=yes"};
        wayThrough(ds, List.of(west, Fixtures.node(ds, -250, -10), Fixtures.node(ds, 250, -10), east), tags);
        wayThrough(ds, List.of(east, Fixtures.node(ds, 250, 10), Fixtures.node(ds, -250, 10), west), tags);
        houses(ds, ELM, 40, "2", "4", "6", "8", "10");
        houses(ds, ELM, -40, "1", "3", "5", "7", "9");
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void cornerLotCloserToTheCrossStreetIsSkipped() {
        for (boolean withOak : new boolean[] {true, false}) {
            DataSet ds = new DataSet();
            road(ds, ELM, 0, -500, 500);
            houses(ds, ELM, 25, "2", "4", "6", "8", "10");
            Fixtures.node(ds, 150, 25, Fixtures.addr("12", ELM));
            // 90 m back from Elm and 20 m from Oak: out of order, but a corner lot.
            Node corner = Fixtures.node(ds, 110, 90, Fixtures.addr("40", ELM));
            if (withOak) {
                Fixtures.way(ds, new String[] {"highway=residential", "name=Oak Street"}, Fixtures.at(130, 0), Fixtures.at(130, 300));
                assertTrue(run(ds).isEmpty(), run(ds).toString());
            } else {
                single(run(ds), HousenumberSequenceTest.CODE_SEQUENCE, corner);
            }
        }
    }

    @Test
    void addressBeyondTheRadiusIsIgnoredUntilItGrows() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4", "6", "8");
        Node far = Fixtures.node(ds, -30, 150, Fixtures.addr("7", ELM));
        assertTrue(run(ds).isEmpty());
        try {
            Config.getPref().putDouble(HousenumberSequenceTest.PREF_RADIUS, 200.0);
            single(run(ds), HousenumberSequenceTest.CODE_PARITY, far);
        } finally {
            Config.getPref().put(HousenumberSequenceTest.PREF_RADIUS, null);
        }
    }

    @Test
    void addressPastTheEndOfTheStreetHasNoSide() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -10, 100);
        houses(ds, ELM, 25, "2", "4", "6", "8");
        // At the head of the street, more ahead of it than beside it.
        Fixtures.node(ds, 150, 5, Fixtures.addr("7", ELM));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void shopsAndOutbuildingsTakeNoPart() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4", "6", "8");
        Fixtures.node(ds, 90, 25, Fixtures.concat(Fixtures.addr("7", ELM), "shop=bakery"));
        Fixtures.rect(ds, 110, 40, 6, 6, Fixtures.concat(Fixtures.addr("9", ELM), "building=garage"));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void unitNodeInsideItsBuildingIsNotAnInversion() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        Fixtures.node(ds, 0, 25, Fixtures.addr("2", ELM));
        Fixtures.node(ds, 20, 25, Fixtures.addr("4", ELM));
        Fixtures.rect(ds, 60, 25, 40, 20, Fixtures.concat(Fixtures.addr("6", ELM), "building=apartments"));
        // 15 m along from the building's centre, but within its frontage.
        Fixtures.node(ds, 45, 25, Fixtures.addr("30", ELM));
        Fixtures.node(ds, 100, 25, Fixtures.addr("8", ELM));
        Fixtures.node(ds, 120, 25, Fixtures.addr("10", ELM));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void closeNeighboursAreNotOutOfOrder() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        Fixtures.node(ds, 0, 25, Fixtures.addr("2", ELM));
        Fixtures.node(ds, 20, 25, Fixtures.addr("4", ELM));
        Fixtures.node(ds, 40, 25, Fixtures.addr("8", ELM));
        Fixtures.node(ds, 43, 25, Fixtures.addr("6", ELM));
        Fixtures.node(ds, 60, 25, Fixtures.addr("10", ELM));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void spellingVariantsShareAStreet() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        Fixtures.node(ds, 0, 25, Fixtures.addr("2", "Elm St"));
        Fixtures.node(ds, 20, 25, Fixtures.addr("4", ELM));
        Fixtures.node(ds, 40, 25, Fixtures.addr("6", "elm street"));
        Node odd = Fixtures.node(ds, 60, 25, Fixtures.addr("7", ELM));
        Fixtures.node(ds, 80, 25, Fixtures.addr("10", ELM));
        single(run(ds), HousenumberSequenceTest.CODE_PARITY, odd);
    }

    @Test
    void resultNearTheDownloadEdgeSaysSo() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        List<Node> north = houses(ds, ELM, 25, "2", "4", "6", "7", "10");
        ds.addDataSource(new DataSource(new Bounds(Fixtures.at(-1000, -1000), Fixtures.at(100, 1000)), "test"));
        TestError e = single(run(ds), HousenumberSequenceTest.CODE_PARITY, north.get(3));
        assertTrue(e.getMessage().contains("edge of downloaded area"), e.getMessage());
    }

    @Test
    void onlyVisitedAddressesAreReported() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        List<Node> north = houses(ds, ELM, 25, "2", "4", "6", "7", "10");
        HousenumberSequenceTest test = new HousenumberSequenceTest();
        test.startTest(null);
        test.visit(List.of(north.get(0), north.get(3)));
        test.endTest();
        single(test.getErrors(), HousenumberSequenceTest.CODE_PARITY, north.get(3));

        test.startTest(null);
        test.visit(List.of(north.get(0)));
        test.endTest();
        assertTrue(test.getErrors().isEmpty());
    }

    @Test
    void degenerateGeometryDoesNotThrow() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        houses(ds, ELM, 25, "2", "4", "6", "8");
        Node unplaced = new Node();
        Fixtures.tag(unplaced, Fixtures.addr("3", ELM));
        Node moved = Fixtures.node(ds, 0, 0, Fixtures.addr("5", ELM));
        moved.setCoor(null);
        Relation empty = new Relation();
        ds.addPrimitive(empty);
        Fixtures.tag(empty, Fixtures.concat(Fixtures.addr("9", ELM), "type=multipolygon", "building=yes"));
        Way noNodes = new Way();
        ds.addPrimitive(noNodes);
        Relation hollow = new Relation();
        hollow.addMember(new RelationMember("outer", noNodes));
        ds.addPrimitive(hollow);
        Fixtures.tag(hollow, Fixtures.concat(Fixtures.addr("11", ELM), "type=multipolygon", "building=yes"));
        Node incomplete = new Node(987654321L);
        ds.addPrimitive(incomplete);
        wayThrough(ds, List.of(Fixtures.node(ds, 0, 50), incomplete), Fixtures.concat(Fixtures.addr("13", ELM), "building=yes"));
        // A road with one usable node.
        Node lonely = Fixtures.node(ds, 0, 400);
        Node lost = new Node(987654322L);
        ds.addPrimitive(lost);
        wayThrough(ds, List.of(lonely, lost), "highway=residential", "name=" + ELM);

        HousenumberSequenceTest test = new HousenumberSequenceTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.visit(Collections.<OsmPrimitive>singletonList(unplaced));
        test.endTest();
        assertTrue(test.getErrors().isEmpty(), test.getErrors().toString());
    }

    private static final String BAR = "Bar Court";
    private static final String ASH = "Ash Court";

    @Test
    void houseAtTheHeadOfACulDeSacIsKept() {
        DataSet ds = new DataSet();
        // South to north, from Foo Street to a dead end.
        Way bar = wayThrough(ds, List.of(Fixtures.node(ds, 0, 0), Fixtures.node(ds, 0, 140)),
                "highway=residential", "name=" + BAR);
        wayThrough(ds, List.of(Fixtures.node(ds, -200, 0), bar.firstNode(), Fixtures.node(ds, 200, 0)),
                "highway=residential", "name=Foo Street");
        Fixtures.node(ds, 15, 20, Fixtures.addr("102", BAR));
        Node n106 = Fixtures.node(ds, 15, 60, Fixtures.addr("106", BAR));
        Node n104 = Fixtures.node(ds, 15, 100, Fixtures.addr("104", BAR));
        // 17 m past the end and 4 m east of its line: more ahead than beside.
        Fixtures.node(ds, 4, 157, Fixtures.addr("108", BAR));
        Fixtures.node(ds, -15, 20, Fixtures.addr("103", BAR));
        Fixtures.node(ds, -15, 60, Fixtures.addr("105", BAR));
        Fixtures.node(ds, -15, 100, Fixtures.addr("107", BAR));
        Fixtures.node(ds, -17, 149, Fixtures.addr("109", BAR));
        // 106 and 104 are swapped, so each is out of order with its neighbours; 104
        // only is because 108 at the head of the court counts.
        List<TestError> errors = run(ds);
        assertEquals(2, errors.size(), errors.toString());
        for (TestError e : errors) {
            assertEquals(HousenumberSequenceTest.CODE_SEQUENCE, e.getCode(), e.getDescription());
        }
        assertEquals(n106, errors.get(0).getPrimitives().iterator().next());
        assertTrue(errors.get(0).getDescription().contains("106 between 102 and 104"), errors.get(0).getDescription());
        assertEquals(n104, errors.get(1).getPrimitives().iterator().next());
        assertTrue(errors.get(1).getDescription().contains("104 between 106 and 108"), errors.get(1).getDescription());
    }

    @Test
    void aheadOfAJoinedEndIsACornerButAheadOfADeadEndIsTheHead() {
        for (boolean joined : new boolean[] {false, true}) {
            DataSet ds = new DataSet();
            Way elm = road(ds, ELM, 0, -10, 100);
            if (joined) {
                wayThrough(ds, List.of(Fixtures.node(ds, 100, -200), elm.lastNode(), Fixtures.node(ds, 100, 200)),
                        "highway=residential", "name=Oak Street");
            }
            List<Node> north = houses(ds, ELM, 25, "2", "4", "8", "12");
            // 40 m ahead of the end, 5 m to the north side.
            Fixtures.node(ds, 140, 5, Fixtures.addr("10", ELM));
            if (joined) {
                assertTrue(run(ds).isEmpty(), run(ds).toString());
            } else {
                TestError e = single(run(ds), HousenumberSequenceTest.CODE_SEQUENCE, north.get(3));
                assertTrue(e.getDescription().contains("12 between 8 and 10"), e.getDescription());
            }
        }
    }

    @Test
    void threeHousesASideTakeTheDirectionOfTheWholeStreet() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0, -500, 500);
        List<Node> north = houses(ds, ELM, 25, "2", "6", "4");
        houses(ds, ELM, -25, "1", "3", "5");
        TestError e = single(run(ds), HousenumberSequenceTest.CODE_SEQUENCE, north.get(1));
        assertTrue(e.getDescription().contains("6 between 2 and 4"), e.getDescription());

        // Alone, the side rises once and falls once: no direction, no verdict.
        DataSet alone = new DataSet();
        road(alone, ELM, 0, -500, 500);
        houses(alone, ELM, 25, "2", "6", "4");
        assertTrue(run(alone).isEmpty(), run(alone).toString());
    }

    /**
     * Ash Court: a stem north from (0, 0) to (0, 160) ending in a bulb drawn as a
     * ring of radius 20 round (0, 180). Evens on one side of the stem, odds on the
     * other (mirrored swaps them), and round the bulb 18 and 20 on the evens' half,
     * 21 and 19 on the odds' half. With pShape the stem and ring are one way.
     * The returned list holds the stem's even houses, south to north.
     */
    private static List<Node> ashCourt(DataSet ds, boolean pShape, boolean mirrored, String... evens) {
        double m = mirrored ? -1 : 1;
        Node start = Fixtures.node(ds, 0, 0);
        Node junction = Fixtures.node(ds, 0, 160);
        List<Node> ring = new ArrayList<>();
        ring.add(junction);
        for (int k = 1; k < 12; k++) {
            double a = Math.toRadians(270 + 30 * k);
            ring.add(Fixtures.node(ds, 20 * Math.cos(a), 180 + 20 * Math.sin(a)));
        }
        ring.add(junction);
        String[] tags = {"highway=residential", "name=" + ASH};
        if (pShape) {
            List<Node> all = new ArrayList<>();
            all.add(start);
            all.addAll(ring);
            wayThrough(ds, all, tags);
        } else {
            wayThrough(ds, List.of(start, junction), tags);
            wayThrough(ds, ring, tags);
        }
        List<Node> evenHouses = new ArrayList<>();
        String[] numbers = evens.length > 0 ? evens : new String[] {"2", "4", "6", "8", "10", "12", "14", "16"};
        for (int i = 0; i < numbers.length; i++) {
            evenHouses.add(Fixtures.node(ds, 15 * m, 10 + 17 * i, Fixtures.addr(numbers[i], ASH)));
            Fixtures.node(ds, -15 * m, 10 + 17 * i, Fixtures.addr(String.valueOf(2 * i + 1), ASH));
        }
        String[] bulb = {"18", "20", "21", "19"};
        int[] angles = {-10, 40, 140, 190};
        for (int i = 0; i < bulb.length; i++) {
            double a = Math.toRadians(angles[i]);
            Fixtures.node(ds, 38 * Math.cos(a) * m, 180 + 38 * Math.sin(a), Fixtures.addr(bulb[i], ASH));
        }
        return evenHouses;
    }

    @Test
    void mixedParityRoundALoopBulbIsNoParityError() {
        // Whichever side the bulb falls on, 10 of its 12 addresses share a parity.
        for (boolean mirrored : new boolean[] {false, true}) {
            DataSet ds = new DataSet();
            ashCourt(ds, false, mirrored);
            assertTrue(withCode(run(ds), HousenumberSequenceTest.CODE_PARITY).isEmpty(), run(ds).toString());
        }
    }

    @Test
    void turnRoundALoopBulbIsNoSequenceError() {
        // Walked one way round, 21 sits between 19 and 20 at the top of the bulb;
        // the mirror image puts it there for the other way round.
        for (boolean mirrored : new boolean[] {false, true}) {
            DataSet ds = new DataSet();
            ashCourt(ds, false, mirrored);
            assertTrue(run(ds).isEmpty(), run(ds).toString());
        }
    }

    @Test
    void pShapedWayIsALoopBulbToo() {
        for (boolean mirrored : new boolean[] {false, true}) {
            DataSet ds = new DataSet();
            ashCourt(ds, true, mirrored);
            assertTrue(run(ds).isEmpty(), run(ds).toString());

            // The stem is still checked.
            DataSet bad = new DataSet();
            List<Node> evens = ashCourt(bad, true, mirrored, "2", "4", "6", "30", "10", "12", "14", "16");
            single(run(bad), HousenumberSequenceTest.CODE_SEQUENCE, evens.get(3));
        }
    }
}
