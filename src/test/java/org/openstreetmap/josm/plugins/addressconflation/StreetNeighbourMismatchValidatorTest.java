// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.addressconflation.validation.StreetNeighbourMismatchTest;

class StreetNeighbourMismatchValidatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String ELM = "Elm Street";
    private static final String OAK = "Oak Street";

    private static List<TestError> run(DataSet ds) {
        StreetNeighbourMismatchTest test = new StreetNeighbourMismatchTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.endTest();
        return test.getErrors();
    }

    private static Way road(DataSet ds, String name, double y) {
        return Fixtures.way(ds, new String[] {"highway=residential", "name=" + name}, Fixtures.at(-500, y), Fixtures.at(500, y));
    }

    /**
     * Elm Street along y=0 and Oak Street 120 m north. Six houses north of Elm at
     * x = ±20, ±40, ±60 with the given streets, and one at x = 0 on Oak.
     */
    private static Node block(DataSet ds, String... neighbourStreets) {
        road(ds, ELM, 0);
        road(ds, OAK, 120);
        double[] xs = {-15, 15, -30, 30, -45, 45};
        for (int i = 0; i < neighbourStreets.length; i++) {
            Fixtures.node(ds, xs[i], 25, Fixtures.addr(String.valueOf(10 + 2 * i), neighbourStreets[i]));
        }
        return Fixtures.node(ds, 0, 25, Fixtures.addr("8", OAK));
    }

    private static TestError single(List<TestError> errors, OsmPrimitive p) {
        assertEquals(1, errors.size(), errors.toString());
        TestError e = errors.get(0);
        assertEquals(StreetNeighbourMismatchTest.CODE, e.getCode(), e.getDescription());
        assertEquals(Collections.singletonList(p), List.copyOf(e.getPrimitives()));
        return e;
    }

    @Test
    void addressDisagreeingWithEveryNeighbourIsFlaggedAndFixed() {
        DataSet ds = new DataSet();
        Node odd = block(ds, ELM, ELM, ELM, ELM, ELM, ELM);
        TestError e = single(run(ds), odd);
        assertTrue(e.getDescription().contains("addr:street=Oak Street"), e.getDescription());
        assertTrue(e.getDescription().contains("neighbours use Elm Street"), e.getDescription());
        assertEquals(7, e.getHighlighted().size(), e.getHighlighted().toString());
        assertTrue(e.isFixable());
        Command fix = e.getFix();
        assertNotNull(fix);
        fix.executeCommand();
        assertEquals(ELM, odd.get("addr:street"));
        fix.undoCommand();
        assertEquals(OAK, odd.get("addr:street"));
    }

    @Test
    void fixUsesTheCommonSpellingOnlyWhenItIsClear() {
        DataSet clear = new DataSet();
        Node a = block(clear, ELM, ELM, ELM, ELM, ELM, "Elm St");
        TestError e = single(run(clear), a);
        assertTrue(e.isFixable());
        e.getFix().executeCommand();
        assertEquals(ELM, a.get("addr:street"));

        DataSet split = new DataSet();
        Node b = block(split, ELM, "Elm St", ELM, "Elm St", ELM, "Elm St");
        TestError f = single(run(split), b);
        assertFalse(f.isFixable());
    }

    @Test
    void cornerLotIsLeftAlone() {
        DataSet ds = new DataSet();
        block(ds, ELM, ELM, ELM, ELM, ELM, ELM);
        // The house's own street is a cross street 40 m away: under twice its 25 m to Elm.
        Fixtures.way(ds, new String[] {"highway=residential", "name=" + OAK}, Fixtures.at(40, -200), Fixtures.at(40, 0));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void ownStreetNowhereNearIsLeftToTheStreetNameTest() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0);
        double[] xs = {-15, 15, -30, 30, -45, 45};
        for (double x : xs) {
            Fixtures.node(ds, x, 25, Fixtures.addr("10", ELM));
        }
        Fixtures.node(ds, 0, 25, Fixtures.addr("8", OAK));
        road(ds, OAK, 400);
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void tooFewNeighboursOrTooLittleAgreementIsSilent() {
        DataSet few = new DataSet();
        block(few, ELM, ELM, ELM);
        assertTrue(run(few).isEmpty());

        DataSet mixed = new DataSet();
        road(mixed, "Pine Street", 300);
        block(mixed, ELM, ELM, "Pine Street", ELM, "Pine Street", ELM);
        assertTrue(run(mixed).isEmpty(), run(mixed).toString());
    }

    @Test
    void neighboursAcrossTheRoadDoNotCount() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0);
        road(ds, OAK, 120);
        double[] xs = {-15, 15, -30, 30, -45, 45};
        for (double x : xs) {
            Fixtures.node(ds, x, -25, Fixtures.addr("11", ELM));
        }
        Fixtures.node(ds, 20, 25, Fixtures.addr("10", ELM));
        Fixtures.node(ds, 0, 25, Fixtures.addr("8", OAK));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void neighboursMustAgreeWithTheRoad() {
        DataSet ds = new DataSet();
        road(ds, "Pine Street", 300);
        block(ds, "Pine Street", "Pine Street", "Pine Street", "Pine Street", "Pine Street", "Pine Street");
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void addressAgreeingWithTheRoadIsNotTheOddOneOut() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0);
        road(ds, OAK, 120);
        double[] xs = {-15, 15, -30, 30, -45, 45};
        for (double x : xs) {
            Fixtures.node(ds, x, 25, Fixtures.addr("10", OAK));
        }
        Node onElm = Fixtures.node(ds, 0, 25, Fixtures.addr("8", ELM));
        for (TestError e : run(ds)) {
            assertFalse(e.getPrimitives().contains(onElm), e.toString());
        }
    }

    @Test
    void spellingVariantOfTheSameStreetIsNotAMismatch() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0);
        double[] xs = {-15, 15, -30, 30, -45, 45};
        for (double x : xs) {
            Fixtures.node(ds, x, 25, Fixtures.addr("10", ELM));
        }
        Fixtures.node(ds, 0, 25, Fixtures.addr("8", "Elm St"));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void outbuildingIsNotChecked() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0);
        road(ds, OAK, 120);
        double[] xs = {-15, 15, -30, 30, -45, 45};
        for (double x : xs) {
            Fixtures.node(ds, x, 25, Fixtures.addr("10", ELM));
        }
        Fixtures.rect(ds, 0, 30, 6, 6, Fixtures.concat(Fixtures.addr("8", OAK), "building=garage"));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void buildingsAndNodesAreBothNeighbours() {
        DataSet ds = new DataSet();
        road(ds, ELM, 0);
        road(ds, OAK, 120);
        List<OsmPrimitive> neighbours = new ArrayList<>();
        neighbours.add(Fixtures.rect(ds, -20, 30, 10, 10, Fixtures.concat(Fixtures.addr("6", ELM), "building=house")));
        neighbours.add(Fixtures.rect(ds, 20, 30, 10, 10, Fixtures.concat(Fixtures.addr("10", ELM), "building=house")));
        neighbours.add(Fixtures.node(ds, -40, 25, Fixtures.addr("4", ELM)));
        neighbours.add(Fixtures.node(ds, 40, 25, Fixtures.addr("12", ELM)));
        Way odd = Fixtures.rect(ds, 0, 30, 10, 10, Fixtures.concat(Fixtures.addr("8", OAK), "building=house"));
        TestError e = single(run(ds), odd);
        assertTrue(e.getHighlighted().containsAll(neighbours), e.getHighlighted().toString());
    }

    @Test
    void degenerateGeometryDoesNotThrow() {
        DataSet ds = new DataSet();
        block(ds, ELM, ELM, ELM, ELM, ELM, ELM);
        Node unplaced = new Node();
        Fixtures.tag(unplaced, Fixtures.addr("3", OAK));
        Node moved = Fixtures.node(ds, 0, 0, Fixtures.addr("5", OAK));
        moved.setCoor(null);
        Relation empty = new Relation();
        ds.addPrimitive(empty);
        Fixtures.tag(empty, Fixtures.concat(Fixtures.addr("9", OAK), "type=multipolygon", "building=yes"));
        Node incomplete = new Node(987654321L);
        ds.addPrimitive(incomplete);
        Way partial = new Way();
        partial.setNodes(List.of(Fixtures.node(ds, 300, 25), incomplete));
        ds.addPrimitive(partial);
        Fixtures.tag(partial, Fixtures.concat(Fixtures.addr("13", OAK), "building=yes"));

        StreetNeighbourMismatchTest test = new StreetNeighbourMismatchTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.visit(Collections.<OsmPrimitive>singletonList(unplaced));
        test.endTest();
        // Only the planted mismatch from block().
        assertEquals(1, test.getErrors().size(), test.getErrors().toString());
    }
}
