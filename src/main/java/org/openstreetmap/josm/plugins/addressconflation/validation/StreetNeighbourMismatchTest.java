// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
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
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressGeometry.RoadHit;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Warns when an address names a different street from nearly all of its close
 * neighbours on the same side of the road, and the neighbours' street is the
 * road it sits beside. That catches a wrong street that really is nearby,
 * which the street-name test cannot see.
 * <p>
 * Only an address whose own street is nearby is checked; one whose street
 * cannot be found is the street-name test's business. An address whose own
 * street runs about as close as the road beside it (a corner lot) is left
 * alone, and so is one whose street is the road beside it: then it is the
 * neighbours that disagree with the road. Shop and amenity points and
 * outbuildings take no part, as their position says little. Spelling variants of one street
 * (W Olive Ave, West Olive Avenue) count as the same street here; the
 * street-name test reports those.
 */
public class StreetNeighbourMismatchTest extends Test {
    /** addr:street differs from the street the neighbours and the nearest road agree on. */
    public static final int CODE = 47106;

    /** Preference key: how far away a neighbour may be, in metres. */
    public static final String PREF_RADIUS = "addressconflation.validator.neighbour.radius";
    /** Default for {@link #PREF_RADIUS}. */
    public static final double DEFAULT_RADIUS = 60.0;

    /** How far the road an address sits beside may be, in metres. */
    static final double ROAD_RADIUS = 60.0;
    /**
     * An address whose own street is within this many metres, or within
     * {@link #CORNER_FACTOR} times its distance to the road it sits beside, is a
     * corner lot.
     */
    static final double CORNER_RADIUS = 25.0;
    /** See {@link #CORNER_RADIUS}. */
    static final double CORNER_FACTOR = 2.0;
    /** Neighbours considered, nearest first. */
    static final int MAX_NEIGHBOURS = 6;
    /** Fewest neighbours to say anything. */
    static final int MIN_NEIGHBOURS = 4;
    /** Share of the neighbours that must agree on one other street, and on one spelling for a fix. */
    static final double AGREEMENT = 0.75;

    private final Map<DataSet, Set<OsmPrimitive>> visited = new LinkedHashMap<>();
    private double radius = DEFAULT_RADIUS;
    private double streetRadius = StreetNameMismatchTest.DEFAULT_RADIUS;
    /** The mapper's outbuilding list, read with the other preferences at the start of each run. */
    private ConflationSettings settings = new ConflationSettings();

    public StreetNeighbourMismatchTest() {
        super(tr("Street differs from neighbours"),
                tr("Finds addresses whose addr:street differs from the neighbouring addresses along the same road"));
    }

    @Override
    public void startTest(ProgressMonitor progressMonitor) {
        super.startTest(progressMonitor);
        double r = Config.getPref().getDouble(PREF_RADIUS, DEFAULT_RADIUS);
        radius = Double.isFinite(r) && r > 0 ? r : DEFAULT_RADIUS;
        double s = Config.getPref().getDouble(StreetNameMismatchTest.PREF_RADIUS, StreetNameMismatchTest.DEFAULT_RADIUS);
        streetRadius = Double.isFinite(s) && s > 0 ? s : StreetNameMismatchTest.DEFAULT_RADIUS;
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
        return p.isUsable() && p.getDataSet() != null && !p.hasKey("highway") && StreetNames.streetKey(p) != null
                && AddressGeometry.isAddressCarrier(p, settings);
    }

    @Override
    public void endTest() {
        try {
            for (Map.Entry<DataSet, Set<OsmPrimitive>> e : visited.entrySet()) {
                if (isCanceled()) {
                    break;
                }
                new Run(e.getKey()).check(e.getValue());
            }
        } finally {
            visited.clear();
        }
        super.endTest();
    }

    @Override
    public boolean isFixable(TestError e) {
        return e.getCode() == CODE && fixSpelling(e) != null;
    }

    @Override
    public Command fixError(TestError e) {
        String spelling = e.getCode() == CODE ? fixSpelling(e) : null;
        if (spelling == null) {
            return null;
        }
        return new ChangePropertyCommand(e.getPrimitives(), "addr:street", spelling);
    }

    /**
     * The neighbours' spelling of their street, read from the highlighted
     * neighbours as they are now: the most common raw addr:street among them,
     * when it holds at least {@link #AGREEMENT} of them and differs from the
     * flagged address's own value. Null otherwise.
     */
    private static String fixSpelling(TestError e) {
        Collection<? extends OsmPrimitive> flagged = e.getPrimitives();
        if (flagged.size() != 1) {
            return null;
        }
        OsmPrimitive p = flagged.iterator().next();
        if (p.isDeleted() || p.getDataSet() == null) {
            return null;
        }
        List<OsmPrimitive> neighbours = new ArrayList<>();
        for (Object o : e.getHighlighted()) {
            if (o instanceof OsmPrimitive && o != p && !((OsmPrimitive) o).isDeleted()) {
                neighbours.add((OsmPrimitive) o);
            }
        }
        String best = commonest(neighbours, AGREEMENT);
        return best == null || best.equals(p.get("addr:street")) ? null : best;
    }

    /**
     * The most common single addr:street value among the primitives, when it holds
     * at least the given share of those that have one; otherwise null.
     */
    static String commonest(Collection<? extends OsmPrimitive> ps, double share) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (OsmPrimitive o : ps) {
            List<String> v = StreetNames.splitValues(o.get("addr:street"));
            if (v.size() == 1) {
                counts.merge(v.get(0), 1, Integer::sum);
                total++;
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            if (c.getValue() > bestCount) {
                best = c.getKey();
                bestCount = c.getValue();
            }
        }
        return best != null && bestCount >= share * total ? best : null;
    }

