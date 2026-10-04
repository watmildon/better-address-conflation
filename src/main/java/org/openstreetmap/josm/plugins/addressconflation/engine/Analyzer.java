// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final DataSet hints;
    private final CellSource cellSource;
    private final ConflationSettings settings;

    private LocalProjection proj;
    private List<Cell> cells;
    private STRtree cellIndex;
    private STRtree buildingIndex;
    private STRtree hintIndex;
    private int duplicatesRemoved;
    /** For hinted assignments: the OSM outbuilding the hint overrode, or null when OSM had nothing. */
    private final Map<Assignment, BuildingCandidate> hintedReason = new HashMap<>();

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
        this(source, target, null, cellSource, settings);
    }

    /**
     * @param hints optional dataset of building footprints used for position only (MapWithAI,
     *              county or Microsoft footprints); never edited, may be null
     */
    public Analyzer(DataSet source, DataSet target, DataSet hints, CellSource cellSource, ConflationSettings settings) {
        this.source = source;
        this.target = target;
        this.hints = hints == target ? null : hints;
        this.cellSource = cellSource;
        this.settings = settings;
    }

    public static AnalysisResult analyze(DataSet source, DataSet target, CellSource cellSource, ConflationSettings settings) {
        return new Analyzer(source, target, cellSource, settings).run();
    }

    public static AnalysisResult analyze(DataSet source, DataSet target, DataSet hints, CellSource cellSource, ConflationSettings settings) {
        return new Analyzer(source, target, hints, cellSource, settings).run();
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
        indexBuildingNodes(extent);
        indexHints(extent);
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
            if (source == target && !ConflationSettings.isPlainAddressNode(n)) {
                // A shop, a building node, a named place: a feature with an address, not a
                // loose address to fold into a building. It stays, and counts as existing.
                continue;
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

    /** Radius of the stand-in footprint of a building mapped as a node, in metres. */
    static final double BUILDING_NODE_RADIUS = 6.0;

    /**
     * Unaddressed building=* nodes: some mappers mark a building's presence with a node.
     * They only become targets where a cell has no outline (see {@link #bucket}).
     */
    private void indexBuildingNodes(Envelope extent) {
        Envelope search = new Envelope(extent);
        search.expandBy(settings.matchDistanceMeters + 200);
        for (Node n : target.getNodes()) {
            if (!n.isUsable() || n.getCoor() == null || !n.hasKey("building") || "no".equals(n.get("building"))
                    || n.hasKey(HOUSENUMBER) || n.getReferrers().stream().anyMatch(Way.class::isInstance)) {
                continue;
            }
            Coordinate c = proj.toXY(n.getCoor());
            if (!search.contains(c)) {
                continue;
            }
            Point p = OsmGeometry.factory().createPoint(c);
            for (Cell cell : (List<Cell>) cellIndex.query(new Envelope(c))) {
                if (cell.getPrepared().intersects(p)) {
                    cell.getBuildingNodes().add(n);
                }
            }
        }
    }

    /** Building nodes an address in this cell could go to. */
    private List<Node> buildingNodesFor(AddressGroup g, Cell cell) {
        if (!cell.isSynthetic()) {
            return cell.getBuildingNodes();
        }
        Coordinate at = proj.toXY(g.getPosition());
        List<Node> out = new ArrayList<>();
        for (Node n : cell.getBuildingNodes()) {
            if (proj.toXY(n.getCoor()).distance(at) <= settings.matchDistanceMeters) {
                out.add(n);
            }
        }
        return out;
    }

    private BuildingCandidate nodeCandidate(Node n) {
        Geometry circle = OsmGeometry.factory().createPoint(proj.toXY(n.getCoor())).buffer(BUILDING_NODE_RADIUS, 4);
        return new BuildingCandidate(n, circle, settings.weightFor(n));
    }

    private void indexBuildings(Envelope extent) {
        buildingIndex = new STRtree();
        indexFootprints(target, extent, false, buildingIndex);
        buildingIndex.build();
        for (Cell cell : cells) {
            Collections.sort(cell.getBuildings());
        }
    }

    private void indexHints(Envelope extent) {
        hintIndex = new STRtree();
        if (hints != null) {
            indexFootprints(hints, extent, true, hintIndex);
        }
        hintIndex.build();
        for (Cell cell : cells) {
            Collections.sort(cell.getHintBuildings());
        }
    }

    @SuppressWarnings("unchecked")
    private void indexFootprints(DataSet ds, Envelope extent, boolean asHint, STRtree index) {
        Envelope search = new Envelope(extent);
        search.expandBy(settings.matchDistanceMeters + 200);
        List<OsmPrimitive> prims = new ArrayList<>();
        prims.addAll(ds.getWays());
        prims.addAll(ds.getRelations());
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
            BuildingCandidate b = new BuildingCandidate(prim, g, settings.weightFor(prim), asHint);
            index.insert(g.getEnvelopeInternal(), b);
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
                    (asHint ? cell.getHintBuildings() : cell.getBuildings()).add(cb);
                }
            }
        }
    }

    /** Buildings whose footprint contains the point, whether or not they made the cell's list. */
    @SuppressWarnings("unchecked")
    private List<BuildingCandidate> buildingsContaining(Point p, STRtree index) {
        List<BuildingCandidate> out = new ArrayList<>();
        for (BuildingCandidate b : (List<BuildingCandidate>) index.query(p.getEnvelopeInternal())) {
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
        Map<BuildingCandidate, List<Assignment>> hinted = new LinkedHashMap<>();
        Map<Cell, List<AddressGroup>> onNodes = new LinkedHashMap<>();
        hintedReason.clear();

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
                List<String> reasons = existingReasons(g, bestKind, matches);
                if (bestKind == ExistingKind.IDENTICAL && matches.size() == 1
                        && AddressNormalizer.conflictingKeys(g.getTags(), matches.get(0).getTags()).isEmpty()) {
                    BuildingCandidate on = candidateFor(matches.get(0).getPrimitive(), cell);
                    String misplaced = matches.get(0).isBuilding() ? otherBuildingIsPrimary(g, cell, on) : null;
                    if (misplaced == null) {
                        proposals.add(alreadyMapped(g, cell, matches.get(0), on));
                        continue;
                    }
                    reasons.add(misplaced);
                }
                proposals.add(new Proposal(Bucket.EXISTING_ADDRESS, Collections.singletonList(g), null, cell.getBuildings(),
                        confidenceFor(bestKind), reasons, bestKind, matches, cell));
                continue;
            }
            Assignment a = rank(g, cell);
            if (a.ranked.isEmpty() && !buildingNodesFor(g, cell).isEmpty()) {
                // No outline, but a mapper marked the building with a node: that beats a hint.
                onNodes.computeIfAbsent(cell, x -> new ArrayList<>()).add(g);
                continue;
            }
            if (hints != null) {
                Assignment h = rankHints(g, cell);
                if (!h.ranked.isEmpty() && preferHint(a, h)) {
                    hinted.computeIfAbsent(h.ranked.get(0).getBuilding(), x -> new ArrayList<>()).add(h);
                    hintedReason.put(h, a.ranked.isEmpty() ? null : a.ranked.get(0).getBuilding());
                    continue;
                }
            }
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
                if (kind == ExistingKind.IDENTICAL && AddressNormalizer.conflictingKeys(g.getTags(), ex.get(0).getTags()).isEmpty()) {
                    proposals.add(alreadyMapped(g, cell, ex.get(0), primary));
                    continue;
                }
                proposals.add(new Proposal(Bucket.EXISTING_ADDRESS, Collections.singletonList(g), primary, a.ranked,
                        confidenceFor(kind), existingReasons(g, kind, ex), kind, ex, cell));
                continue;
            }
            byBuilding.computeIfAbsent(primary, x -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<BuildingCandidate, List<Assignment>> e : byBuilding.entrySet()) {
            proposals.add(buildingProposal(e.getKey(), e.getValue()));
        }
        for (Map.Entry<BuildingCandidate, List<Assignment>> e : hinted.entrySet()) {
            proposals.add(hintProposal(e.getKey(), e.getValue()));
        }
        for (Map.Entry<Cell, List<AddressGroup>> e : onNodes.entrySet()) {
            proposals.addAll(buildingNodeProposals(e.getKey(), e.getValue()));
        }
        return proposals;
    }

    /**
     * Addresses in a cell whose only buildings are mapped as nodes. One node: the address
     * goes on it (several addresses become nodes beside it). Several nodes: the mapper picks;
     * the closest node is often the neighbour's house, so there is no default.
     */
    private List<Proposal> buildingNodeProposals(Cell cell, List<AddressGroup> groups) {
        List<Proposal> out = new ArrayList<>();
        java.util.Set<Node> all = new java.util.LinkedHashSet<>();
        for (AddressGroup g : groups) {
            all.addAll(buildingNodesFor(g, cell));
        }
        String where = cell.isSynthetic() ? "near the address" : "in parcel " + cell.getId();
        if (all.size() == 1) {
            Node n = all.iterator().next();
            BuildingCandidate b = nodeCandidate(n);
            List<CellBuilding> cands = Collections.singletonList(new CellBuilding(b, cell, b.getArea()));
            String what = "building=" + n.get("building");
            if (groups.size() == 1) {
                out.add(new Proposal(Bucket.CLEAN, groups, b, cands, 0.85,
                        Collections.singletonList("Only building " + where + " is mapped as a node (" + what + "); the address goes on it"),
                        null, null, cell));
            } else {
                out.add(new Proposal(Bucket.MULTI_ADDRESS_BUILDING, groups, b, cands, 0.7,
                        Arrays.asList("Only building " + where + " is mapped as a node (" + what + ")",
                                groups.size() + " addresses: each stays its own node beside it"),
                        null, null, cell));
            }
            return out;
        }
        for (AddressGroup g : groups) {
            List<CellBuilding> cands = new ArrayList<>();
            for (Node n : buildingNodesFor(g, cell)) {
                BuildingCandidate b = nodeCandidate(n);
                cands.add(new CellBuilding(b, cell, b.getArea()));
            }
            out.add(new Proposal(Bucket.AMBIGUOUS_BUILDING, Collections.singletonList(g), null, cands, 0.3,
                    Arrays.asList(cands.size() + " buildings " + where + " are mapped as nodes and none as an outline",
                            "Select the right building node on the map, then Apply"),
                    null, null, cell));
        }
        return out;
    }

    /**
     * A hint footprint beats the OSM candidates when OSM has nothing, or when OSM only
     * offers an outbuilding and the hint shows something much bigger where the house
     * should be (a mapped shed, an unmapped house).
     */
    private boolean preferHint(Assignment osm, Assignment hint) {
        if (osm.ranked.isEmpty()) {
            return true;
        }
        BuildingCandidate osmPrimary = osm.ranked.get(0).getBuilding();
        double osmScore = osm.scores.get(0);
        double hintScore = hint.scores.get(0);
        return osmPrimary.getTagFactor() < 1.0 && hintScore > osmScore * settings.hintOverOutbuildingRatio;
    }

    private Proposal hintProposal(BuildingCandidate footprint, List<Assignment> assignments) {
        List<AddressGroup> groups = new ArrayList<>();
        Set<Cell> cellsHit = new HashSet<>();
        double worstRatio = 0;
        for (Assignment a : assignments) {
            groups.add(a.group);
            cellsHit.add(a.cell);
            worstRatio = Math.max(worstRatio, a.ratio);
        }
        Assignment first = assignments.get(0);
        List<String> reasons = new ArrayList<>();
        BuildingCandidate osmShadowed = hintedReason.get(first);
        if (osmShadowed != null) {
            reasons.add(String.format(Locale.ROOT, "OSM only has building=%s (%.0f m\u00b2) here; hint footprint is %.0f m\u00b2",
                    osmShadowed.getBuildingValue(), osmShadowed.getArea(), footprint.getArea()));
        } else {
            reasons.add(String.format(Locale.ROOT, "No OSM building; hint footprint of %.0f m\u00b2 in %s", footprint.getArea(),
                    first.cell.isSynthetic() ? "cell" : "parcel " + first.cell.getId()));
        }
        if (first.ranked.size() > 1) {
            reasons.add(String.format(Locale.ROOT, "Largest of %d hint footprints; runner-up scores %.0f%%", first.ranked.size(), worstRatio * 100));
        }
        if (groups.size() > 1) {
            reasons.add(groups.size() + " addresses on this footprint; each stays a node");
        }
        double confidence = Math.min(0.8, 1.0 - 0.5 * worstRatio);
        Bucket bucket = Bucket.CLEAN;
        if (worstRatio >= settings.ambiguityRatio
                || (groups.size() > 1 && worstRatio >= settings.multiAddressAmbiguityRatio)) {
            // Same rule as for OSM buildings: several addresses and several footprints at one
            // point cannot be sorted out automatically.
            bucket = Bucket.AMBIGUOUS_BUILDING;
            if (groups.size() > 1) {
                reasons.add(groups.size() + " addresses share this cell with " + first.ranked.size() + " hint footprints");
            }
            confidence = Math.min(confidence, 0.4);
        } else if (groups.size() > 1) {
            // A footprint is only a hint: several addresses on one may be several buildings
            // the footprint merged, so a mapper looks before they become nodes.
            bucket = Bucket.REVIEW;
            confidence = Math.min(confidence, 0.6);
        }
        if (cellsHit.size() > 1) {
            reasons.add("Footprint covers " + cellsHit.size() + " addressed cells");
        }
        double far = farthest(footprint, groups);
        if (far > settings.farMatchMeters && bucket != Bucket.AMBIGUOUS_BUILDING) {
            reasons.add(String.format(Locale.ROOT, "Footprint is %.0f m from the address point", far));
            bucket = Bucket.AMBIGUOUS_BUILDING;
            confidence = Math.min(confidence, 0.4);
        }
        if (bucket == Bucket.CLEAN && splitByParcel(footprint, assignments, reasons)) {
            bucket = Bucket.REVIEW;
            confidence = Math.min(confidence, 0.6);
        }
        return new Proposal(bucket, groups, footprint, first.ranked, clamp(confidence), reasons, null, null, first.cell);
    }

    /**
     * Parcel mode: true, with a reason added, when more of the building lies outside an
     * address's parcel than {@link ConflationSettings#splitTolerance} allows.
     */
    private boolean splitByParcel(BuildingCandidate building, List<Assignment> assignments, List<String> reasons) {
        double minShare = 1;
        Cell worst = null;
        for (Assignment a : assignments) {
            if (a.cell.isSynthetic()) {
                // Voronoi edges do not follow lot lines; a building across one says nothing.
                return false;
            }
            double share = shareIn(building, a.cell);
            if (share < minShare) {
                minShare = share;
                worst = a.cell;
            }
        }
        if (worst == null || minShare >= 1 - settings.splitTolerance) {
            return false;
        }
        reasons.add(String.format(Locale.ROOT, "A parcel line splits the building: only %.0f%% of it is in parcel %s",
                minShare * 100, worst.getId()));
        return true;
    }

    /** Fraction of the building's footprint inside the cell, 0..1. */
    private static double shareIn(BuildingCandidate building, Cell cell) {
        Geometry g = building.getGeometry();
        if (g.getArea() <= 0 || cell.getPrepared().covers(g)) {
            return 1;
        }
        try {
            return cell.getGeometry().intersection(g).getArea() / g.getArea();
        } catch (TopologyException ex) {
            // Broken parcel geometry: do not send the address to review over it.
            Logging.trace(ex);
            return 1;
        }
    }

    /** Rank the OSM buildings in a cell for one address group. */
    private Assignment rank(AddressGroup g, Cell cell) {
        return rank(g, cell, cell.getBuildings(), buildingIndex);
    }

    /** Rank the hint footprints in a cell for one address group. */
    private Assignment rankHints(AddressGroup g, Cell cell) {
        return rank(g, cell, cell.getHintBuildings(), hintIndex);
    }

    private Assignment rank(AddressGroup g, Cell cell, List<CellBuilding> cellCandidates, STRtree index) {
        Point p = OsmGeometry.factory().createPoint(proj.toXY(g.getPosition()));
        List<CellBuilding> ranked = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<BuildingCandidate> containing = buildingsContaining(p, index);
        List<CellBuilding> candidates = new ArrayList<>(cellCandidates);
        for (BuildingCandidate b : containing) {
            if (candidates.stream().noneMatch(cb -> cb.getBuilding() == b)) {
                // The point is inside this building even though little of it is in the cell.
                CellBuilding extra = new CellBuilding(b, cell, b.getArea());
                extra.setScore(b.getArea() * b.getTagFactor());
                candidates.add(extra);
            }
        }
        boolean explicitPresent = candidates.stream().map(CellBuilding::getBuilding)
                .anyMatch(b -> !b.isHint() && b.getTagFactor() >= 1.0 && !ConflationSettings.isGeneric(b.getBuildingValue()));
        for (CellBuilding cb : candidates) {
            BuildingCandidate b = cb.getBuilding();
            boolean contains = containing.contains(b);
            if (cell.isSynthetic() && !contains && b.getGeometry().distance(p) > settings.matchDistanceMeters) {
                continue;
            }
            double score = cb.getScore();
            if (explicitPresent && !contains && !b.isHint() && ConflationSettings.isGeneric(b.getBuildingValue()) && b.getTagFactor() >= 1.0) {
                // Being inside the building is direct evidence about this building and
                // outweighs the "yes is probably the barn" prior.
                score *= settings.genericBesideExplicitFactor;
            }
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
        for (Assignment a : assignments) {
            groups.add(a.group);
            cellsHit.add(a.cell);
            worstRatio = Math.max(worstRatio, a.ratio);
            anySnapped |= a.group.isSnappedToCell();
            maxDistance = Math.max(maxDistance, a.group.getDistanceToCell());
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
            bucket = Bucket.REVIEW;
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
        double far = farthest(building, groups);
        if (far > settings.farMatchMeters && (bucket == Bucket.CLEAN || bucket == Bucket.MULTI_ADDRESS_BUILDING)) {
            reasons.add(String.format(Locale.ROOT, "Building is %.0f m from the address point", far));
            bucket = Bucket.AMBIGUOUS_BUILDING;
            confidence = Math.min(confidence, 0.4);
        }
        if ((bucket == Bucket.CLEAN || bucket == Bucket.MULTI_ADDRESS_BUILDING) && splitByParcel(building, assignments, reasons)) {
            bucket = Bucket.REVIEW;
            confidence = Math.min(confidence, 0.6);
        }
        return new Proposal(bucket, groups, building, first.ranked, clamp(confidence), reasons, null, null, cell);
    }

    /** Largest distance from any of the groups' points to the building outline, metres. */
    private double farthest(BuildingCandidate building, List<AddressGroup> groups) {
        double far = 0;
        for (AddressGroup g : groups) {
            far = Math.max(far, building.getGeometry().distance(OsmGeometry.factory().createPoint(proj.toXY(g.getPosition()))));
        }
        return far;
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

    /**
     * OSM already has this address and nothing it says disagrees with the source: a clean
     * row that adds whatever the source knows and OSM lacks (postcode, state...) to the
     * existing feature.
     *
     * @param building the existing feature as a building candidate, or null when it is a node, POI...
     */
    private Proposal alreadyMapped(AddressGroup g, Cell cell, ExistingAddress e, BuildingCandidate building) {
        List<String> reasons = new ArrayList<>();
        reasons.add(describeExisting(ExistingKind.IDENTICAL, Collections.singletonList(e)));
        List<String> added = new ArrayList<>();
        for (String key : g.getTags().keySet()) {
            if (settings.shouldCopyKey(key) && !e.getTags().containsKey(key)) {
                added.add(key);
            }
        }
        reasons.add(added.isEmpty() ? "Nothing to add; applying drops the source node" : "Adds " + String.join(", ", added));
        return new Proposal(Bucket.CLEAN, Collections.singletonList(g), building, cell.getBuildings(), confidenceFor(ExistingKind.IDENTICAL),
                reasons, ExistingKind.IDENTICAL, Collections.singletonList(e), cell);
    }

    /**
     * When OSM has the address on a building, that building has to be the one the address
     * would go to anyway, or the row is not clean: an address on the garage or the gas-station
     * canopy is the mistake this plugin exists to catch. Returns why not, or null when it is.
     */
    private String otherBuildingIsPrimary(AddressGroup g, Cell cell, BuildingCandidate on) {
        Assignment best = rank(g, cell);
        if (!best.ranked.isEmpty() && best.ranked.get(0).getBuilding() == on) {
            return null;
        }
        if (best.ranked.isEmpty() || on == null) {
            return "The building carrying it is not a candidate for this address";
        }
        BuildingCandidate primary = best.ranked.get(0).getBuilding();
        return String.format(Locale.ROOT, "But %s %d (%.0f m²) looks like the main building here", nameOf(primary),
                primary.getPrimitive().getUniqueId(), primary.getArea());
    }

    /** The cell's building candidate for a primitive, or null when it is not one. */
    private static BuildingCandidate candidateFor(OsmPrimitive prim, Cell cell) {
        for (CellBuilding cb : cell.getBuildings()) {
            if (cb.getBuilding().getPrimitive() == prim) {
                return cb.getBuilding();
            }
        }
        return null;
    }

    /** Why an existing address needs a look, naming the keys that disagree. */
    private static List<String> existingReasons(AddressGroup g, ExistingKind kind, List<ExistingAddress> matches) {
        List<String> reasons = new ArrayList<>();
        reasons.add(describeExisting(kind, matches));
        if (matches.size() > 1) {
            reasons.add(matches.size() + " features in this cell already carry a matching address");
        }
        if (kind == ExistingKind.IDENTICAL) {
            Map<String, String> theirs = matches.get(0).getTags();
            for (String key : AddressNormalizer.conflictingKeys(g.getTags(), theirs)) {
                reasons.add(key + " differs: OSM '" + theirs.get(key) + "', source '" + g.getTags().get(key) + "'");
            }
        }
        return reasons;
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
