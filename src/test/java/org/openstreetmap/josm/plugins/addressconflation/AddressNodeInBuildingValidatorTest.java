// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressNodeInBuildingTest;

class AddressNodeInBuildingValidatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String ELM = "Elm Street";

    private static List<TestError> run(DataSet ds) {
        AddressNodeInBuildingTest test = new AddressNodeInBuildingTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.endTest();
        return test.getErrors();
    }

    private static Way house(DataSet ds, String... tags) {
        return Fixtures.rect(ds, 0, 0, 12, 10, Fixtures.concat(new String[] {"building=house"}, tags));
    }

    private static TestError single(List<TestError> errors, int code, Severity severity, OsmPrimitive p) {
        assertEquals(1, errors.size(), errors.toString());
        TestError e = errors.get(0);
        assertEquals(code, e.getCode(), e.getDescription());
        assertEquals(severity, e.getSeverity(), e.getDescription());
        assertEquals(Collections.singletonList(p), List.copyOf(e.getPrimitives()));
        return e;
    }

    @Test
    void nodeWithAnotherAddressInsideABuildingIsFlagged() {
        DataSet ds = new DataSet();
        Way b = house(ds, Fixtures.addr("12", ELM));
        Node n = Fixtures.node(ds, 1, 1, Fixtures.addr("14", ELM));
        TestError e = single(run(ds), AddressNodeInBuildingTest.CODE_DIFFERS, Severity.WARNING, n);
        assertTrue(e.getHighlighted().contains(b), e.getHighlighted().toString());
        assertEquals("node 14 Elm Street, building 12 Elm Street", e.getDescription());
        assertFalse(e.isFixable());
    }

    @Test
    void sameNumberOnAnotherStreetDiffers() {
        DataSet ds = new DataSet();
        house(ds, Fixtures.addr("12", ELM));
        Node n = Fixtures.node(ds, 1, 1, Fixtures.addr("12", "Oak Street"));
        single(run(ds), AddressNodeInBuildingTest.CODE_DIFFERS, Severity.WARNING, n);
    }

    @Test
    void sameAddressIsLeftToCore() {
        DataSet ds = new DataSet();
        house(ds, Fixtures.addr("12", ELM));
        Fixtures.node(ds, 1, 1, Fixtures.addr("12", "Elm St"));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void multiUnitBuildingIsOnlyANote() {
        DataSet ds = new DataSet();
        house(ds, Fixtures.addr("12", ELM));
        Node a = Fixtures.node(ds, -3, 1, Fixtures.addr("14", ELM));
        Node b = Fixtures.node(ds, 3, 1, Fixtures.addr("16", ELM));
        List<TestError> errors = run(ds);
        assertEquals(2, errors.size(), errors.toString());
        for (TestError e : errors) {
            assertEquals(AddressNodeInBuildingTest.CODE_DIFFERS, e.getCode());
            assertEquals(Severity.OTHER, e.getSeverity());
        }
        assertTrue(errors.get(0).getPrimitives().contains(a) || errors.get(1).getPrimitives().contains(a));
        assertTrue(errors.get(0).getPrimitives().contains(b) || errors.get(1).getPrimitives().contains(b));
    }

    @Test
    void crossStreetAddressOnALargeBuildingIsOnlyANote() {
        DataSet ds = new DataSet();
        Fixtures.rect(ds, 0, 0, 40, 40, Fixtures.concat(Fixtures.addr("1", "Main Street"), "building=commercial"));
        Node n = Fixtures.node(ds, 15, 0, Fixtures.addr("5", "Side Street"));
        single(run(ds), AddressNodeInBuildingTest.CODE_DIFFERS, Severity.OTHER, n);
    }

    @Test
    void featureNodesAndNodesOnTheOutlineAreNotChecked() {
        DataSet ds = new DataSet();
        Way b = house(ds, Fixtures.addr("12", ELM));
        Fixtures.node(ds, 1, 1, Fixtures.concat(Fixtures.addr("14", ELM), "shop=bakery"));
        Fixtures.node(ds, -1, 1, Fixtures.concat(Fixtures.addr("14", ELM), "entrance=main"));
        Fixtures.tag(b.firstNode(), Fixtures.addr("16", ELM));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void buildingNoAndNodesOutsideAreIgnored() {
        DataSet ds = new DataSet();
        Fixtures.rect(ds, 0, 0, 12, 10, Fixtures.concat(Fixtures.addr("12", ELM), "building=no"));
        Fixtures.node(ds, 1, 1, Fixtures.addr("14", ELM));
        Fixtures.rect(ds, 100, 0, 12, 10, Fixtures.concat(Fixtures.addr("20", ELM), "building=house"));
        Fixtures.node(ds, 120, 0, Fixtures.addr("18", ELM));
        assertTrue(run(ds).isEmpty(), run(ds).toString());
    }

    @Test
    void nodeInsideAnAddresslessBuildingIsLeftAlone() {
        DataSet ds = new DataSet();
        house(ds);
        Fixtures.node(ds, 1, 1, Fixtures.addr("12", ELM));
        assertTrue(run(ds).isEmpty());

        DataSet partly = new DataSet();
        house(partly, "addr:postcode=85302");
        Fixtures.node(partly, 1, 1, Fixtures.addr("12", ELM));
        assertTrue(run(partly).isEmpty());
    }

    @Test
    void multipolygonBuildingsCountAndTheirHolesDoNot() {
        DataSet ds = new DataSet();
        Way outer = Fixtures.rect(ds, 0, 0, 40, 40);
        Way inner = Fixtures.rect(ds, 0, 0, 10, 10);
        Relation mp = Fixtures.multipolygon(ds, outer, List.of(inner), Fixtures.concat(Fixtures.addr("12", ELM), "building=yes"));
        Node inBody = Fixtures.node(ds, 15, 15, Fixtures.addr("14", ELM));
        Fixtures.node(ds, 1, 1, Fixtures.addr("16", ELM));
        TestError e = single(run(ds), AddressNodeInBuildingTest.CODE_DIFFERS, Severity.WARNING, inBody);
        assertTrue(e.getHighlighted().contains(mp));
    }

    @Test
    void smallestEnclosingBuildingWins() {
        DataSet ds = new DataSet();
        Fixtures.rect(ds, 0, 0, 100, 100, "building=retail");
        Way shop = Fixtures.rect(ds, 10, 10, 12, 10, Fixtures.concat(Fixtures.addr("12", ELM), "building=retail"));
        Node n = Fixtures.node(ds, 10, 10, Fixtures.addr("14", ELM));
        TestError e = single(run(ds), AddressNodeInBuildingTest.CODE_DIFFERS, Severity.WARNING, n);
        assertTrue(e.getHighlighted().contains(shop));
    }

    @Test
    void degenerateGeometryDoesNotThrow() {
        DataSet ds = new DataSet();
        Node unplaced = new Node();
        Fixtures.tag(unplaced, Fixtures.addr("3", ELM));
        Node moved = Fixtures.node(ds, 0, 0, Fixtures.addr("5", ELM));
        moved.setCoor(null);
        Relation empty = new Relation();
        ds.addPrimitive(empty);
        Fixtures.tag(empty, "type=multipolygon", "building=yes");
        Node incomplete = new Node(987654321L);
        ds.addPrimitive(incomplete);
        Node a = Fixtures.node(ds, -10, -10);
        Node b = Fixtures.node(ds, 10, -10);
        Node c = Fixtures.node(ds, 10, 10);
        Way partial = new Way();
        partial.setNodes(List.of(a, b, c, incomplete, a));
        ds.addPrimitive(partial);
        Fixtures.tag(partial, "building=yes");
        Relation hollow = new Relation();
        hollow.addMember(new RelationMember("outer", partial));
        ds.addPrimitive(hollow);
        Fixtures.tag(hollow, "type=multipolygon", "building=yes");
        Fixtures.node(ds, 1, 1, Fixtures.addr("7", ELM));

        AddressNodeInBuildingTest test = new AddressNodeInBuildingTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.visit(Collections.<OsmPrimitive>singletonList(unplaced));
        test.endTest();
        assertTrue(test.getErrors().isEmpty(), test.getErrors().toString());
    }
}
