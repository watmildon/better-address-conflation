// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.RoadClippedVoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.ExistingKind;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/** The situations from the plan's fixture list, one at a time. */
class AnalyzerEdgeCasesTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static Proposal only(AnalysisResult r, Bucket b) {
        List<Proposal> ps = r.getProposals().stream().filter(p -> p.getBucket() == b).collect(Collectors.toList());
        assertEquals(1, ps.size(), "expected one " + b + " proposal, got " + r.getProposals());
        return ps.get(0);
    }

    private static AnalysisResult parcelsRun(DataSet source, DataSet target, DataSet parcels, ConflationSettings s) {
        return Analyzer.analyze(source, target, new ParcelCellSource(parcels, "p"), s);
    }

    /**
     * The last address of a row has no neighbour on its outer side, so its raw Voronoi
     * cell runs far past the row. With a wide match distance a big unaddressed warehouse
     * out there outscored the house next to the point.
     */
    @Test
    void edgeCellDoesNotReachPastTypicalLot() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Way lastHouse = null;
        for (int i = 0; i < 5; i++) {
            lastHouse = Fixtures.rect(target, i * 20, 12, 10, 10, "building=house");
            Fixtures.node(source, i * 20, 0, Fixtures.addr(Integer.toString(100 + i * 2), "Main Street"));
        }
        Way warehouse = Fixtures.rect(target, 150, 12, 40, 30, "building=yes");
        ConflationSettings wide = new ConflationSettings();
        wide.matchDistanceMeters = 120;

        wide.voronoiReachFactor = 0;
        assertSame(warehouse, targetOf(Analyzer.analyze(source, target, new VoronoiCellSource(), wide), "108"),
                "without trimming the fixture should show the problem");

        wide.voronoiReachFactor = new ConflationSettings().voronoiReachFactor;
        assertSame(lastHouse, targetOf(Analyzer.analyze(source, target, new VoronoiCellSource(), wide), "108"));
    }

    private static Object targetOf(AnalysisResult r, String housenumber) {
        return r.getProposals().stream()
                .filter(p -> p.getAddresses().stream().anyMatch(g -> housenumber.equals(g.getTags().get("addr:housenumber"))))
                .map(p -> p.getTarget() == null ? null : p.getTarget().getPrimitive())
                .findFirst().orElse(null);
    }

    @Test
    void houseBeatsGarageAtParcelCentroid() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Way house = Fixtures.rect(target, 0, 15, 12, 10, "building=house");
        Fixtures.rect(target, 0, -18, 7, 6, "building=garage");
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.CLEAN);
        assertSame(house, p.getTarget().getPrimitive());
        assertTrue(p.getConfidence() > 0.9, "" + p.getConfidence());
    }

    @Test
    void houseBeatsGarageEvenWhenCentroidIsInsideGarage() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Way house = Fixtures.rect(target, 0, 18, 12, 10, "building=house");
        Fixtures.rect(target, 0, 0, 7, 6, "building=garage");
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.CLEAN);
        assertSame(house, p.getTarget().getPrimitive());
    }

    @Test
    void untaggedGarageIsAmbiguousOnlyWhenBig() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Way house = Fixtures.rect(target, 0, 15, 12, 10, "building=yes");
        Fixtures.rect(target, 0, -18, 6, 6, "building=yes"); // 36 m2 vs 120 m2: ratio 0.3
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.CLEAN);
        assertSame(house, p.getTarget().getPrimitive());

        DataSet target2 = new DataSet();
        Fixtures.rect(target2, 0, 15, 12, 10, "building=yes");
        Fixtures.rect(target2, 0, -18, 10, 10, "building=yes"); // 100 m2 vs 120 m2: ratio 0.83
        DataSet source2 = new DataSet();
        Fixtures.node(source2, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        only(parcelsRun(source2, target2, parcels, new ConflationSettings()), Bucket.AMBIGUOUS_BUILDING);
    }

    @Test
    void duplexAsTwoBuildingsIsAmbiguous() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 40, "oa:pid=A");
        Fixtures.rect(target, -8, 0, 10, 10, "building=house");
        Fixtures.rect(target, 8, 0, 10, 10, "building=house");
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        Fixtures.node(source, 0, 0, Fixtures.addr("14", "West Olive Avenue"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.AMBIGUOUS_BUILDING);
        assertEquals(2, p.getAddresses().size());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("2 addresses share")), p.getReasons().toString());
    }

    @Test
    void multipolygonBuildingIsATarget() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 80, 80, "oa:pid=A");
        Way outer = Fixtures.rect(target, 0, 0, 40, 40);
        Way inner = Fixtures.rect(target, 0, 0, 16, 16);
        Relation mp = Fixtures.multipolygon(target, outer, Collections.singletonList(inner), "building=apartments");
        Fixtures.node(source, 15, 0, Fixtures.addr("100", "West Olive Avenue")); // in the ring, not the courtyard
        AnalysisResult r = parcelsRun(source, target, parcels, new ConflationSettings());
        Proposal p = only(r, Bucket.CLEAN);
        assertSame(mp, p.getTarget().getPrimitive());
        assertEquals(1600 - 256, Math.round(p.getTarget().getArea()), "ring area without the courtyard");
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("inside")), p.getReasons().toString());
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), new ConflationSettings());
        assertTrue(a.getTargetCommand().executeCommand());
        assertEquals("100", mp.get("addr:housenumber"));
    }

    @Test
    void townhouseRowSpansParcels() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        for (int i = 0; i < 3; i++) {
            Fixtures.rect(parcels, i * 10, 0, 10, 40, "oa:pid=P" + i);
            Fixtures.node(source, i * 10, 0, Fixtures.addr(String.valueOf(10 + 2 * i), "West Olive Avenue"));
        }
        Way row = Fixtures.rect(target, 10, 5, 28, 12, "building=terrace");
        AnalysisResult r = parcelsRun(source, target, parcels, new ConflationSettings());
        Proposal p = only(r, Bucket.REVIEW);
        assertSame(row, p.getTarget().getPrimitive());
        assertEquals(3, p.getAddresses().size());
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), new ConflationSettings());
        assertTrue(a.getTargetCommand().executeCommand());
        assertEquals(3, target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).count());
    }

    @Test
    void exactDuplicatesCollapse() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 40, "oa:pid=A");
        Way house = Fixtures.rect(target, 0, 0, 12, 10, "building=house");
        Fixtures.node(source, 1, 1, Fixtures.addr("12", "West Olive Avenue"));
        Fixtures.node(source, -1, 2, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = parcelsRun(source, target, parcels, s);
        assertEquals(1, r.getDuplicatesRemoved());
        Proposal p = only(r, Bucket.CLEAN);
        assertEquals(1, p.getAddresses().size());
        assertEquals(2, p.getSourceNodes().size());
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), s);
        assertTrue(a.getTargetCommand().executeCommand());
        assertTrue(a.getSourceCommand().executeCommand());
        assertEquals("12", house.get("addr:housenumber"));
        assertTrue(source.getNodes().stream().allMatch(n -> n.isDeleted()));
    }

    @Test
    void identicalAddressInTwoParcelsIsFlagged() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, -20, 0, 40, 40, "oa:pid=A");
        Fixtures.rect(parcels, 20, 0, 40, 40, "oa:pid=B");
        Fixtures.rect(target, -20, 0, 12, 10, "building=house");
        Fixtures.rect(target, 20, 0, 12, 10, "building=house");
        Fixtures.node(source, -20, 0, Fixtures.addr("12", "West Olive Avenue"));
        Fixtures.node(source, 20, 0, Fixtures.addr("12", "West Olive Avenue"));
        AnalysisResult r = parcelsRun(source, target, parcels, new ConflationSettings());
        assertEquals(2, r.countByBucket().getOrDefault(Bucket.DUPLICATE, 0));
    }

    @Test
    void pointInTheStreetSnapsToParcelOrFallsOutside() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 40, "oa:pid=A");
        Fixtures.rect(target, 0, 0, 12, 10, "building=house");
        Fixtures.node(source, 0, -26, Fixtures.addr("12", "West Olive Avenue")); // 6 m outside the parcel
        ConflationSettings s = new ConflationSettings();
        Proposal p = only(parcelsRun(source, target, parcels, s), Bucket.CLEAN);
        assertTrue(p.getAddresses().get(0).isSnappedToCell());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("snapped")), p.getReasons().toString());
        assertTrue(p.getConfidence() < 1.0);

        s.matchDistanceMeters = 4;
        DataSet source2 = new DataSet();
        Fixtures.node(source2, 0, -26, Fixtures.addr("12", "West Olive Avenue"));
        only(parcelsRun(source2, target, parcels, s), Bucket.OUTSIDE_CELLS);
    }

    @Test
    void existingAddressKinds() {
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 40, "oa:pid=A");

        DataSet target = new DataSet();
        Fixtures.rect(target, 0, 0, 12, 10, Fixtures.concat(Fixtures.addr("500", "West Olive Avenue"), "building=apartments"));
        DataSet source = new DataSet();
        Fixtures.node(source, 0, 0, Fixtures.concat(Fixtures.addr("500", "West Olive Avenue"), "addr:unit=2"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.EXISTING_ADDRESS);
        assertEquals(ExistingKind.UNIT_DIFF, p.getExistingKind());

        DataSet target2 = new DataSet();
        Fixtures.rect(target2, 0, 0, 12, 10, Fixtures.concat(Fixtures.addr("10", "West Olive Avenue"), "building=house"));
        DataSet source2 = new DataSet();
        Fixtures.node(source2, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        p = only(parcelsRun(source2, target2, parcels, new ConflationSettings()), Bucket.EXISTING_ADDRESS);
        assertEquals(ExistingKind.OTHER_ADDRESS_ON_BUILDING, p.getExistingKind());

        // a POI node already carrying the address, no building tags involved
        DataSet target3 = new DataSet();
        Fixtures.rect(target3, 0, 0, 12, 10, "building=retail");
        Fixtures.node(target3, 1, 1, Fixtures.concat(Fixtures.addr("12", "West Olive Avenue"), "shop=convenience"));
        DataSet source3 = new DataSet();
        Fixtures.node(source3, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        p = only(parcelsRun(source3, target3, parcels, new ConflationSettings()), Bucket.EXISTING_ADDRESS);
        assertEquals(ExistingKind.IDENTICAL, p.getExistingKind());
    }

    @Test
    void addressedNeighbourLosesToUnaddressedHouse() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 60, 40, "oa:pid=A");
        Fixtures.rect(target, -15, 0, 14, 10, Fixtures.concat(Fixtures.addr("10", "West Olive Avenue"), "building=house"));
        Way b = Fixtures.rect(target, 15, 0, 12, 10, "building=house");
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.CLEAN);
        assertSame(b, p.getTarget().getPrimitive());
    }

    @Test
    void voronoiRespectsMatchDistance() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        Way far = Fixtures.rect(target, 100, 0, 12, 10, "building=house");
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        only(Analyzer.analyze(source, target, new VoronoiCellSource(), s), Bucket.NO_BUILDING);
        s.matchDistanceMeters = 150;
        Proposal p = only(Analyzer.analyze(source, target, new VoronoiCellSource(), s), Bucket.CLEAN);
        assertSame(far, p.getTarget().getPrimitive());
    }

    @Test
    void roadClippingStopsCellsCrossingTheStreet() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        // a house 25 m north of the address point, but a residential street runs between them
        Way acrossTheStreet = Fixtures.rect(target, 0, 25, 12, 10, "building=house");
        Fixtures.polygon(target, new String[] {"highway=residential", "name=West Olive Avenue"}, Fixtures.at(-200, 12), Fixtures.at(200, 12));
        // the polygon helper closes the way; make it an open road again
        Way road = target.getWays().stream().filter(w -> w.hasKey("highway")).findFirst().orElseThrow();
        road.setNodes(road.getNodes().subList(0, 2));
        Fixtures.node(source, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        ConflationSettings s = new ConflationSettings();
        Proposal plain = only(Analyzer.analyze(source, target, new VoronoiCellSource(), s), Bucket.CLEAN);
        assertSame(acrossTheStreet, plain.getTarget().getPrimitive());
        DataSet source2 = new DataSet();
        Fixtures.node(source2, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        only(Analyzer.analyze(source2, target, new RoadClippedVoronoiCellSource(target), s), Bucket.NO_BUILDING);
        // a driveway (service road) between them must not cut the cell
        road.put("highway", "service");
        DataSet source3 = new DataSet();
        Fixtures.node(source3, 0, 0, Fixtures.addr("12", "West Olive Avenue"));
        only(Analyzer.analyze(source3, target, new RoadClippedVoronoiCellSource(target), s), Bucket.CLEAN);
    }

    @Test
    void poiTaggedCanopyCanCarryTheAddress() {
        DataSet target = new DataSet();
        DataSet source = new DataSet();
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 60, 60, "oa:pid=A");
        Way canopy = Fixtures.rect(target, 0, 0, 20, 20, "building=roof", "amenity=fuel");
        Fixtures.rect(target, 0, 20, 8, 6, "building=yes");
        Fixtures.node(source, 0, 0, Fixtures.addr("477", "Troy Schenectady Road"));
        Proposal p = only(parcelsRun(source, target, parcels, new ConflationSettings()), Bucket.CLEAN);
        assertSame(canopy, p.getTarget().getPrimitive());

        DataSet target2 = new DataSet();
        Fixtures.rect(target2, 0, 0, 20, 20, "building=roof");
        Way store = Fixtures.rect(target2, 0, 20, 8, 6, "building=yes");
        DataSet source2 = new DataSet();
        Fixtures.node(source2, 0, 0, Fixtures.addr("477", "Troy Schenectady Road"));
        p = only(parcelsRun(source2, target2, parcels, new ConflationSettings()), Bucket.CLEAN);
        assertSame(store, p.getTarget().getPrimitive());
        assertNotNull(p.getReasons());
    }
}
