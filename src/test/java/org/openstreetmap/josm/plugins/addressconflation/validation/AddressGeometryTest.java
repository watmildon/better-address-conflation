// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.locationtech.jts.geom.Coordinate;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.Fixtures;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.Chain;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.Location;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.Polyline;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.RoadHit;

class AddressGeometryTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final double EPS = 0.01;

    private static void assertNear(LatLon expected, LatLon actual) {
        LocalProjection proj = new LocalProjection(expected.lat(), expected.lon());
        assertEquals(0, proj.toXY(actual).distance(new Coordinate(0, 0)), EPS, actual.toString());
    }

    private static Way wayThrough(DataSet ds, List<Node> nodes, String... tags) {
        Way w = new Way();
        w.setNodes(nodes);
        ds.addPrimitive(w);
        Fixtures.tag(w, tags);
        return w;
    }

    @Test
    void pointOfNodeWayAndRelation() {
        DataSet ds = new DataSet();
        Node n = Fixtures.node(ds, 5, 5);
        assertNear(Fixtures.at(5, 5), AddressGeometry.point(n));
        // The closing node is counted once, so a rectangle gives its centre.
        Way rect = Fixtures.rect(ds, 30, 40, 10, 6);
        assertNear(Fixtures.at(30, 40), AddressGeometry.point(rect));
        Relation mp = Fixtures.multipolygon(ds, Fixtures.rect(ds, -30, 0, 8, 8), Collections.emptyList());
        assertNear(Fixtures.at(-30, 0), AddressGeometry.point(mp));

        Relation empty = new Relation();
        ds.addPrimitive(empty);
        assertNull(AddressGeometry.point(empty));
        Node unplaced = new Node();
        assertNull(AddressGeometry.point(unplaced));
        Node incomplete = new Node(987654321L);
        ds.addPrimitive(incomplete);
        Way partial = wayThrough(ds, List.of(Fixtures.node(ds, 1, 2), incomplete));
        assertNear(Fixtures.at(1, 2), AddressGeometry.point(partial));
    }

    @Test
    void locateGivesDistanceAlongSideAndOverrun() {
        Polyline line = new Polyline(List.of(new Coordinate(0, 0), new Coordinate(100, 0), new Coordinate(100, 100)));
        assertEquals(200, line.length(), EPS);

        Location left = line.locate(new Coordinate(30, 10));
        assertEquals(10, left.distance, EPS);
        assertEquals(30, left.along, EPS);
        assertEquals(1, left.side);
        assertFalse(left.pastEnd());

        Location right = line.locate(new Coordinate(30, -10));
        assertEquals(-1, right.side);

        // After the bend, east of the north-bound leg: its right.
        Location bend = line.locate(new Coordinate(110, 50));
        assertEquals(150, bend.along, EPS);
        assertEquals(-1, bend.side);

        Location ahead = line.locate(new Coordinate(-40, 5));
        assertEquals(0, ahead.along, EPS);
        assertEquals(40, ahead.overshoot, EPS);
        assertEquals(5, ahead.offset, EPS);
        assertTrue(ahead.pastEnd());

        Location beside = line.locate(new Coordinate(-5, 40));
        assertFalse(beside.pastEnd());
    }

    @Test
    void polylineNeedsTwoDistinctPoints() {
        LocalProjection proj = new LocalProjection(Fixtures.LAT0, Fixtures.LON0);
        DataSet ds = new DataSet();
        Node a = Fixtures.node(ds, 0, 0);
        Node b = Fixtures.node(ds, 0, 0);
        assertNull(Polyline.of(List.of(a, b), proj));
        assertNull(Polyline.of(Collections.emptyList(), proj));
        Node c = Fixtures.node(ds, 10, 0);
        assertEquals(10, Polyline.of(List.of(a, b, c), proj).length(), EPS);
    }

    @Test
    void roadsNearAreFilteredAndSorted() {
        DataSet ds = new DataSet();
        Way far = Fixtures.way(ds, new String[] {"highway=residential"}, Fixtures.at(-100, 50), Fixtures.at(100, 50));
        Way near = Fixtures.way(ds, new String[] {"highway=residential"}, Fixtures.at(-100, -20), Fixtures.at(100, -20));
        Fixtures.way(ds, new String[] {"highway=footway"}, Fixtures.at(-100, 5), Fixtures.at(100, 5));
        Fixtures.way(ds, new String[] {"highway=residential"}, Fixtures.at(-100, 200), Fixtures.at(100, 200));
        List<RoadHit> hits = AddressGeometry.roadsNear(ds, Fixtures.at(0, 0), 100, StreetNames::isRoad);
        assertEquals(2, hits.size());
        assertEquals(near, hits.get(0).way);
        assertEquals(20, hits.get(0).location.distance, 0.1);
        assertEquals(far, hits.get(1).way);
    }

    @Test
    void splitWaysJoinIntoOneChainInOrder() {
        DataSet ds = new DataSet();
        Node a = Fixtures.node(ds, 0, 0);
        Node b = Fixtures.node(ds, 100, 0);
        Node c = Fixtures.node(ds, 200, 0);
        Node d = Fixtures.node(ds, 200, 100);
        Way ab = wayThrough(ds, List.of(a, b));
        Way cb = wayThrough(ds, List.of(c, b));
        Way cd = wayThrough(ds, List.of(c, d));
        List<List<Node>> chains = AddressGeometry.chains(List.of(cb, cd, ab));
        assertEquals(1, chains.size());
        List<Node> chain = chains.get(0);
        assertTrue(chain.equals(List.of(a, b, c, d)) || chain.equals(List.of(d, c, b, a)), chain.toString());
    }

    @Test
    void chainsDoNotTurnBack() {
        DataSet ds = new DataSet();
        Node west = Fixtures.node(ds, -300, 0);
        Node east = Fixtures.node(ds, 300, 0);
        Way south = wayThrough(ds, List.of(west, Fixtures.node(ds, -250, -10), Fixtures.node(ds, 250, -10), east));
        Way north = wayThrough(ds, List.of(east, Fixtures.node(ds, 250, 10), Fixtures.node(ds, -250, 10), west));
        assertEquals(2, AddressGeometry.chains(List.of(south, north)).size());
        // Straight on is preferred where three ways meet.
        Node m = Fixtures.node(ds, 0, 500);
        Way stem = wayThrough(ds, List.of(Fixtures.node(ds, -100, 500), m));
        Way spur = wayThrough(ds, List.of(m, Fixtures.node(ds, 0, 600)));
        Way on = wayThrough(ds, List.of(m, Fixtures.node(ds, 100, 500)));
        List<List<Node>> chains = AddressGeometry.chains(List.of(stem, spur, on));
        assertEquals(2, chains.size());
        assertEquals(3, chains.get(0).size());
        assertTrue(AddressGeometry.turn(stem.firstNode(), m, on.lastNode()) < 1);
        assertEquals(90, AddressGeometry.turn(stem.firstNode(), m, spur.lastNode()), 1);
    }

    @Test
    void closedLoopIsOneChain() {
        DataSet ds = new DataSet();
        Way loop = Fixtures.rect(ds, 0, 0, 100, 100);
        List<List<Node>> chains = AddressGeometry.chains(List.of(loop));
        assertEquals(1, chains.size());
        assertEquals(5, chains.get(0).size());
    }

    @Test
    void leavesDownloadOnlyWithBoundsThatDoNotCover() {
        DataSet ds = new DataSet();
        assertFalse(AddressGeometry.leavesDownload(ds, AddressGeometry.around(Fixtures.at(0, 0), 100)));
        ds.addDataSource(new DataSource(new Bounds(Fixtures.at(-1000, -1000), Fixtures.at(1000, 1000)), "test"));
        assertFalse(AddressGeometry.leavesDownload(ds, AddressGeometry.around(Fixtures.at(0, 0), 100)));
        assertTrue(AddressGeometry.leavesDownload(ds, AddressGeometry.around(Fixtures.at(950, 0), 100)));
    }

    @Test
    void addressCarriers() {
        DataSet ds = new DataSet();
        ConflationSettings s = new ConflationSettings();
        assertTrue(AddressGeometry.isAddressCarrier(Fixtures.rect(ds, 0, 0, 10, 10, "building=house"), s));
        assertTrue(AddressGeometry.isAddressCarrier(Fixtures.rect(ds, 0, 0, 10, 10, "building=retail", "shop=bakery"), s));
        assertFalse(AddressGeometry.isAddressCarrier(Fixtures.rect(ds, 0, 0, 6, 6, "building=garage"), s));
        assertTrue(AddressGeometry.isAddressCarrier(Fixtures.node(ds, 0, 0, "addr:housenumber=1"), s));
        assertTrue(AddressGeometry.isAddressCarrier(Fixtures.node(ds, 0, 0, "addr:housenumber=1", "entrance=main"), s));
        assertFalse(AddressGeometry.isAddressCarrier(Fixtures.node(ds, 0, 0, "addr:housenumber=1", "shop=bakery"), s));
        assertFalse(AddressGeometry.isAddressCarrier(Fixtures.rect(ds, 0, 0, 10, 10, "amenity=bicycle_rental"), s));
        // The mapper's own list decides what an outbuilding is.
        ConflationSettings custom = new ConflationSettings();
        custom.setOutbuildings(List.of("barn"));
        assertTrue(AddressGeometry.isAddressCarrier(Fixtures.rect(ds, 0, 0, 6, 6, "building=garage"), custom));
        assertFalse(AddressGeometry.isAddressCarrier(Fixtures.rect(ds, 0, 0, 6, 6, "building=barn"), custom));
    }

    @Test
    void chainEndsKnowWhetherAnotherRoadMeetsThem() {
        DataSet ds = new DataSet();
        Node a = Fixtures.node(ds, 0, 0);
        Node b = Fixtures.node(ds, 0, 100);
        Node c = Fixtures.node(ds, 0, 200);
        // The court in two ways, off a cross street at a; a footpath carries on past c.
        Way ab = wayThrough(ds, List.of(a, b), "highway=residential", "name=Bar Court");
        Way bc = wayThrough(ds, List.of(b, c), "highway=residential", "name=Bar Court");
        wayThrough(ds, List.of(Fixtures.node(ds, -100, 0), a, Fixtures.node(ds, 100, 0)), "highway=residential", "name=Foo Street");
        wayThrough(ds, List.of(c, Fixtures.node(ds, 0, 300)), "highway=footway");
        List<Chain> chains = AddressGeometry.chainsOf(List.of(ab, bc));
        assertEquals(1, chains.size());
        Chain chain = chains.get(0);
        assertEquals(List.of(a, b, c), chain.nodes);
        assertEquals(Set.of(ab, bc), chain.ways);
        assertFalse(chain.deadStart);
        assertTrue(chain.deadEnd);
        assertFalse(chain.loopSegment(0));
        assertFalse(chain.loopSegment(1));

        // A road with the same name that is not part of the chain still meets it;
        // joined into the chain, it carries the run on to its own dead end.
        Way spur = wayThrough(ds, List.of(c, Fixtures.node(ds, 100, 200)), "highway=residential", "name=Bar Court");
        assertFalse(AddressGeometry.chainsOf(List.of(ab, bc)).get(0).deadEnd);
        Chain longer = AddressGeometry.chainsOf(List.of(ab, bc, spur)).get(0);
        assertEquals(spur.lastNode(), longer.nodes.get(3));
        assertTrue(longer.deadEnd);
    }

    @Test
    void drivewayAtTheHeadKeepsTheDeadEnd() {
        DataSet ds = new DataSet();
        Node a = Fixtures.node(ds, 0, 0);
        Node b = Fixtures.node(ds, 0, 100);
        Way ab = wayThrough(ds, List.of(a, b), "highway=residential", "name=Bar Court");
        Way drive = wayThrough(ds, List.of(b, Fixtures.node(ds, 20, 120)), "highway=service", "service=driveway");
        assertTrue(AddressGeometry.chainsOf(List.of(ab)).get(0).deadEnd);
        drive.remove("service");
        assertFalse(AddressGeometry.chainsOf(List.of(ab)).get(0).deadEnd);
    }

    @Test
    void wholeRingStreetIsNotABulb() {
        DataSet ds = new DataSet();
        Node start = Fixtures.node(ds, 0, 0);
        Node junction = Fixtures.node(ds, 0, 100);
        Way stem = wayThrough(ds, List.of(start, junction));
        // Radius 80 m: about 500 m round, far more than a turning bulb.
        Way ring = wayThrough(ds, ring(ds, junction, 0, 180, 80, 270, 1));
        List<Chain> chains = AddressGeometry.chainsOf(List.of(stem, ring));
        assertEquals(1, chains.size());
        Chain chain = chains.get(0);
        assertEquals(14, chain.nodes.size());
        for (int s = 0; s < chain.nodes.size() - 1; s++) {
            assertFalse(chain.loopSegment(s), "segment " + s);
        }
    }

    @Test
    void locationSaysWhichEndWasOvershot() {
        Polyline line = new Polyline(List.of(new Coordinate(0, 0), new Coordinate(100, 0)));
        Location ahead = line.locate(new Coordinate(-30, 4));
        assertEquals(Location.START, ahead.end);
        assertEquals(-30, ahead.alongExtended(), EPS);
        Location past = line.locate(new Coordinate(117, -4));
        assertEquals(Location.END, past.end);
        assertEquals(117, past.alongExtended(), EPS);
        Location beside = line.locate(new Coordinate(50, 10));
        assertEquals(0, beside.end);
        assertEquals(50, beside.alongExtended(), EPS);
    }

    /** A ring of twelve nodes round (cx, cy), starting and ending at the given node. */
    private static List<Node> ring(DataSet ds, Node at, double cx, double cy, double r, double startDeg, int dir) {
        List<Node> out = new ArrayList<>();
        out.add(at);
        for (int k = 1; k < 12; k++) {
            double a = Math.toRadians(startDeg + dir * 30 * k);
            out.add(Fixtures.node(ds, cx + r * Math.cos(a), cy + r * Math.sin(a)));
        }
        out.add(at);
        return out;
    }

    private static void assertLoopBulb(Chain chain, Node start, Node junction, int stemSegments) {
        List<Node> nodes = chain.nodes;
        int n = nodes.size();
        // Stem, round the loop, back to the junction: in either direction.
        boolean forward = nodes.get(0) == start;
        assertTrue(forward || nodes.get(n - 1) == start, nodes.toString());
        assertEquals(stemSegments + 13, n, nodes.toString());
        for (int s = 0; s < n - 1; s++) {
            boolean stem = forward ? s < stemSegments : s >= n - 1 - stemSegments;
            assertEquals(!stem, chain.loopSegment(s), "segment " + s);
        }
        Node stemEnd = nodes.get(forward ? stemSegments : n - 1 - stemSegments);
        assertEquals(junction, stemEnd);
        assertEquals(junction, forward ? nodes.get(n - 1) : nodes.get(0));
        assertTrue(forward ? chain.deadStart : chain.deadEnd);
        assertFalse(forward ? chain.deadEnd : chain.deadStart);
    }

    @Test
    void loopBulbJoinsItsStemWhicheverComesFirst() {
        for (boolean ringFirst : new boolean[] {false, true}) {
            for (int dir : new int[] {1, -1}) {
                DataSet ds = new DataSet();
                Node start = Fixtures.node(ds, 0, 0);
                Node junction = Fixtures.node(ds, 0, 100);
                Way stem = wayThrough(ds, List.of(start, junction));
                Way bulb = wayThrough(ds, ring(ds, junction, 0, 120, 20, 270, dir));
                List<Chain> chains = AddressGeometry.chainsOf(ringFirst ? List.of(bulb, stem) : List.of(stem, bulb));
                assertEquals(1, chains.size(), "ringFirst " + ringFirst + " dir " + dir);
                assertLoopBulb(chains.get(0), start, junction, 1);
            }
        }
    }

    @Test
    void ringDrawnAgainstTheStemIsWalkedTheOtherWayRound() {
        DataSet ds = new DataSet();
        Node start = Fixtures.node(ds, 0, -100);
        Node junction = Fixtures.node(ds, 0, 0);
        Way stem = wayThrough(ds, List.of(start, junction));
        // A bulb off to the east of the stem's end, drawn so it first heads back south.
        Way bulb = wayThrough(ds, ring(ds, junction, 20, 0, 20, 180, 1));
        assertTrue(AddressGeometry.turn(start, junction, bulb.getNode(1)) > AddressGeometry.MAX_TURN);
        List<Chain> chains = AddressGeometry.chainsOf(List.of(stem, bulb));
        assertEquals(1, chains.size());
        assertLoopBulb(chains.get(0), start, junction, 1);
    }

    @Test
    void pShapedWayMarksItsLoop() {
        DataSet ds = new DataSet();
        Node start = Fixtures.node(ds, 0, 0);
        Node mid = Fixtures.node(ds, 0, 50);
        Node junction = Fixtures.node(ds, 0, 100);
        List<Node> nodes = new ArrayList<>(List.of(start, mid));
        nodes.addAll(ring(ds, junction, 0, 120, 20, 270, 1));
        Way p = wayThrough(ds, nodes);
        List<Chain> chains = AddressGeometry.chainsOf(List.of(p));
        assertEquals(1, chains.size());
        assertLoopBulb(chains.get(0), start, junction, 2);

        // The polyline carries the loop segments over, by its own segment numbers.
        LocalProjection proj = new LocalProjection(Fixtures.LAT0, Fixtures.LON0);
        Polyline line = Polyline.of(chains.get(0), proj);
        Location onStem = line.locate(proj.toXY(Fixtures.at(10, 30)));
        assertFalse(line.loopSegment(onStem.segment));
        Location onBulb = line.locate(proj.toXY(Fixtures.at(0, 150)));
        assertTrue(line.loopSegment(onBulb.segment));
        assertFalse(Polyline.of(chains.get(0).nodes, proj).loopSegment(onBulb.segment));
    }
}
