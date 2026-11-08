// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Warns when an element's {@code addr:street} names no road within a fixed
 * radius. A road that matches only after abbreviation expansion, case folding
 * or diacritic stripping gets a softer "near match" warning instead, naming
 * the road so the mapper can decide which spelling is right.
 * <p>
 * Distances are true metres from the nearest part of the address geometry to
 * the nearest segment of the road. When the dataset has download bounds and
 * the search circle reaches outside them, a "no road" result is reported in
 * its own bucket: the road may simply not be loaded, so the mapper should
 * download more before trusting it.
 */
public class StreetNameMismatchTest extends Test {
    /** No road within the radius carries the address's street name. */
    public static final int CODE_NO_ROAD = 47101;
    /** A road within the radius matches only after normalisation (abbreviations, case, diacritics). */
    public static final int CODE_NEAR_MATCH = 47102;
    /** No match, but the search circle leaves the downloaded area, so the road may not be loaded. */
    public static final int CODE_NO_ROAD_AT_EDGE = 47103;

    /** Preference key for the search radius in metres. */
    public static final String PREF_RADIUS = "addressconflation.validator.street.radius";
    /** Default search radius in metres. */
    public static final double DEFAULT_RADIUS = 200.0;

    private static final double METRES_PER_DEGREE_LAT = 110540.0;
    private static final double METRES_PER_DEGREE_LON_AT_EQUATOR = 111320.0;

    private final GeometryFactory factory = new GeometryFactory();
    private final Map<Way, List<RoadName>> roadNames = new HashMap<>();
    /** Whether each dataset seen this run holds any road at all; without one the test has nothing to say. */
    private final Map<DataSet, Boolean> datasetHasRoads = new HashMap<>();
    private double radius = DEFAULT_RADIUS;

    public StreetNameMismatchTest() {
        super(tr("Street name not found nearby"),
                tr("Finds addresses whose addr:street matches no road nearby"));
    }

    @Override
    public void startTest(ProgressMonitor progressMonitor) {
        super.startTest(progressMonitor);
        double r = Config.getPref().getDouble(PREF_RADIUS, DEFAULT_RADIUS);
        radius = Double.isFinite(r) && r > 0 ? r : DEFAULT_RADIUS;
        roadNames.clear();
        datasetHasRoads.clear();
    }

    @Override
    public void endTest() {
        roadNames.clear();
        datasetHasRoads.clear();
        super.endTest();
    }

    @Override
    public void visit(Node n) {
        if (n.isLatLonKnown()) {
            check(n);
        }
    }

    @Override
    public void visit(Way w) {
        check(w);
    }

    @Override
    public void visit(Relation r) {
        check(r);
    }

    private void check(OsmPrimitive p) {
        if (isCanceled() || !p.isUsable() || p.hasKey("highway") || p.getDataSet() == null) {
            return;
        }
        List<String> parts = StreetNames.splitValues(p.get("addr:street"));
        if (parts.isEmpty()) {
            return;
        }
        Address address = null;
        for (String part : parts) {
            String canon = StreetNames.canonical(part);
            if (inAssociatedStreet(p, canon)) {
                continue;
            }
            if (address == null) {
                address = locate(p);
                if (address == null) {
                    return;
                }
            }
            checkPart(p, address, part, canon);
        }
    }

