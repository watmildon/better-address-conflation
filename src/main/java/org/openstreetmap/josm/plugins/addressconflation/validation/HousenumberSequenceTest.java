// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.locationtech.jts.geom.Coordinate;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.gui.AddressConflationPreferences;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.Chain;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.Location;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.Polyline;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Checks house numbers along each street: odd and even numbers should keep to
 * their own side, and numbers should rise (or fall) steadily along it.
 * <p>
 * Every way named after the street is joined into chains, so a street split
 * into many ways is seen whole. Each address is placed on the nearest chain of
 * its own street, on one side of it, at some distance along it. An address
 * that is closer to a differently named road than to its own street (a corner
 * lot) or that lies ahead of a street end where another road joins is left
 * out, because its side and position say little. Ahead of a dead end, such as
 * the head of a cul-de-sac, the address still belongs to the street and its
 * position runs on past the end.
 * <p>
 * Numbers are expected to run the same way along both sides of a chain, so the
 * direction is taken from the whole chain. Addresses beside a loop of the
 * chain (a cul-de-sac bulb drawn as a ring) all fall on its outer side whatever
 * their parity, so they take no part in the parity check and are never
 * reported as out of sequence, though they still count as neighbours.
 * <p>
 * Missing data cannot invent an inversion or an odd number on an even side, it
 * can only hide one. What it can do is leave out the bit of street or the cross
 * street an address really sits beside, so results whose search circle leaves
 * the downloaded area are marked as such.
 */
public class HousenumberSequenceTest extends Test {
    /** A house number out of order with both its neighbours along the street. */
    public static final int CODE_SEQUENCE = 47104;
    /** A house number whose parity differs from nearly every other on its side. */
    public static final int CODE_PARITY = 47105;

    /** Preference key: how far an address may be from its street to be checked, in metres. */
    public static final String PREF_RADIUS = "addressconflation.validator.sequence.radius";
    /** Default for {@link #PREF_RADIUS}. */
    public static final double DEFAULT_RADIUS = 100.0;
    /** Preference key: whether the parity check runs. */
    public static final String PREF_PARITY = "addressconflation.validator.parity.enabled";

