// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.index.strtree.STRtree;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.cells.CellSource;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.BuildingCandidate;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;
import org.openstreetmap.josm.plugins.addressconflation.model.CellBuilding;
import org.openstreetmap.josm.plugins.addressconflation.model.ExistingAddress;
import org.openstreetmap.josm.plugins.addressconflation.model.ExistingKind;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;
import org.openstreetmap.josm.tools.Logging;

/**
 * The engine. Pure function of two datasets, a cell source and settings; no
 * Swing, no layers, no commands.
 *
 * <pre>
 * addresses -> assign to cell -> dedupe -> rank buildings per (address, cell) -> bucket
 * </pre>
 */
public final class Analyzer {
    private static final String HOUSENUMBER = "addr:housenumber";

    private final DataSet source;
    private final DataSet target;
    private final CellSource cellSource;
    private final ConflationSettings settings;

    private LocalProjection proj;
    private List<Cell> cells;
    private STRtree cellIndex;
    private STRtree buildingIndex;
    private int duplicatesRemoved;

    /** A tentative "address group goes to this building in this cell". */
    private static final class Assignment {
        final AddressGroup group;
        final Cell cell;
        final List<CellBuilding> ranked;
        final List<Double> scores;
        final double ratio;

        Assignment(AddressGroup group, Cell cell, List<CellBuilding> ranked, List<Double> scores) {
            this.group = group;
            this.cell = cell;
            this.ranked = ranked;
            this.scores = scores;
            this.ratio = scores.size() > 1 && scores.get(0) > 0 ? scores.get(1) / scores.get(0) : 0;
        }
    }

    public Analyzer(DataSet source, DataSet target, CellSource cellSource, ConflationSettings settings) {
        this.source = source;
        this.target = target;
        this.cellSource = cellSource;
        this.settings = settings;
    }

    public static AnalysisResult analyze(DataSet source, DataSet target, CellSource cellSource, ConflationSettings settings) {
        return new Analyzer(source, target, cellSource, settings).run();
    }

    public AnalysisResult run() {
        List<Node> sourceNodes = collectSourceNodes();
        if (sourceNodes.isEmpty()) {
            return new AnalysisResult(new ArrayList<>(), new ArrayList<>(), new LocalProjection(0, 0), 0, 0, cellSource.isSynthetic());
        }
        proj = centerProjection(sourceNodes);
        List<Coordinate> sites = new ArrayList<>(sourceNodes.size());
        Envelope extent = new Envelope();
        for (Node n : sourceNodes) {
            Coordinate c = proj.toXY(n.getCoor());
            sites.add(c);
            extent.expandToInclude(c);
        }

        cells = cellSource.buildCells(proj, sites, extent, settings);
        cellIndex = new STRtree();
        for (Cell c : cells) {
            cellIndex.insert(c.getGeometry().getEnvelopeInternal(), c);
        }
        cellIndex.build();

        List<AddressGroup> groups = groupAddresses(sourceNodes);
        indexBuildings(extent);
        indexExisting(new HashSet<>(sourceNodes));

        List<Proposal> proposals = bucket(groups);
        proposals.sort(Comparator.comparing(Proposal::getBucket).thenComparingDouble(p -> p.getBucket() == Bucket.CLEAN ? -p.getConfidence() : p.getConfidence()));
        AnalysisResult result = new AnalysisResult(proposals, cells, proj, sourceNodes.size(), duplicatesRemoved, cellSource.isSynthetic());
        result.setShift(ShiftEstimator.estimate(proposals, proj));
        return result;
    }

    // ---- source addresses -------------------------------------------------

    private List<Node> collectSourceNodes() {
        List<Node> nodes = new ArrayList<>();
        for (Node n : source.getNodes()) {
            if (!n.isUsable() || n.getCoor() == null || !n.hasKey(HOUSENUMBER)) {
                continue;
            }
            if (source == target && n.getReferrers().stream().anyMatch(Way.class::isInstance)) {
                continue; // an address vertex of a building outline is not a source point
            }
            nodes.add(n);
        }
        nodes.sort(Comparator.comparingLong(Node::getUniqueId));
        return nodes;
    }

