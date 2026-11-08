// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.locationtech.jts.geom.Coordinate;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;

/**
 * Small geometry helpers for the address validators: where an address is,
 * which roads are near it, and where it sits along and beside a road.
 * Everything metric is in the metres of a {@link LocalProjection}.
 */
final class AddressGeometry {
    private static final double METRES_PER_DEGREE_LAT = 110540.0;
    private static final double METRES_PER_DEGREE_LON_AT_EQUATOR = 111320.0;

    /** Tags that make a non-building address carrier a feature of its own (a shop, a bike dock...). */
    static final List<String> FEATURE_KEYS = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            "amenity", "shop", "office", "craft", "tourism", "leisure", "healthcare", "emergency", "name"));

    private AddressGeometry() {
    }

    /**
     * True when the primitive's position says where its address is along the
     * street: a main building, or a plain address point. A shop node or bike dock
     * sits wherever it is inside a large building or on a plaza, and a garage or
     * shed stands behind the house it shares an address with, so those are left
     * out of the neighbourhood checks. The settings say which building values
     * are outbuildings.
     */
    static boolean isAddressCarrier(OsmPrimitive p, ConflationSettings settings) {
        String building = p.get("building");
        if (building != null && !"no".equals(building)) {
            return settings.weightFor(p) >= 1.0;
        }
        for (String k : FEATURE_KEYS) {
            if (p.hasKey(k)) {
                return false;
            }
        }
        return true;
    }

    /** True for a node that has coordinates and can be drawn. */
    static boolean placed(Node n) {
        return n != null && !n.isIncomplete() && n.isLatLonKnown();
    }

    /**
     * One point standing for the address: a node itself, the average of a way's
     * nodes (the closing node counted once), or the average of every node of a
     * relation's node and way members. Null when there is nothing placed.
     */
    static LatLon point(OsmPrimitive p) {
        Set<Node> nodes = new LinkedHashSet<>();
        if (p instanceof Node) {
            nodes.add((Node) p);
        } else if (p instanceof Way) {
            nodes.addAll(((Way) p).getNodes());
        } else if (p instanceof Relation) {
            for (RelationMember m : ((Relation) p).getMembers()) {
                if (m.isNode()) {
                    nodes.add(m.getNode());
                } else if (m.isWay()) {
                    nodes.addAll(m.getWay().getNodes());
                }
            }
        }
        double lat = 0;
        double lon = 0;
        int count = 0;
        for (Node n : nodes) {
            if (placed(n)) {
                lat += n.lat();
                lon += n.lon();
                count++;
            }
        }
        return count == 0 ? null : new LatLon(lat / count, lon / count);
    }

    /** A lat/lon box holding every point within radius metres of the given one. */
    static BBox around(LatLon at, double radius) {
        double dLat = radius / METRES_PER_DEGREE_LAT;
        double dLon = radius / (METRES_PER_DEGREE_LON_AT_EQUATOR * Math.max(0.01, Math.cos(Math.toRadians(at.lat()))));
        return new BBox(Math.max(-180, at.lon() - dLon), Math.max(-90, at.lat() - dLat),
                Math.min(180, at.lon() + dLon), Math.min(90, at.lat() + dLat));
    }

    /**
     * True when the dataset has download bounds and the box is not wholly inside
     * them: something just outside may simply not be loaded.
     */
    static boolean leavesDownload(DataSet ds, BBox box) {
        if (ds == null || ds.getDataSourceBounds().isEmpty()) {
            return false;
        }
        Area area = ds.getDataSourceArea();
        return area == null || !area.contains(new Rectangle2D.Double(box.getTopLeftLon(), box.getBottomRightLat(),
                box.getBottomRightLon() - box.getTopLeftLon(), box.getTopLeftLat() - box.getBottomRightLat()));
    }

    /**
     * Roads within radius metres of a point, nearest first. Only ways accepted by
     * the filter are measured.
     */
    static List<RoadHit> roadsNear(DataSet ds, LatLon at, double radius, Predicate<Way> accept) {
        LocalProjection proj = new LocalProjection(at.lat(), at.lon());
        Coordinate p = proj.toXY(at);
        List<RoadHit> out = new ArrayList<>();
        for (Way w : ds.searchWays(around(at, radius))) {
            if (!accept.test(w)) {
                continue;
            }
            Polyline line = Polyline.of(w.getNodes(), proj);
            if (line == null) {
                continue;
            }
            Location loc = line.locate(p);
            if (loc.distance <= radius) {
                out.add(new RoadHit(w, line, loc));
            }
        }
        out.sort(Comparator.comparingDouble(h -> h.location.distance));
        return out;
    }

    /**
     * Sharpest turn, in degrees, at which two ways sharing an end node still join
     * into one chain. The two halves of a divided road meet at their ends; joined,
     * they would make a U-turn and swap the sides of the street halfway along.
     */
    static final double MAX_TURN = 120.0;

    /**
     * Joins ways that share end nodes into runs of nodes, one per chain, each in
     * a single consistent direction. A street split into many ways comes back as
     * one run. Where three or more ways meet at one end the straightest
     * continuation carries the run on and the others start their own, and no
     * run turns back on itself by more than {@link #MAX_TURN}.
     */
    static List<List<Node>> chains(Collection<Way> ways) {
        List<List<Node>> out = new ArrayList<>();
        for (Chain c : chainsOf(ways)) {
            out.add(c.nodes);
        }
        return out;
    }

    /**
     * The runs of {@link #chains}, each with the ways it is made of, whether
     * each end is a dead end and which of its segments go round a loop.
     * <p>
     * A closed way that meets the run at one of its ends, such as a cul-de-sac
     * bulb drawn as a ring, is walked round and the run comes back to the
     * junction: stem, loop, junction. A way that comes back to one of its own
     * nodes (drawn as a P) gives the same shape.
     */
    static List<Chain> chainsOf(Collection<Way> ways) {
        Map<Node, List<Way>> byEnd = new HashMap<>();
        List<Way> usable = new ArrayList<>();
        for (Way w : ways) {
            if (w.getNodesCount() < 2) {
                continue;
            }
            usable.add(w);
            byEnd.computeIfAbsent(w.firstNode(), k -> new ArrayList<>()).add(w);
            if (w.lastNode() != w.firstNode()) {
                byEnd.computeIfAbsent(w.lastNode(), k -> new ArrayList<>()).add(w);
            }
        }
        Set<Way> used = new HashSet<>();
        List<Chain> out = new ArrayList<>();
        for (Way start : usable) {
            if (!used.add(start)) {
                continue;
            }
            Set<Way> own = new LinkedHashSet<>();
            own.add(start);
            Deque<Node> run = new ArrayDeque<>(start.getNodes());
            extend(run, byEnd, used, own, true);
            extend(run, byEnd, used, own, false);
            out.add(new Chain(new ArrayList<>(run), own));
        }
        return out;
    }

    private static void extend(Deque<Node> run, Map<Node, List<Way>> byEnd, Set<Way> used, Set<Way> own,
            boolean atTail) {
        while (true) {
            Node end = atTail ? run.peekLast() : run.peekFirst();
            if (end == null) {
                return;
            }
            Node before = neighbour(run, atTail);
            Way next = null;
            List<Node> nextNodes = null;
            double bestTurn = MAX_TURN;
            for (Way w : byEnd.getOrDefault(end, List.of())) {
                if (used.contains(w)) {
                    continue;
                }
                List<Node> nodes = new ArrayList<>(w.getNodes());
                if (nodes.get(0) != end) {
                    java.util.Collections.reverse(nodes);
                }
                List<List<Node>> walks = new ArrayList<>();
                walks.add(nodes);
                if (w.isClosed()) {
                    // A ring can be walked either way round from the junction.
                    List<Node> back = new ArrayList<>(nodes);
                    java.util.Collections.reverse(back);
                    walks.add(back);
                }
                for (List<Node> walk : walks) {
                    double turn = turn(before, end, walk.get(1));
                    if (turn <= bestTurn) {
                        bestTurn = turn;
                        next = w;
                        nextNodes = walk;
                    }
                }
            }
            if (next == null) {
                return;
            }
            used.add(next);
            own.add(next);
            List<Node> nodes = nextNodes;
            // nodes now starts at end; skip it and walk away from the run.
            for (int i = 1; i < nodes.size(); i++) {
                if (atTail) {
                    run.addLast(nodes.get(i));
                } else {
                    run.addFirst(nodes.get(i));
                }
            }
        }
    }

    /**
     * Longest loop, in metres, still taken for a cul-de-sac bulb. A longer ring is
     * a street in its own right, where houses have a real side and parity means
     * something.
     */
    static final double MAX_BULB_LOOP = 300.0;

    /**
     * True when no road outside the chain's own ways uses the node and the run
     * does not pass through it again: the street simply stops there. A driveway
     * or parking aisle leaving the end does not make it a through road.
     */
    private static boolean isDeadEnd(List<Node> run, Node n, Set<Way> own) {
        if (run.indexOf(n) != run.lastIndexOf(n)) {
            return false;
        }
        for (OsmPrimitive r : n.getReferrers()) {
            if (r instanceof Way && !own.contains(r) && StreetNames.isRoad((Way) r) && !isDriveway((Way) r)) {
                return false;
            }
        }
        return true;
    }

    static boolean isDriveway(Way w) {
        String service = w.get("service");
        return "driveway".equals(service) || "parking_aisle".equals(service);
    }

    /** Metres along the run from node index from to node index to, skipping unplaced nodes. */
    private static double runLength(List<Node> nodes, int from, int to) {
        double length = 0;
        Node prev = null;
        for (int i = from; i <= to; i++) {
            Node n = nodes.get(i);
            if (!placed(n)) {
                continue;
            }
            if (prev != null) {
                length += prev.greatCircleDistance(n);
            }
            prev = n;
        }
        return length;
    }

    /**
     * A chain of joined street ways: its run of nodes, the ways it is made of,
     * whether each end is a dead end, and which segments go round a loop.
     */
    static final class Chain {
        final List<Node> nodes;
        final Set<Way> ways;
        /** No other road meets the first node: houses ahead of it are still on this street. */
        final boolean deadStart;
        /** No other road meets the last node. */
        final boolean deadEnd;
        /** loop[i]: the segment from nodes[i] to nodes[i + 1] lies on a loop. */
        private final boolean[] loop;

        Chain(List<Node> nodes, Set<Way> ways) {
            this.nodes = java.util.Collections.unmodifiableList(nodes);
            this.ways = java.util.Collections.unmodifiableSet(ways);
            boolean any = !nodes.isEmpty();
            deadStart = any && isDeadEnd(nodes, nodes.get(0), ways);
            deadEnd = any && isDeadEnd(nodes, nodes.get(nodes.size() - 1), ways);
            // Segments between the first and the last visit of a node the run passes more than
            // once, when that round trip is short enough to be a bulb rather than a ring street.
            loop = new boolean[Math.max(0, nodes.size() - 1)];
            Map<Node, Integer> first = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                Integer f = first.putIfAbsent(nodes.get(i), i);
                if (f != null && runLength(nodes, f, i) <= MAX_BULB_LOOP) {
                    for (int s = f; s < i; s++) {
                        loop[s] = true;
                    }
                }
            }
        }

        /** True when the segment from node i to node i + 1 lies on a loop. */
        boolean loopSegment(int i) {
            return i >= 0 && i < loop.length && loop[i];
        }
    }

    /** The node next to the end of the run that is being extended. */
    private static Node neighbour(Deque<Node> run, boolean atTail) {
        java.util.Iterator<Node> it = atTail ? run.descendingIterator() : run.iterator();
        it.next();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * How sharply a run coming from a through b turns to head for c, in degrees:
     * 0 is straight on, 180 straight back. 0 when any point is unplaced.
     */
    static double turn(Node a, Node b, Node c) {
        if (!placed(a) || !placed(b) || !placed(c)) {
            return 0;
        }
        double k = Math.cos(Math.toRadians(b.lat()));
        double x1 = (b.lon() - a.lon()) * k;
        double y1 = b.lat() - a.lat();
        double x2 = (c.lon() - b.lon()) * k;
        double y2 = c.lat() - b.lat();
        double n = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        if (n == 0) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (x1 * x2 + y1 * y2) / n))));
    }

    /** A road found near a point: the way, its polyline and where the point sits relative to it. */
    static final class RoadHit {
        final Way way;
        final Polyline line;
        final Location location;

        RoadHit(Way way, Polyline line, Location location) {
            this.way = way;
            this.line = line;
            this.location = location;
        }
    }

    /** Where a point sits relative to a polyline. */
    static final class Location {
        /** {@link #end}: the point lies ahead of the line's start. */
        static final int START = -1;
        /** {@link #end}: the point lies past the line's end. */
        static final int END = 1;

        /** Metres from the point to the nearest point of the line. */
        final double distance;
        /** Metres along the line from its start to that nearest point. */
        final double along;
        /** +1 left of the line's direction, -1 right, 0 on it. */
        final int side;
        /**
         * Metres the point lies past the start or end of the line, measured along the
         * end segment; 0 when it projects onto the line itself.
         */
        final double overshoot;
        /** Metres from the point to the line (or its end segment's extension), measured square to it. */
        final double offset;
        /** Index of the segment the nearest point is on. */
        final int segment;
        /** Which end the point lies past: {@link #START}, {@link #END}, or 0 when neither. */
        final int end;

        Location(double distance, double along, int side, double overshoot, double offset, int segment, int end) {
            this.distance = distance;
            this.along = along;
            this.side = side;
            this.overshoot = overshoot;
            this.offset = offset;
            this.segment = segment;
            this.end = end;
        }

        /**
         * True when the point is more ahead of the line's end than beside it, so its
         * side says little: the house at the head of a cul-de-sac, or one on the
         * cross street.
         */
        boolean pastEnd() {
            return overshoot > offset;
        }

        /**
         * Distance along the line, carried on past the end that was overshot: negative
         * ahead of the start, more than the length past the end.
         */
        double alongExtended() {
            return end == START ? along - overshoot : end == END ? along + overshoot : along;
        }
    }

    /** A polyline in local metres. */
    static final class Polyline {
        private final Coordinate[] pts;
        private final double[] cum;
        /** loop[i]: segment i lies on a loop of its chain. */
        private final boolean[] loop;

        Polyline(List<Coordinate> points) {
            this(points, null);
        }

        /**
         * @param segLoop for each segment between consecutive points, whether it lies
         *        on a loop; null when none does
         */
        private Polyline(List<Coordinate> points, boolean[] segLoop) {
            List<Coordinate> clean = new ArrayList<>();
            List<Boolean> flags = new ArrayList<>();
            boolean pending = true;
            for (int i = 0; i < points.size(); i++) {
                Coordinate c = points.get(i);
                if (clean.isEmpty()) {
                    clean.add(c);
                    continue;
                }
                // A segment merged with dropped zero-length ones is a loop segment only if all were.
                pending &= segLoop != null && segLoop[i - 1];
                if (!clean.get(clean.size() - 1).equals2D(c)) {
                    clean.add(c);
                    flags.add(pending);
                    pending = true;
                }
            }
            if (clean.size() < 2) {
                throw new IllegalArgumentException("a polyline needs two distinct points");
            }
            pts = clean.toArray(new Coordinate[0]);
            cum = new double[pts.length];
            for (int i = 1; i < pts.length; i++) {
                cum[i] = cum[i - 1] + pts[i - 1].distance(pts[i]);
            }
            loop = new boolean[flags.size()];
            for (int i = 0; i < loop.length; i++) {
                loop[i] = flags.get(i);
            }
        }

        /** The placed nodes as a polyline, or null when fewer than two distinct points remain. */
        static Polyline of(List<Node> nodes, LocalProjection proj) {
            return of(nodes, null, proj);
        }

        /** The chain's placed nodes as a polyline that knows its loop segments; null as for {@link #of(List, LocalProjection)}. */
        static Polyline of(Chain chain, LocalProjection proj) {
            return of(chain.nodes, chain, proj);
        }

        private static Polyline of(List<Node> nodes, Chain chain, LocalProjection proj) {
            List<Coordinate> cs = new ArrayList<>(nodes.size());
            List<Boolean> flags = new ArrayList<>();
            int prev = -1;
            for (int i = 0; i < nodes.size(); i++) {
                Node n = nodes.get(i);
                if (!placed(n)) {
                    continue;
                }
                if (prev >= 0) {
                    boolean all = chain != null;
                    for (int s = prev; s < i && all; s++) {
                        all = chain.loopSegment(s);
                    }
                    flags.add(all);
                }
                cs.add(proj.toXY(n.getCoor()));
                prev = i;
            }
            boolean[] segLoop = new boolean[flags.size()];
            for (int i = 0; i < segLoop.length; i++) {
                segLoop[i] = flags.get(i);
            }
            try {
                return new Polyline(cs, segLoop);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        double length() {
            return cum[cum.length - 1];
        }

        /** True when the segment lies on a loop of its chain (a cul-de-sac bulb drawn as a ring). */
        boolean loopSegment(int segment) {
            return segment >= 0 && segment < loop.length && loop[segment];
        }

        Location locate(Coordinate p) {
            int last = pts.length - 2;
            Location best = null;
            for (int i = 0; i <= last; i++) {
                Coordinate a = pts[i];
                Coordinate b = pts[i + 1];
                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double len = Math.sqrt(dx * dx + dy * dy);
                double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (len * len);
                double overshoot = 0;
                int end = 0;
                if (t < 0) {
                    if (i == 0) {
                        overshoot = -t * len;
                        end = Location.START;
                    }
                    t = 0;
                } else if (t > 1) {
                    if (i == last) {
                        overshoot = (t - 1) * len;
                        end = Location.END;
                    }
                    t = 1;
                }
                double qx = a.x + t * dx;
                double qy = a.y + t * dy;
                double d = Math.hypot(p.x - qx, p.y - qy);
                if (best == null || d < best.distance) {
                    double cross = dx * (p.y - a.y) - dy * (p.x - a.x);
                    best = new Location(d, cum[i] + t * len, (int) Math.signum(cross), overshoot, Math.abs(cross) / len,
                            i, end);
                }
            }
            return best;
        }
    }
}
