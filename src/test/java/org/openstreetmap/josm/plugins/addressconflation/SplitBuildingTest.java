// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Buckets say what the mapper has to do: a building a parcel line cuts through, or several
 * addresses on one hint footprint, need a look whether the building is from OSM or a hint.
 */
class SplitBuildingTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private final DataSet target = new DataSet();
    private final DataSet source = new DataSet();
    private final DataSet hints = new DataSet();
    private final DataSet parcels = new DataSet();

    /** Parcels A (x -10..10) and B (x 10..30); the lot line is x = 10. */
    private void twoParcels() {
        Fixtures.rect(parcels, 0, 0, 20, 40, "oa:pid=A");
        Fixtures.rect(parcels, 20, 0, 20, 40, "oa:pid=B");
    }

    private AnalysisResult run(ConflationSettings s) {
        return Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), s);
    }

    private static Proposal only(AnalysisResult r, Bucket b) {
        List<Proposal> ps = r.getProposals().stream().filter(p -> p.getBucket() == b).collect(Collectors.toList());
        assertEquals(1, ps.size(), "expected one " + b + ", got " + r.getProposals());
        return ps.get(0);
    }

    @Test
    void hintFootprintSplitByAParcelLineNeedsReview() {
        twoParcels();
        Way footprint = Fixtures.rect(hints, 10, 10, 12, 10, "building=yes"); // half in each parcel
        Fixtures.node(source, 0, -15, Fixtures.addr("10", "West Market Street"));
        Proposal p = only(run(new ConflationSettings()), Bucket.REVIEW);
        assertSame(footprint, p.getTarget().getPrimitive());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("parcel line splits")), p.getReasons().toString());
    }

    @Test
    void thinSliceAcrossTheLotLineStaysClean() {
        twoParcels();
        Fixtures.rect(hints, 5.6, 10, 12, 10, "building=yes"); // 13 % pokes into B
        Fixtures.node(source, 0, -15, Fixtures.addr("10", "West Market Street"));
        Proposal p = only(run(new ConflationSettings()), Bucket.CLEAN);
        assertTrue(p.getTarget().isHint());
    }

    @Test
    void splitToleranceIsTunable() {
        twoParcels();
        Fixtures.rect(hints, 5.6, 10, 12, 10, "building=yes");
        Fixtures.node(source, 0, -15, Fixtures.addr("10", "West Market Street"));
        ConflationSettings strict = new ConflationSettings();
        strict.splitTolerance = 0.05;
        only(run(strict), Bucket.REVIEW);
    }

    @Test
    void osmBuildingSplitByAParcelLineNeedsReviewAndStillGetsTagged() {
        twoParcels();
        Way house = Fixtures.rect(target, 10, 10, 12, 10, "building=house");
        Fixtures.node(source, 0, -15, Fixtures.addr("10", "West Market Street"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = run(s);
        Proposal p = only(r, Bucket.REVIEW);
        assertSame(house, p.getTarget().getPrimitive());
        assertTrue(ProposalApplier.build(p, target, source, r.getProjection(), s).getTargetCommand().executeCommand());
        assertEquals("10", house.get("addr:housenumber"));
    }

    @Test
    void severalAddressesOnOneHintFootprintNeedReview() {
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Fixtures.rect(hints, 0, 10, 20, 12, "building=yes");
        Fixtures.node(source, -5, -20, Fixtures.addr("10", "West Market Street"));
        Fixtures.node(source, 5, -20, Fixtures.addr("12", "West Market Street"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = run(s);
        Proposal p = only(r, Bucket.REVIEW);
        assertEquals(2, p.getAddresses().size());
        assertTrue(ProposalApplier.build(p, target, source, r.getProjection(), s).getTargetCommand().executeCommand());
        assertEquals(2, target.getNodes().stream().filter(n -> n.hasKey("addr:housenumber")).count(), "each address stays a node");
    }

    @Test
    void severalAddressesOnOneOsmBuildingStayBulkApplicable() {
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Fixtures.rect(target, 0, 10, 20, 12, "building=apartments");
        Fixtures.node(source, -5, -20, Fixtures.addr("10", "West Market Street"));
        Fixtures.node(source, 5, -20, Fixtures.addr("12", "West Market Street"));
        assertEquals(2, only(run(new ConflationSettings()), Bucket.MULTI_ADDRESS_BUILDING).getAddresses().size());
    }
}
