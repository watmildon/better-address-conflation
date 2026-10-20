// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

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
import org.openstreetmap.josm.plugins.addressconflation.validation.StreetNameMismatchTest;
import org.openstreetmap.josm.spi.preferences.Config;

class StreetNameMismatchValidatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static List<TestError> run(DataSet ds) {
        StreetNameMismatchTest test = new StreetNameMismatchTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.endTest();
        return test.getErrors();
    }

    /** East-west road at y metres north of the origin, 1 km long. */
    private static Way road(DataSet ds, double y, String... tags) {
        return Fixtures.way(ds, tags, Fixtures.at(-500, y), Fixtures.at(500, y));
    }

    private static Node address(DataSet ds, String street) {
        return Fixtures.node(ds, 0, 0, Fixtures.addr("12", street));
    }

    private static TestError single(List<TestError> errors, int code, OsmPrimitive p) {
        assertEquals(1, errors.size(), errors.toString());
        TestError e = errors.get(0);
        assertEquals(code, e.getCode(), e.getDescription());
        assertEquals(Collections.singletonList(p), List.copyOf(e.getPrimitives()));
        return e;
    }

    @Test
    void exactMatchNearbyPasses() {
        DataSet ds = new DataSet();
        address(ds, "West Olive Avenue");
        road(ds, 50, "highway=residential", "name=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void differentStreetNearbyIsNoRoad() {
        DataSet ds = new DataSet();
        Node a = address(ds, "West Olive Avenue");
        road(ds, 50, "highway=residential", "name=North 59th Avenue");
        TestError e = single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);
        assertTrue(e.getDescription().contains("West Olive Avenue"), e.getDescription());
    }

    @Test
    void abbreviatedNameIsNearMatchHighlightingTheRoad() {
        DataSet ds = new DataSet();
        Node a = address(ds, "W Olive Ave");
        Way r = road(ds, 50, "highway=residential", "name=West Olive Avenue");
        road(ds, -80, "highway=residential", "name=Maryland Avenue");
        TestError e = single(run(ds), StreetNameMismatchTest.CODE_NEAR_MATCH, a);
        assertTrue(e.getHighlighted().contains(r), e.getHighlighted().toString());
        assertTrue(e.getDescription().contains("W Olive Ave"), e.getDescription());
        assertTrue(e.getDescription().contains("West Olive Avenue"), e.getDescription());
    }

    @Test
    void caseOnlyDifferenceIsNearMatch() {
        DataSet ds = new DataSet();
        Node a = address(ds, "WEST OLIVE AVENUE");
        road(ds, 50, "highway=residential", "name=West Olive Avenue");
        single(run(ds), StreetNameMismatchTest.CODE_NEAR_MATCH, a);
    }

    @Test
    void diacriticOnlyDifferenceIsNearMatch() {
        DataSet ds = new DataSet();
        Node a = address(ds, "Calle Pena");
        road(ds, 50, "highway=residential", "name=Calle Peña");
        single(run(ds), StreetNameMismatchTest.CODE_NEAR_MATCH, a);
    }

    @Test
    void differentNamesInAnotherScriptAreNotNearMatches() {
        DataSet ds = new DataSet();
        Node a = address(ds, "улица Ленина");
        road(ds, 50, "highway=residential", "name=улица Пушкина");
        single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);
    }

    @Test
    void whitespaceDifferencesStillMatchExactly() {
        DataSet ds = new DataSet();
        address(ds, "  West  Olive Avenue ");
        road(ds, 50, "highway=residential", "name=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void roadBeyondRadiusIsNoRoadUntilRadiusGrows() {
        DataSet ds = new DataSet();
        Node a = address(ds, "West Olive Avenue");
        road(ds, 250, "highway=residential", "name=West Olive Avenue");
        single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);
        try {
            Config.getPref().putDouble(StreetNameMismatchTest.PREF_RADIUS, 300.0);
            assertTrue(run(ds).isEmpty());
        } finally {
            Config.getPref().put(StreetNameMismatchTest.PREF_RADIUS, null);
        }
        single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);
    }

    @Test
    void altNameAndLanguageNamesMatch() {
        DataSet ds = new DataSet();
        address(ds, "Olive Avenue");
        road(ds, 50, "highway=residential", "name=West Olive Avenue", "alt_name=Olive Avenue");
        assertTrue(run(ds).isEmpty());

        DataSet ds2 = new DataSet();
        address(ds2, "Olive Avenue");
        road(ds2, 50, "highway=residential", "name=Avenida Olivo", "name:en=Olive Avenue");
        assertTrue(run(ds2).isEmpty());
    }

    @Test
    void semicolonListInRoadNameMatches() {
        DataSet ds = new DataSet();
        address(ds, "Olive Avenue");
        road(ds, 50, "highway=residential", "name=West Olive Avenue;Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void nonameAndNotNameDoNotMatch() {
        DataSet ds = new DataSet();
        Node a = address(ds, "yes");
        road(ds, 50, "highway=residential", "noname=yes");
        single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);

        DataSet ds2 = new DataSet();
        Node b = address(ds2, "Olive Avenue");
        road(ds2, 50, "highway=residential", "name=West Olive Avenue", "not:name=Olive Avenue");
        single(run(ds2), StreetNameMismatchTest.CODE_NO_ROAD, b);
    }

    @Test
    void onlyKnownRoadValuesAreCandidates() {
        for (String notARoad : List.of("platform", "footway", "cycleway", "path", "corridor", "steps")) {
            DataSet ds = new DataSet();
            Node a = address(ds, "West Olive Avenue");
            road(ds, 50, "highway=" + notARoad, "name=West Olive Avenue");
            road(ds, -80, "highway=residential", "name=North 59th Avenue");
            single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);
        }
        for (String isARoad : List.of("service", "pedestrian", "track", "living_street", "primary_link")) {
            DataSet ds = new DataSet();
            address(ds, "Olive Mall");
            road(ds, 50, "highway=" + isARoad, "name=Olive Mall");
            assertTrue(run(ds).isEmpty(), isARoad);
        }
    }

    @Test
    void semicolonListReportsTheFailingPart() {
        DataSet ds = new DataSet();
        Node a = address(ds, "West Olive Avenue; North 59th Avenue");
        road(ds, 50, "highway=residential", "name=West Olive Avenue");
        TestError e = single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, a);
        assertTrue(e.getDescription().contains("North 59th Avenue"), e.getDescription());
        assertFalse(e.getDescription().contains("West Olive Avenue"), e.getDescription());
    }

    @Test
    void buildingDistanceIsFromItsNearestEdge() {
        DataSet ds = new DataSet();
        // 100 m square centred on the origin: top edge at y=50, centre 240 m from the road.
        Fixtures.rect(ds, 0, 0, 100, 100, Fixtures.concat(Fixtures.addr("12", "West Olive Avenue"), "building=house"));
        road(ds, 240, "highway=residential", "name=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void multipolygonAddressUsesMemberGeometry() {
        DataSet ds = new DataSet();
        Way outer = Fixtures.rect(ds, 0, 0, 40, 40);
        Relation mp = Fixtures.multipolygon(ds, outer, Collections.emptyList(),
                Fixtures.concat(Fixtures.addr("12", "West Olive Avenue"), "building=yes"));
        road(ds, 100, "highway=residential", "name=North 59th Avenue");
        single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD, mp);
        road(ds, -100, "highway=residential", "name=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void associatedStreetHouseMemberMatchesWithoutDistance() {
        DataSet ds = new DataSet();
        Node a = address(ds, "West Olive Avenue");
        Way r = road(ds, 5000, "highway=residential");
        Relation rel = new Relation();
        rel.addMember(new RelationMember("street", r));
        rel.addMember(new RelationMember("house", a));
        ds.addPrimitive(rel);
        Fixtures.tag(rel, "type=associatedStreet", "name=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void streetRelationNameCountsForItsRoads() {
        DataSet ds = new DataSet();
        address(ds, "West Olive Avenue");
        Way r = road(ds, 50, "highway=residential");
        Relation rel = new Relation();
        rel.addMember(new RelationMember("street", r));
        ds.addPrimitive(rel);
        Fixtures.tag(rel, "type=associatedStreet", "name=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void noRoadAtDownloadEdgeGetsItsOwnBucket() {
        DataSet ds = new DataSet();
        Node a = address(ds, "West Olive Avenue");
        road(ds, 50, "highway=residential", "name=North 59th Avenue");
        ds.addDataSource(new DataSource(new Bounds(Fixtures.at(-1000, -1000), Fixtures.at(100, 1000)), "test"));
        TestError e = single(run(ds), StreetNameMismatchTest.CODE_NO_ROAD_AT_EDGE, a);
        assertTrue(e.getDescription().contains("West Olive Avenue"), e.getDescription());

        DataSet covered = new DataSet();
        Node b = address(covered, "West Olive Avenue");
        road(covered, 50, "highway=residential", "name=North 59th Avenue");
        covered.addDataSource(new DataSource(new Bounds(Fixtures.at(-1000, -1000), Fixtures.at(1000, 1000)), "test"));
        single(run(covered), StreetNameMismatchTest.CODE_NO_ROAD, b);
    }

    @Test
    void nearMatchIsReportedEvenAtTheDownloadEdge() {
        DataSet ds = new DataSet();
        Node a = address(ds, "W Olive Ave");
        road(ds, 50, "highway=residential", "name=West Olive Avenue");
        ds.addDataSource(new DataSource(new Bounds(Fixtures.at(-1000, -1000), Fixtures.at(100, 1000)), "test"));
        single(run(ds), StreetNameMismatchTest.CODE_NEAR_MATCH, a);
    }

    @Test
    void degenerateGeometryDoesNotThrow() {
        DataSet ds = new DataSet();
        // A complete node without coordinates cannot join a dataset, but it can be visited.
        Node unplaced = new Node();
        Fixtures.tag(unplaced, "addr:street=West Olive Avenue");
        // One that loses its coordinates after joining.
        Node moved = Fixtures.node(ds, 0, 0, "addr:street=West Olive Avenue");
        moved.setCoor(null);

        Relation empty = new Relation();
        ds.addPrimitive(empty);
        Fixtures.tag(empty, "type=multipolygon", "building=yes", "addr:street=West Olive Avenue");

        Way noNodes = new Way();
        ds.addPrimitive(noNodes);
        Relation hollow = new Relation();
        hollow.addMember(new RelationMember("outer", noNodes));
        ds.addPrimitive(hollow);
        Fixtures.tag(hollow, "type=multipolygon", "building=yes", "addr:street=West Olive Avenue");

        Node incomplete = new Node(987654321L);
        ds.addPrimitive(incomplete);
        Node placed = Fixtures.node(ds, 0, 0);
        Way partial = new Way();
        partial.setNodes(List.of(placed, incomplete));
        ds.addPrimitive(partial);
        Fixtures.tag(partial, "building=yes", "addr:street=West Olive Avenue");

        StreetNameMismatchTest test = new StreetNameMismatchTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.visit(Collections.<OsmPrimitive>singletonList(unplaced));
        test.endTest();
        assertTrue(test.getErrors().isEmpty(), test.getErrors().toString());
    }

    @Test
    void datasetWithoutAnyRoadIsLeftAlone() {
        DataSet ds = new DataSet();
        address(ds, "West Olive Avenue");
        Fixtures.rect(ds, 60, 0, 12, 10, Fixtures.concat(Fixtures.addr("14", "West Olive Avenue"), "building=house"));
        ds.addDataSource(new DataSource(new Bounds(Fixtures.at(-1000, -1000), Fixtures.at(1000, 1000)), "test"));
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void highwayCarryingAnAddressIsNotChecked() {
        DataSet ds = new DataSet();
        road(ds, 0, "highway=service", "name=Mall Drive", "addr:street=West Olive Avenue");
        assertTrue(run(ds).isEmpty());
    }

    @Test
    void blankStreetIsIgnored() {
        DataSet ds = new DataSet();
        Fixtures.node(ds, 0, 0, "addr:street= ; ");
        assertTrue(run(ds).isEmpty());
    }
}