    private void checkPart(OsmPrimitive p, Address address, String part, String canon) {
        // Tier 1: the same name, ignoring only Unicode composition and spacing.
        for (Candidate c : address.candidates) {
            for (RoadName n : c.names) {
                if (n.display.equals(part)) {
                    return;
                }
            }
        }
        // Tier 2: the same name once case, diacritics or abbreviations are set aside.
        String folded = StreetNames.fold(canon);
        String expanded = StreetNames.expand(folded);
        for (Candidate c : address.candidates) {
            for (RoadName n : c.names) {
                if (n.canonical.equals(canon) || n.folded.equals(folded)
                        || (expanded != null && expanded.equals(n.expanded))) {
                    errors.add(TestError.builder(this, Severity.WARNING, CODE_NEAR_MATCH)
                            .message(tr("addr:street differs from the nearby road name"),
                                    marktr("addr:street={0}, road name={1}"), part, n.display)
                            .primitives(p)
                            .highlight(p, c.road)
                            .build());
                    return;
                }
            }
        }
        if (address.circleOutsideDownload) {
            errors.add(TestError.builder(this, Severity.WARNING, CODE_NO_ROAD_AT_EDGE)
                    .message(tr("Street name not found nearby (at edge of downloaded area)"), marktr("addr:street={0}"), part)
                    .primitives(p)
                    .build());
            return;
        }
        errors.add(TestError.builder(this, Severity.WARNING, CODE_NO_ROAD)
                .message(tr("Street name not found nearby"), marktr("addr:street={0}"), part)
                .primitives(p)
                .build());
    }

    /**
     * Geometry, nearby roads and download-bounds coverage for one address, or
     * null when the primitive has no locatable geometry.
     */
    private Address locate(OsmPrimitive p) {
        DataSet ds = p.getDataSet();
        if (!hasRoads(ds)) {
            // An address-only download: nothing to compare against, so stay quiet.
            return null;
        }
        List<List<LatLon>> pieces = geometry(p);
        if (pieces.isEmpty()) {
            return null;
        }
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (List<LatLon> piece : pieces) {
            for (LatLon ll : piece) {
                minLat = Math.min(minLat, ll.lat());
                maxLat = Math.max(maxLat, ll.lat());
                minLon = Math.min(minLon, ll.lon());
                maxLon = Math.max(maxLon, ll.lon());
            }
        }
        double lat0 = (minLat + maxLat) / 2;
        double lon0 = (minLon + maxLon) / 2;
        LocalProjection proj = new LocalProjection(lat0, lon0);
        Geometry shape = toGeometry(pieces, proj);

        // Same scale factors as LocalProjection, so every road within the radius
        // as measured there falls inside this box.
        double dLat = radius / METRES_PER_DEGREE_LAT;
        double dLon = radius / (METRES_PER_DEGREE_LON_AT_EQUATOR * Math.max(0.01, Math.cos(Math.toRadians(lat0))));
        double south = Math.max(-90, minLat - dLat);
        double north = Math.min(90, maxLat + dLat);
        double west = Math.max(-180, minLon - dLon);
        double east = Math.min(180, maxLon + dLon);

        List<Candidate> candidates = new ArrayList<>();
        for (Way w : ds.searchWays(new BBox(west, south, east, north))) {
            if (w == p || !StreetNames.isRoad(w)) {
                continue;
            }
            List<RoadName> names = namesOf(w);
            if (names.isEmpty()) {
                continue;
            }
            Geometry road = toGeometry(Collections.singletonList(coords(w)), proj);
            if (road == null) {
                continue;
            }
            double d = shape.distance(road);
            if (d <= radius) {
                candidates.add(new Candidate(w, d, names));
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> c.distance));

        boolean outside = false;
        if (!ds.getDataSourceBounds().isEmpty()) {
            Area area = ds.getDataSourceArea();
            outside = area == null || !area.contains(new Rectangle2D.Double(west, south, east - west, north - south));
        }
        return new Address(candidates, outside);
    }

    private boolean hasRoads(DataSet ds) {
        Boolean known = datasetHasRoads.get(ds);
        if (known == null) {
            known = ds.getWays().stream().anyMatch(StreetNames::isRoad);
            datasetHasRoads.put(ds, known);
        }
        return known;
    }

    /** Coordinate runs of the address: one point, one way, or each member of a relation. */
    private static List<List<LatLon>> geometry(OsmPrimitive p) {
        List<List<LatLon>> pieces = new ArrayList<>();
        if (p instanceof Node) {
            addPiece(pieces, Collections.singletonList((Node) p));
        } else if (p instanceof Way) {
            addPiece(pieces, ((Way) p).getNodes());
        } else if (p instanceof Relation) {
            for (RelationMember m : ((Relation) p).getMembers()) {
                if (m.isNode()) {
                    addPiece(pieces, Collections.singletonList(m.getNode()));
                } else if (m.isWay()) {
                    addPiece(pieces, m.getWay().getNodes());
                }
            }
        }
        return pieces;
    }