    /** One pass over one dataset. */
    private final class Run {
        private final DataSet ds;
        private final Map<Way, Set<String>> roadKeys = new HashMap<>();
        private final Map<Long, List<Placed>> grid = new HashMap<>();
        private final Map<OsmPrimitive, Placed> placed = new HashMap<>();
        private LocalProjection proj;

        Run(DataSet ds) {
            this.ds = ds;
        }

        private Set<String> keysOf(Way w) {
            return roadKeys.computeIfAbsent(w, StreetNames::roadKeys);
        }

        /** Every address of the dataset in a grid of radius-sized cells, so neighbour lookups stay local. */
        private void index() {
            List<OsmPrimitive> all = new ArrayList<>();
            addAll(all, ds.getNodes());
            addAll(all, ds.getWays());
            addAll(all, ds.getRelations());
            for (OsmPrimitive p : all) {
                LatLon at = AddressGeometry.point(p);
                if (at == null) {
                    continue;
                }
                if (proj == null) {
                    proj = new LocalProjection(at.lat(), at.lon());
                }
                Placed a = new Placed(p, at, proj.toXY(at));
                placed.put(p, a);
                grid.computeIfAbsent(cell(a.xy), k -> new ArrayList<>()).add(a);
            }
        }

        private void addAll(List<OsmPrimitive> out, Collection<? extends OsmPrimitive> ps) {
            for (OsmPrimitive p : ps) {
                if (isAddress(p)) {
                    out.add(p);
                }
            }
        }

        private long cell(Coordinate c) {
            return key((long) Math.floor(c.x / radius), (long) Math.floor(c.y / radius));
        }

        private long key(long cx, long cy) {
            return (cx << 32) ^ (cy & 0xffffffffL);
        }

        private List<Placed> within(Placed a) {
            List<Placed> out = new ArrayList<>();
            long cx = (long) Math.floor(a.xy.x / radius);
            long cy = (long) Math.floor(a.xy.y / radius);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dy = -1; dy <= 1; dy++) {
                    for (Placed b : grid.getOrDefault(key(cx + dx, cy + dy), List.of())) {
                        if (b != a && b.xy.distance(a.xy) <= radius) {
                            out.add(b);
                        }
                    }
                }
            }
            return out;
        }

        void check(Set<OsmPrimitive> subjects) {
            index();
            for (OsmPrimitive p : subjects) {
                if (isCanceled()) {
                    return;
                }
                Placed a = placed.get(p);
                if (a != null) {
                    check(a);
                }
            }
        }

        private void check(Placed a) {
            String own = StreetNames.streetKey(a.p);
            List<RoadHit> roads = AddressGeometry.roadsNear(ds, a.at, ROAD_RADIUS,
                    w -> StreetNames.isRoad(w) && !keysOf(w).isEmpty());
            if (roads.isEmpty()) {
                return;
            }
            RoadHit road = roads.get(0);
            Set<String> roadNames = keysOf(road.way);
            int side = road.location.side;
            if (side == 0 || roadNames.contains(own)) {
                return;
            }
            // The address's own street must be nearby (else the street-name test reports it),
            // but not so near that this is a corner lot.
            double cornerReach = Math.max(CORNER_RADIUS, CORNER_FACTOR * road.location.distance);
            List<RoadHit> ownStreet = AddressGeometry.roadsNear(ds, a.at, streetRadius,
                    w -> StreetNames.isRoad(w) && keysOf(w).contains(own));
            if (ownStreet.isEmpty() || ownStreet.get(0).location.distance <= cornerReach) {
                return;
            }
            // Same projection origin as roadsNear used, so road.line and these points share metres.
            LocalProjection local = new LocalProjection(a.at.lat(), a.at.lon());
            List<Placed> near = within(a);
            near.sort(Comparator.comparingDouble(b -> b.xy.distance(a.xy)));
            List<Placed> neighbours = new ArrayList<>();
            for (Placed b : near) {
                if (road.line.locate(local.toXY(b.at)).side == side) {
                    neighbours.add(b);
                    if (neighbours.size() == MAX_NEIGHBOURS) {
                        break;
                    }
                }
            }
            if (neighbours.size() < MIN_NEIGHBOURS) {
                return;
            }
            Map<String, List<Placed>> byStreet = new HashMap<>();
            for (Placed b : neighbours) {
                byStreet.computeIfAbsent(StreetNames.streetKey(b.p), k -> new ArrayList<>()).add(b);
            }
            String other = null;
            List<Placed> agreeing = List.of();
            for (Map.Entry<String, List<Placed>> e : byStreet.entrySet()) {
                if (e.getValue().size() > agreeing.size()) {
                    other = e.getKey();
                    agreeing = e.getValue();
                }
            }
            if (other == null || other.equals(own) || agreeing.size() < AGREEMENT * neighbours.size()
                    || !roadNames.contains(other)) {
                return;
            }
            List<OsmPrimitive> highlight = new ArrayList<>();
            highlight.add(a.p);
            for (Placed b : agreeing) {
                highlight.add(b.p);
            }
            String spelling = commonest(highlight.subList(1, highlight.size()), 0);
            errors.add(TestError.builder(StreetNeighbourMismatchTest.this, Severity.WARNING, CODE)
                    .message(tr("addr:street differs from the neighbouring addresses"),
                            marktr("addr:street={0}, neighbours use {1}"),
                            StreetNames.street(a.p), spelling)
                    .primitives(a.p)
                    .highlight(highlight)
                    .build());
        }
    }

    /** An address and where it is. */
    private static final class Placed {
        final OsmPrimitive p;
        final LatLon at;
        final Coordinate xy;

        Placed(OsmPrimitive p, LatLon at, Coordinate xy) {
            this.p = p;
            this.at = at;
            this.xy = xy;
        }
    }
}