    /** A side needs this many numbered addresses before the parity check speaks. */
    static final int MIN_SIDE = 4;
    /**
     * A side needs this many numbered addresses before the sequence check speaks;
     * the numbering direction comes from the whole chain, so a short side of a
     * cul-de-sac can be checked.
     */
    static final int MIN_SIDE_SEQUENCE = 3;
    /** Share of one parity on a side above which the others are flagged. */
    static final double PARITY_SHARE = 0.8;
    /** An address this much closer (as a fraction) to a differently named road is a corner lot. */
    static final double CORNER_RATIO = 0.4;
    /**
     * Two addresses closer than this along the street (metres) are not in any
     * meaningful order: units of one building, or a house and one behind it.
     * Nor is an address whose position falls within the stretch of street a
     * building outline spans (a unit node inside it).
     */
    static final double MIN_GAP = 8.0;
    /** An address closer than this to the street line (metres), such as a skybridge, has no clear side. */
    static final double MIN_OFFSET = 3.0;

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d{1,9})");

    /** Primitives visited this run, by dataset; only these are reported. */
    private final Map<DataSet, Set<OsmPrimitive>> visited = new LinkedHashMap<>();
    private double radius = DEFAULT_RADIUS;
    private boolean parity = true;
    /** The mapper's outbuilding list, read with the other preferences at the start of each run. */
    private ConflationSettings settings = new ConflationSettings();

    public HousenumberSequenceTest() {
        super(tr("House number sequence"),
                tr("Finds house numbers out of order along their street or on the wrong side of it"));
    }

    @Override
    public void startTest(ProgressMonitor progressMonitor) {
        super.startTest(progressMonitor);
        double r = Config.getPref().getDouble(PREF_RADIUS, DEFAULT_RADIUS);
        radius = Double.isFinite(r) && r > 0 ? r : DEFAULT_RADIUS;
        parity = Config.getPref().getBoolean(PREF_PARITY, true);
        settings = AddressConflationPreferences.fromPreferences();
        visited.clear();
    }

    @Override
    public void visit(Node n) {
        collect(n);
    }

    @Override
    public void visit(Way w) {
        collect(w);
    }

    @Override
    public void visit(Relation r) {
        collect(r);
    }

    private void collect(OsmPrimitive p) {
        if (isAddress(p)) {
            visited.computeIfAbsent(p.getDataSet(), k -> new LinkedHashSet<>()).add(p);
        }
    }

    private boolean isAddress(OsmPrimitive p) {
        return p.isUsable() && p.getDataSet() != null && !p.hasKey("highway")
                && p.hasKey("addr:housenumber") && StreetNames.streetKey(p) != null
                && AddressGeometry.isAddressCarrier(p, settings);
    }

    @Override
    public void endTest() {
        try {
            for (Map.Entry<DataSet, Set<OsmPrimitive>> e : visited.entrySet()) {
                if (isCanceled()) {
                    break;
                }
                new Run(e.getKey(), e.getValue()).analyse();
            }
        } finally {
            visited.clear();
        }
        super.endTest();
    }

    /** Leading integer of a house number ("24A" and "24 1/2" give 24, "100-104" gives 100), or -1. */
    static long number(String housenumber) {
        if (housenumber == null) {
            return -1;
        }
        Matcher m = LEADING_NUMBER.matcher(housenumber);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** One validation pass over one dataset. */
    private final class Run {
        private final DataSet ds;
        private final Set<OsmPrimitive> subjects;
        private final Map<Way, Set<String>> roadKeys = new HashMap<>();
        private final Set<OsmPrimitive> reportedSequence = new HashSet<>();
        private final Set<OsmPrimitive> reportedParity = new HashSet<>();

        Run(DataSet ds, Set<OsmPrimitive> subjects) {
            this.ds = ds;
            this.subjects = subjects;
        }

        private Set<String> keysOf(Way w) {
            return roadKeys.computeIfAbsent(w, StreetNames::roadKeys);
        }

        void analyse() {
            Set<String> streets = new HashSet<>();
            for (OsmPrimitive p : subjects) {
                streets.add(StreetNames.streetKey(p));
            }
            Map<String, List<Way>> roads = new HashMap<>();
            for (Way w : ds.getWays()) {
                if (StreetNames.isRoad(w)) {
                    for (String k : keysOf(w)) {
                        if (streets.contains(k)) {
                            roads.computeIfAbsent(k, x -> new ArrayList<>()).add(w);
                        }
                    }
                }
            }
            if (roads.isEmpty()) {
                return;
            }
            // Context comes from the whole dataset, not only from what was visited.
            Map<String, List<OsmPrimitive>> addresses = new HashMap<>();
            addAddresses(addresses, ds.getNodes(), roads.keySet());
            addAddresses(addresses, ds.getWays(), roads.keySet());
            addAddresses(addresses, ds.getRelations(), roads.keySet());
            for (Map.Entry<String, List<Way>> e : roads.entrySet()) {
                if (isCanceled()) {
                    return;
                }
                List<OsmPrimitive> onStreet = addresses.get(e.getKey());
                if (onStreet != null && onStreet.size() >= MIN_SIDE_SEQUENCE) {
                    street(e.getKey(), e.getValue(), onStreet);
                }
            }
        }

        private void addAddresses(Map<String, List<OsmPrimitive>> out, Collection<? extends OsmPrimitive> ps,
                Set<String> streets) {
            for (OsmPrimitive p : ps) {
                if (isAddress(p)) {
                    String k = StreetNames.streetKey(p);
                    if (streets.contains(k)) {
                        out.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
                    }
                }
            }
        }

        private void street(String key, List<Way> ways, List<OsmPrimitive> onStreet) {
            BBox box = new BBox();
            for (Way w : ways) {
                box.addPrimitive(w, 0);
            }
            if (!box.isValid()) {
                return;
            }
            LatLon centre = box.getCenter();
            LocalProjection proj = new LocalProjection(centre.lat(), centre.lon());
            List<Chain> chains = new ArrayList<>();
            List<Polyline> lines = new ArrayList<>();
            for (Chain chain : AddressGeometry.chainsOf(ways)) {
                Polyline line = Polyline.of(chain, proj);
                if (line != null) {
                    chains.add(chain);
                    lines.add(line);
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            // (chain index, side) -> numbered addresses on that side
            Map<Long, List<Placed>> sides = new LinkedHashMap<>();
            for (OsmPrimitive p : onStreet) {
                long n = number(p.get("addr:housenumber"));
                if (n < 0) {
                    continue;
                }
                LatLon at = AddressGeometry.point(p);
                if (at == null) {
                    continue;
                }
                Coordinate xy = proj.toXY(at);
                int bestChain = -1;
                Location best = null;
                for (int i = 0; i < lines.size(); i++) {
                    Location loc = lines.get(i).locate(xy);
                    if (best == null || loc.distance < best.distance) {
                        best = loc;
                        bestChain = i;
                    }
                }
                if (best.distance > radius || best.distance < MIN_OFFSET || best.side == 0) {
                    continue;
                }
                Chain chain = chains.get(bestChain);
                // Ahead of an end where another road joins, the address is a corner house
                // of the cross street; ahead of a dead end it is at the head of this one.
                boolean head = best.pastEnd();
                if (head && !deadEnd(chain, best.end) || isCorner(at, key, best.distance)) {
                    continue;
                }
                Polyline line = lines.get(bestChain);
                sides.computeIfAbsent(bestChain * 2L + (best.side > 0 ? 1 : 0), k -> new ArrayList<>())
                        .add(new Placed(p, at, n, along(best, chain),
                                extent(p, chain, line, proj, along(best, chain)), line.loopSegment(best.segment), head));
            }
            for (int c = 0; c < lines.size(); c++) {
                List<List<Placed>> chainSides = new ArrayList<>();
                for (long k = c * 2L; k <= c * 2L + 1; k++) {
                    List<Placed> side = sides.get(k);
                    if (side != null) {
                        side = withoutStrayHeads(side);
                        side.sort(Comparator.comparingDouble(a -> a.along));
                        chainSides.add(side);
                    }
                }
                // The numbering direction of the whole chain, both sides together.
                int chainSteps = 0;
                for (List<Placed> side : chainSides) {
                    chainSteps += steps(side);
                }
                for (List<Placed> side : chainSides) {
                    if (parity) {
                        List<Placed> checked = new ArrayList<>();
                        for (Placed a : side) {
                            if (!a.loop) {
                                checked.add(a);
                            }
                        }
                        if (checked.size() >= MIN_SIDE) {
                            parity(checked);
                        }
                    }
                    if (side.size() >= MIN_SIDE_SEQUENCE) {
                        int dir = chainSteps != 0 ? Integer.signum(chainSteps) : Integer.signum(steps(side));
                        if (dir != 0) {
                            sequence(side, dir);
                        }
                    }
                }
            }
        }

        private boolean deadEnd(Chain chain, int end) {
            return end == Location.END ? chain.deadEnd : end == Location.START && chain.deadStart;
        }

        /** Distance along the chain, carried on past a dead end the address lies beyond. */
        private double along(Location loc, Chain chain) {
            return deadEnd(chain, loc.end) ? loc.alongExtended() : loc.along;
        }

        /**
         * The side without the head-of-street addresses whose parity is the odd one
         * out on it. Straight ahead of a dead end, which side a house falls on is a
         * toss-up, so one whose parity nearly every other address on the side lacks
         * most likely carries on the numbering of the other side.
         */
        private List<Placed> withoutStrayHeads(List<Placed> side) {
            int even = 0;
            int others = 0;
            for (Placed a : side) {
                if (!a.head) {
                    others++;
                    if (a.number % 2 == 0) {
                        even++;
                    }
                }
            }
            List<Placed> out = new ArrayList<>();
            for (Placed a : side) {
                if (a.head && others > 0) {
                    int otherParity = a.number % 2 == 0 ? others - even : even;
                    if (otherParity >= PARITY_SHARE * others) {
                        continue;
                    }
                }
                out.add(a);
            }
            return out;
        }

        /** Rising steps minus falling steps between neighbours along a side sorted by distance along. */
        private int steps(List<Placed> side) {
            int net = 0;
            for (int i = 1; i < side.size(); i++) {
                net += Long.signum(side.get(i).number - side.get(i - 1).number);
            }
            return net;
        }

        /** Lowest and highest distance along the chain of the primitive's nodes; just {@code along} for a node. */
        private double[] extent(OsmPrimitive p, Chain chain, Polyline line, LocalProjection proj, double along) {
            double[] out = {along, along};
            if (p instanceof Node) {
                return out;
            }
            Set<Node> nodes = new LinkedHashSet<>();
            if (p instanceof Way) {
                nodes.addAll(((Way) p).getNodes());
            } else {
                for (OsmPrimitive m : ((Relation) p).getMemberPrimitivesList()) {
                    if (m instanceof Way) {
                        nodes.addAll(((Way) m).getNodes());
                    } else if (m instanceof Node) {
                        nodes.add((Node) m);
                    }
                }
            }
            for (Node n : nodes) {
                if (AddressGeometry.placed(n)) {
                    double a = along(line.locate(proj.toXY(n.getCoor())), chain);
                    out[0] = Math.min(out[0], a);
                    out[1] = Math.max(out[1], a);
                }
            }
            return out;
        }

        /** True when a differently named road is much closer than the address's own street. */
        private boolean isCorner(LatLon at, String key, double ownDistance) {
            double reach = ownDistance * CORNER_RATIO;
            if (reach <= 0) {
                return false;
            }
            return !AddressGeometry.roadsNear(ds, at, reach, w -> {
                if (!StreetNames.isRoad(w)) {
                    return false;
                }
                Set<String> keys = keysOf(w);
                return !keys.isEmpty() && !keys.contains(key);
            }).isEmpty();
        }

        private void parity(List<Placed> side) {
            int even = 0;
            for (Placed a : side) {
                if (a.number % 2 == 0) {
                    even++;
                }
            }
            int odd = side.size() - even;
            boolean evenSide;
            if (even >= PARITY_SHARE * side.size()) {
                evenSide = true;
            } else if (odd >= PARITY_SHARE * side.size()) {
                evenSide = false;
            } else {
                return;
            }
            for (Placed a : side) {
                if ((a.number % 2 == 0) != evenSide && subjects.contains(a.p) && reportedParity.add(a.p)) {
                    errors.add(TestError.builder(HousenumberSequenceTest.this, Severity.WARNING, CODE_PARITY)
                            .message(edge(a) ? tr("House number parity differs from its side of the street (at edge of downloaded area)")
                                    : tr("House number parity differs from its side of the street"),
                                    marktr("{0} on the {1} side of {2}"), a.p.get("addr:housenumber"),
                                    evenSide ? tr("even") : tr("odd"), StreetNames.street(a.p))
                            .primitives(a.p)
                            .build());
                }
            }
        }

        /** Reports addresses out of order on a side sorted by distance along; dir is +1 for rising numbers. */
        private void sequence(List<Placed> side, int dir) {
            for (int i = 1; i + 1 < side.size(); i++) {
                Placed prev = side.get(i - 1);
                Placed cur = side.get(i);
                Placed next = side.get(i + 1);
                long a = dir * prev.number;
                long b = dir * cur.number;
                long c = dir * next.number;
                // Neighbours in order with each other, and this one not between them, and
                // clearly apart along the street from the neighbour it disagrees with.
                boolean inversion = a < c && ((b < a && apart(prev, cur)) || (b > c && apart(cur, next)));
                // Round a loop the numbers climb one half and fall the other, so the turn is no inversion.
                if (inversion && !cur.loop && subjects.contains(cur.p) && reportedSequence.add(cur.p)) {
                    errors.add(TestError.builder(HousenumberSequenceTest.this, Severity.WARNING, CODE_SEQUENCE)
                            .message(edge(cur) ? tr("House number out of sequence along the street (at edge of downloaded area)")
                                    : tr("House number out of sequence along the street"),
                                    marktr("{0} between {1} and {2} on {3}"), cur.p.get("addr:housenumber"),
                                    prev.p.get("addr:housenumber"), next.p.get("addr:housenumber"), StreetNames.street(cur.p))
                            .primitives(cur.p)
                            .highlight(cur.p, prev.p, next.p)
                            .build());
                }
            }
        }

        /**
         * True when two addresses are clearly apart along the street: at least
         * {@link #MIN_GAP} between them, and neither inside the other's stretch of
         * street (a unit node inside its building).
         */
        private boolean apart(Placed x, Placed y) {
            return Math.abs(y.along - x.along) >= MIN_GAP && !x.covers(y.along) && !y.covers(x.along);
        }

        private boolean edge(Placed a) {
            return AddressGeometry.leavesDownload(ds, AddressGeometry.around(a.at, radius));
        }
    }

    /** A numbered address placed on one side of a chain. */
    private static final class Placed {
        final OsmPrimitive p;
        final LatLon at;
        final long number;
        final double along;
        /** The stretch of street (lowest, highest distance along) the address's outline spans. */
        final double[] extent;
        /** Nearest to a loop of the chain, such as a cul-de-sac bulb drawn as a ring. */
        final boolean loop;
        /** More ahead of a dead end than beside it: its side is a guess. */
        final boolean head;

        Placed(OsmPrimitive p, LatLon at, long number, double along, double[] extent, boolean loop, boolean head) {
            this.p = p;
            this.at = at;
            this.number = number;
            this.along = along;
            this.extent = extent;
            this.loop = loop;
            this.head = head;
        }

        boolean covers(double a) {
            return a > extent[0] && a < extent[1];
        }
    }
}