    private static void addPiece(List<List<LatLon>> pieces, List<Node> nodes) {
        List<LatLon> out = coords(nodes);
        if (!out.isEmpty()) {
            pieces.add(out);
        }
    }

    private static List<LatLon> coords(Way w) {
        return coords(w.getNodes());
    }

    private static List<LatLon> coords(List<Node> nodes) {
        List<LatLon> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            if (n != null && !n.isIncomplete() && n.isLatLonKnown()) {
                out.add(n.getCoor());
            }
        }
        return out;
    }

    /** Points and polylines in local metres, or null when there is nothing to draw. */
    private Geometry toGeometry(List<List<LatLon>> pieces, LocalProjection proj) {
        List<Geometry> parts = new ArrayList<>();
        for (List<LatLon> piece : pieces) {
            if (piece.isEmpty()) {
                continue;
            }
            Coordinate[] cs = new Coordinate[piece.size()];
            for (int i = 0; i < cs.length; i++) {
                cs[i] = proj.toXY(piece.get(i));
            }
            parts.add(cs.length == 1 ? factory.createPoint(cs[0]) : factory.createLineString(cs));
        }
        return parts.isEmpty() ? null : factory.buildGeometry(parts);
    }

    /** The road's own names plus those of any street relation it belongs to, cached per run. */
    private List<RoadName> namesOf(Way w) {
        List<RoadName> cached = roadNames.get(w);
        if (cached != null) {
            return cached;
        }
        Map<String, RoadName> byName = new LinkedHashMap<>();
        for (Map.Entry<String, String> tag : w.getKeys().entrySet()) {
            if (StreetNames.isNameKey(tag.getKey())) {
                addNames(byName, tag.getValue());
            }
        }
        for (OsmPrimitive ref : w.getReferrers()) {
            if (ref instanceof Relation && ref.isUsable() && StreetNames.isStreetRelation(ref)) {
                addNames(byName, ref.get("name"));
            }
        }
        List<RoadName> names = new ArrayList<>(byName.values());
        roadNames.put(w, names);
        return names;
    }

    private static void addNames(Map<String, RoadName> byName, String value) {
        for (String v : StreetNames.splitValues(value)) {
            if (!byName.containsKey(v)) {
                byName.put(v, new RoadName(v, StreetNames.canonical(v)));
            }
        }
    }

    /** True when p belongs to a street relation whose name is this street. */
    private static boolean inAssociatedStreet(OsmPrimitive p, String canon) {
        for (OsmPrimitive ref : p.getReferrers()) {
            if (ref instanceof Relation && ref.isUsable() && StreetNames.isStreetRelation(ref)) {
                for (String name : StreetNames.splitValues(ref.get("name"))) {
                    if (StreetNames.canonical(name).equals(canon)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final class RoadName {
        /** NFC, trimmed, single-spaced: compared as is for an exact match and shown in messages. */
        final String display;
        final String canonical;
        final String folded;
        final String expanded;

        RoadName(String display, String canonical) {
            this.display = display;
            this.canonical = canonical;
            this.folded = StreetNames.fold(canonical);
            this.expanded = StreetNames.expand(folded);
        }
    }

    private static final class Candidate {
        final Way road;
        final double distance;
        final List<RoadName> names;

        Candidate(Way road, double distance, List<RoadName> names) {
            this.road = road;
            this.distance = distance;
            this.names = names;
        }
    }

    private static final class Address {
        final List<Candidate> candidates;
        final boolean circleOutsideDownload;

        Address(List<Candidate> candidates, boolean circleOutsideDownload) {
            this.candidates = candidates;
            this.circleOutsideDownload = circleOutsideDownload;
        }
    }
}