    private static LocalProjection centerProjection(List<Node> nodes) {
        double lat = 0;
        double lon = 0;
        for (Node n : nodes) {
            lat += n.lat();
            lon += n.lon();
        }
        return new LocalProjection(lat / nodes.size(), lon / nodes.size());
    }

    /** Assign each node to a cell, then merge absolute duplicates that share a cell. */
    private List<AddressGroup> groupAddresses(List<Node> nodes) {
        Map<String, AddressGroup> byKey = new LinkedHashMap<>();
        Map<String, Set<String>> cellsByExact = new HashMap<>();
        Map<String, List<AddressGroup>> groupsByExact = new HashMap<>();
        for (Node n : nodes) {
            Map<String, String> tags = n.getKeys();
            String exact = AddressNormalizer.exactKey(tags);
            CellHit hit = findCell(proj.toXY(n.getCoor()));
            String cellId = hit == null ? "<none>" : hit.cell.getId();
            String k = exact + '\u0002' + cellId;
            AddressGroup g = byKey.get(k);
            if (g == null) {
                g = new AddressGroup(n, tags, AddressNormalizer.key(tags));
                if (hit != null) {
                    g.setCell(hit.cell, hit.snapped, hit.distance);
                    hit.cell.getAddresses().add(g);
                }
                byKey.put(k, g);
                groupsByExact.computeIfAbsent(exact, x -> new ArrayList<>()).add(g);
            } else {
                g.getDuplicates().add(n);
                duplicatesRemoved++;
            }
            cellsByExact.computeIfAbsent(exact, x -> new HashSet<>()).add(cellId);
        }
        for (Map.Entry<String, Set<String>> e : cellsByExact.entrySet()) {
            if (e.getValue().size() > 1) {
                for (AddressGroup g : groupsByExact.get(e.getKey())) {
                    g.setCrossCellDuplicate(true);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static final class CellHit {
        final Cell cell;
        final boolean snapped;
        final double distance;

        CellHit(Cell cell, boolean snapped, double distance) {
            this.cell = cell;
            this.snapped = snapped;
            this.distance = distance;
        }
    }

    @SuppressWarnings("unchecked")
    private CellHit findCell(Coordinate c) {
        Point p = OsmGeometry.factory().createPoint(c);
        Cell best = null;
        for (Cell cell : (List<Cell>) cellIndex.query(new Envelope(c))) {
            if (cell.getPrepared().intersects(p)) {
                // Prefer the smallest containing cell (condo footprints inside a master parcel).
                if (best == null || cell.getGeometry().getArea() < best.getGeometry().getArea()) {
                    best = cell;
                }
            }
        }
        if (best != null) {
            return new CellHit(best, false, 0);
        }
        Envelope e = new Envelope(c);
        e.expandBy(settings.matchDistanceMeters);
        double bestDist = Double.MAX_VALUE;
        for (Cell cell : (List<Cell>) cellIndex.query(e)) {
            double d = cell.getGeometry().distance(p);
            if (d < bestDist) {
                bestDist = d;
                best = cell;
            }
        }
        if (best != null && bestDist <= settings.matchDistanceMeters) {
            return new CellHit(best, true, bestDist);
        }
        return null;
    }

    // ---- buildings ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void indexBuildings(Envelope extent) {
        Envelope search = new Envelope(extent);
        search.expandBy(settings.matchDistanceMeters + 200);
        buildingIndex = new STRtree();
        List<OsmPrimitive> prims = new ArrayList<>();
        prims.addAll(target.getWays());
        prims.addAll(target.getRelations());
        for (OsmPrimitive prim : prims) {
            if (!prim.isUsable() || !prim.hasKey("building") || "no".equals(prim.get("building"))) {
                continue;
            }
            if (prim instanceof Relation && !((Relation) prim).isMultipolygon()) {
                continue;
            }
            Geometry g = OsmGeometry.toPolygon(prim, proj);
            if (g == null || g.isEmpty() || g.getArea() < 1 || !g.getEnvelopeInternal().intersects(search)) {
                continue;
            }
            BuildingCandidate b = new BuildingCandidate(prim, g, settings.weightFor(prim));
            buildingIndex.insert(g.getEnvelopeInternal(), b);
            for (Cell cell : (List<Cell>) cellIndex.query(g.getEnvelopeInternal())) {
                if (!cell.getPrepared().intersects(g)) {
                    continue;
                }
                double areaIn;
                try {
                    areaIn = cell.getPrepared().covers(g) ? b.getArea() : cell.getGeometry().intersection(g).getArea();
                } catch (TopologyException ex) {
                    Logging.trace(ex);
                    areaIn = cell.getPrepared().containsProperly(g.getInteriorPoint()) ? b.getArea() : 0;
                }
                if (areaIn <= 0) {
                    continue;
                }
                double share = areaIn / b.getArea();
                if (share >= settings.minShareInCell || areaIn >= 20) {
                    CellBuilding cb = new CellBuilding(b, cell, areaIn);
                    cb.setScore(areaIn * b.getTagFactor());
                    cell.getBuildings().add(cb);
                }
            }
        }
        buildingIndex.build();
        for (Cell cell : cells) {
            Collections.sort(cell.getBuildings());
        }
    }

    /** Buildings whose footprint contains the point, whether or not they made the cell's list. */
    @SuppressWarnings("unchecked")
    private List<BuildingCandidate> buildingsContaining(Point p) {
        List<BuildingCandidate> out = new ArrayList<>();
        for (BuildingCandidate b : (List<BuildingCandidate>) buildingIndex.query(p.getEnvelopeInternal())) {
            if (b.getGeometry().contains(p)) {
                out.add(b);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void indexExisting(Set<Node> sourceNodes) {
        for (OsmPrimitive prim : target.allPrimitives()) {
            if (!prim.isUsable() || !prim.hasKey(HOUSENUMBER) || sourceNodes.contains(prim)) {
                continue;
            }
            Coordinate c = representative(prim);
            if (c == null) {
                continue;
            }
            Point p = OsmGeometry.factory().createPoint(c);
            for (Cell cell : (List<Cell>) cellIndex.query(new Envelope(c))) {
                if (cell.getPrepared().intersects(p)) {
                    cell.getExisting().add(new ExistingAddress(prim));
                }
            }
        }
    }

    private Coordinate representative(OsmPrimitive prim) {
        if (prim instanceof Node) {
            LatLon ll = ((Node) prim).getCoor();
            return ll == null ? null : proj.toXY(ll);
        }
        Geometry g = OsmGeometry.toPolygon(prim, proj);
        if (g != null && !g.isEmpty()) {
            return g.getInteriorPoint().getCoordinate();
        }
        if (prim instanceof Way && ((Way) prim).getNodesCount() > 0) {
            LatLon ll = ((Way) prim).getNode(0).getCoor();
            return ll == null ? null : proj.toXY(ll);
        }
        return null;
    }

    // ---- bucketing ------------------------------------------------------------

    private List<Proposal> bucket(List<AddressGroup> groups) {
        List<Proposal> proposals = new ArrayList<>();
        Map<BuildingCandidate, List<Assignment>> byBuilding = new LinkedHashMap<>();

        for (AddressGroup g : groups) {
            if (g.getCell() == null) {
                proposals.add(simple(Bucket.OUTSIDE_CELLS, g, 0.0,
                        String.format(Locale.ROOT, "No parcel within %.0f m", settings.matchDistanceMeters)));
                continue;
            }
            if (g.isCrossCellDuplicate()) {
                proposals.add(simple(Bucket.DUPLICATE, g, 0.2, "Identical address appears in more than one cell"));
                continue;
            }
            Cell cell = g.getCell();
            List<ExistingAddress> matches = new ArrayList<>();
            ExistingKind bestKind = null;
            for (ExistingAddress e : cell.getExisting()) {
                ExistingKind k = AddressNormalizer.compare(g.getTags(), e.getTags());
                if (k != null) {
                    matches.add(e);
                    bestKind = better(bestKind, k);
                }
            }
            if (bestKind != null) {
                proposals.add(new Proposal(Bucket.EXISTING_ADDRESS, Collections.singletonList(g), null, cell.getBuildings(),
                        confidenceFor(bestKind), Collections.singletonList(describeExisting(bestKind, matches)), bestKind, matches, cell));
                continue;
            }
            Assignment a = rank(g, cell);
            if (a.ranked.isEmpty()) {
                proposals.add(simple(Bucket.NO_BUILDING, g, 0.9, cell.isSynthetic()
                        ? String.format(Locale.ROOT, "No building within %.0f m", settings.matchDistanceMeters)
                        : "No building in parcel " + cell.getId()));
                continue;
            }
            BuildingCandidate primary = a.ranked.get(0).getBuilding();
            if (primary.hasAddress()) {
                ExistingKind k = AddressNormalizer.compare(g.getTags(), primary.getAddrTags());
                ExistingKind kind = k == null ? ExistingKind.OTHER_ADDRESS_ON_BUILDING : k;
                List<ExistingAddress> ex = Collections.singletonList(new ExistingAddress(primary.getPrimitive()));
                proposals.add(new Proposal(Bucket.EXISTING_ADDRESS, Collections.singletonList(g), primary, a.ranked,
                        confidenceFor(kind), Collections.singletonList(describeExisting(kind, ex)), kind, ex, cell));
                continue;
            }
            byBuilding.computeIfAbsent(primary, x -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<BuildingCandidate, List<Assignment>> e : byBuilding.entrySet()) {
            proposals.add(buildingProposal(e.getKey(), e.getValue()));
        }
        return proposals;
    }

    /** Rank the buildings in a cell for one address group. */
    private Assignment rank(AddressGroup g, Cell cell) {
        Point p = OsmGeometry.factory().createPoint(proj.toXY(g.getPosition()));
        List<CellBuilding> ranked = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<BuildingCandidate> containing = buildingsContaining(p);
        List<CellBuilding> candidates = new ArrayList<>(cell.getBuildings());
        for (BuildingCandidate b : containing) {
            if (candidates.stream().noneMatch(cb -> cb.getBuilding() == b)) {
                // The point is inside this building even though little of it is in the cell.
                CellBuilding extra = new CellBuilding(b, cell, b.getArea());
                extra.setScore(b.getArea() * b.getTagFactor());
                candidates.add(extra);
            }
        }
        for (CellBuilding cb : candidates) {
            BuildingCandidate b = cb.getBuilding();
            boolean contains = containing.contains(b);
            if (cell.isSynthetic() && !contains && b.getGeometry().distance(p) > settings.matchDistanceMeters) {
                continue;
            }
            double score = cb.getScore();
            if (contains && b.getTagFactor() >= 1.0) {
                // Being inside a house or a unit outline is strong evidence; being under a
                // gas-station canopy or inside a garage that happens to sit at the parcel
                // centroid is not.
                score *= settings.containsFactor;
            }
            if (b.hasAddress() && AddressNormalizer.compare(g.getTags(), b.getAddrTags()) == null) {
                score *= settings.otherAddressFactor;
            }
            int i = 0;
            while (i < scores.size() && scores.get(i) >= score) {
                i++;
            }
            ranked.add(i, cb);
            scores.add(i, score);
        }
        return new Assignment(g, cell, ranked, scores);
    }

    private Proposal buildingProposal(BuildingCandidate building, List<Assignment> assignments) {
        List<AddressGroup> groups = new ArrayList<>();
        Set<Cell> cellsHit = new HashSet<>();
        double worstRatio = 0;
        boolean anySnapped = false;
        double maxDistance = 0;
        double minShare = 1;
        for (Assignment a : assignments) {
            groups.add(a.group);
            cellsHit.add(a.cell);
            worstRatio = Math.max(worstRatio, a.ratio);
            anySnapped |= a.group.isSnappedToCell();
            maxDistance = Math.max(maxDistance, a.group.getDistanceToCell());
            minShare = Math.min(minShare, a.ranked.get(0).getShare());
        }
        Assignment first = assignments.get(0);
        List<String> reasons = new ArrayList<>();
        Cell cell = first.cell;
        int n = first.ranked.size();
        if (n > 1) {
            reasons.add(String.format(Locale.ROOT, "Largest of %d buildings in %s (%.0f m² vs %.0f m² %s)", n,
                    cell.isSynthetic() ? "cell" : "parcel " + cell.getId(), building.getArea(),
                    first.ranked.get(1).getBuilding().getArea(), nameOf(first.ranked.get(1).getBuilding())));
        } else {
            reasons.add(cell.isSynthetic() ? "Only building near the address" : "Only building in parcel " + cell.getId());
        }
        if (worstRatio > 0) {
            reasons.add(String.format(Locale.ROOT, "Runner-up scores %.0f%% of primary", worstRatio * 100));
        }
        if (assignments.stream().allMatch(a -> building.getGeometry().contains(OsmGeometry.factory().createPoint(proj.toXY(a.group.getPosition()))))) {
            reasons.add(groups.size() == 1 ? "Address point is inside this building" : "All address points are inside this building");
        }
        if (anySnapped) {
            reasons.add(String.format(Locale.ROOT, "Address point snapped to parcel %.0f m away", maxDistance));
        }
        double confidence = 1.0 - 0.5 * worstRatio - (anySnapped ? 0.1 : 0);
        Bucket bucket;
        if (cellsHit.size() > 1) {
            bucket = Bucket.BUILDING_SPANS_CELLS;
            reasons.add("Building covers " + cellsHit.size() + " addressed cells");
            confidence = Math.min(confidence, 0.6);
        } else if (worstRatio >= settings.ambiguityRatio) {
            bucket = Bucket.AMBIGUOUS_BUILDING;
            if (groups.size() > 1) {
                reasons.add(groups.size() + " addresses share this cell with " + n + " candidate buildings");
            }
            confidence = Math.min(confidence, 0.5);
        } else if (groups.size() > 1 && worstRatio >= settings.multiAddressAmbiguityRatio) {
            // Several addresses and several real buildings at one point (a strip mall, a
            // condo complex with points at the parcel centroid): nobody can say which address
            // belongs to which building, so do not stack them all on the biggest one quietly.
            bucket = Bucket.AMBIGUOUS_BUILDING;
            reasons.add(groups.size() + " addresses share this cell with " + n + " candidate buildings");
            confidence = Math.min(confidence, 0.4);
        } else if (groups.size() > 1) {
            bucket = Bucket.MULTI_ADDRESS_BUILDING;
            reasons.add(groups.size() + " addresses on this building; each stays a node");
            confidence = Math.min(confidence, 0.85);
        } else {
            bucket = Bucket.CLEAN;
        }
        if (minShare < settings.spanningShare && bucket == Bucket.CLEAN) {
            reasons.add(String.format(Locale.ROOT, "Only %.0f%% of the building is in this cell", minShare * 100));
            confidence -= 0.2;
        }
        return new Proposal(bucket, groups, building, first.ranked, clamp(confidence), reasons, null, null, cell);
    }

    private Proposal simple(Bucket bucket, AddressGroup g, double confidence, String reason) {
        return new Proposal(bucket, Collections.singletonList(g), null, g.getCell() == null ? null : g.getCell().getBuildings(),
                confidence, Collections.singletonList(reason), null, null, g.getCell());
    }

    private static ExistingKind better(ExistingKind a, ExistingKind b) {
        if (a == null) {
            return b;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    private static double confidenceFor(ExistingKind k) {
        switch (k) {
        case IDENTICAL:
            return 0.95;
        case STREET_VARIANT:
            return 0.7;
        case UNIT_DIFF:
            return 0.5;
        case STREET_DIFF:
            return 0.3;
        default:
            return 0.2;
        }
    }

    private static String describeExisting(ExistingKind kind, Collection<ExistingAddress> matches) {
        ExistingAddress e = matches.iterator().next();
        String what = nameOf(e.getPrimitive()) + " " + e.getPrimitive().getUniqueId();
        switch (kind) {
        case IDENTICAL:
            return "Already mapped on " + what;
        case STREET_VARIANT:
            return "Already mapped on " + what + " with street spelled '" + e.getTags().get("addr:street") + "'";
        case UNIT_DIFF:
            return "Same housenumber on " + what + ", unit differs ('" + e.getTags().getOrDefault("addr:unit", "") + "')";
        case STREET_DIFF:
            return "Same housenumber on " + what + " but street is '" + e.getTags().get("addr:street") + "'";
        default:
            return "Building " + what + " already has address " + e.getTags().getOrDefault(HOUSENUMBER, "?") + " "
                    + e.getTags().getOrDefault("addr:street", "");
        }
    }

    private static String nameOf(BuildingCandidate b) {
        return "building=" + b.getBuildingValue();
    }

    private static String nameOf(OsmPrimitive p) {
        if (p.hasKey("building")) {
            return "building=" + p.get("building");
        }
        return p.getType().getAPIName();
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
