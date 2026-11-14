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
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * A house that sits in its own addressed lot but pokes over the back fence into a big
 * neighbouring parcel (a subdivision's common-area tract) is no candidate for that parcel's
 * address. Seen in Pima County: a stale NAD point in a 4 ha tract went to a house 336 m away.
 */
class ParcelSliverTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private final DataSet target = new DataSet();
    private final DataSet source = new DataSet();
    private final DataSet parcels = new DataSet();
    private final ConflationSettings settings = new ConflationSettings();

    private AnalysisResult run() {
        return Analyzer.analyze(source, target, new ParcelCellSource(parcels, "p"), settings);
    }

    private static Proposal proposalFor(AnalysisResult r, String housenumber) {
        List<Proposal> ps = r.getProposals().stream()
                .filter(p -> p.getAddresses().stream().map(AddressGroup::getTags).anyMatch(t -> housenumber.equals(t.get("addr:housenumber"))))
                .collect(Collectors.toList());
        assertEquals(1, ps.size(), "expected one proposal for " + housenumber + ", got " + r.getProposals());
        return ps.get(0);
    }

    /** Two lots (y -20..20) backing onto a 200 x 80 m tract (y 20..100). Each house has 2 m of its 16 m depth in the tract. */
    private Way[] lotsAgainstATract() {
        Fixtures.rect(parcels, 0, 0, 30, 40, "oa:pid=A");
        Fixtures.rect(parcels, 30, 0, 30, 40, "oa:pid=B");
        Fixtures.rect(parcels, 15, 60, 200, 80, "oa:pid=TRACT");
        Way a = Fixtures.rect(target, 0, 14, 14, 16, "building=house");
        Way b = Fixtures.rect(target, 30, 14, 14, 16, "building=house");
        Fixtures.node(source, 0, 10, "addr:housenumber=10269", "addr:street=East Boulder Hop Trail");
        Fixtures.node(source, 30, 10, "addr:housenumber=10273", "addr:street=East Boulder Hop Trail");
        return new Way[] {a, b};
    }

    @Test
    void anAddressInATractDoesNotGoToAHouseThatOnlyGrazesIt() {
        Way[] houses = lotsAgainstATract();
        Fixtures.node(source, 0, 90, "addr:housenumber=10221", "addr:street=East Drexel Road");
        AnalysisResult r = run();

        Proposal tract = proposalFor(r, "10221");
        assertEquals(Bucket.NO_BUILDING, tract.getBucket(), tract.getReasons().toString());
        assertTrue(tract.getReasons().stream().anyMatch(x -> x.contains("TRACT") || x.contains("parcel")), tract.getReasons().toString());

        Proposal a = proposalFor(r, "10269");
        assertEquals(Bucket.CLEAN, a.getBucket(), a.getReasons().toString());
        assertSame(houses[0], a.getTarget().getPrimitive());
        assertEquals(1, a.getAddresses().size(), "the tract's address does not ride along");
        assertSame(houses[1], proposalFor(r, "10273").getTarget().getPrimitive());
    }

    @Test
    void anAddressPointInsideTheGrazingPartStillFindsTheHouse() {
        Way[] houses = lotsAgainstATract();
        // Inside house A, but on the tract's side of the fence line.
        Fixtures.node(source, 0, 21, "addr:housenumber=10221", "addr:street=East Drexel Road");
        Proposal p = proposalFor(run(), "10221");
        assertSame(houses[0], p.getTarget().getPrimitive(), p.getReasons().toString());
    }

    @Test
    void aBuildingAcrossALotLineStillServesBothLots() {
        // A duplex mapped as one outline, half in each lot: neither lot is its home.
        Fixtures.rect(parcels, 0, 0, 30, 40, "oa:pid=A");
        Fixtures.rect(parcels, 30, 0, 30, 40, "oa:pid=B");
        Way duplex = Fixtures.rect(target, 15, 0, 20, 12, "building=semidetached_house");
        Fixtures.node(source, 0, -15, "addr:housenumber=12", "addr:street=Lot Line Road");
        Fixtures.node(source, 30, -15, "addr:housenumber=14", "addr:street=Lot Line Road");
        Proposal p = proposalFor(run(), "12");
        assertSame(duplex, p.getTarget().getPrimitive());
        assertEquals(2, p.getAddresses().size(), p.getReasons().toString());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("covers 2 addressed cells")), p.getReasons().toString());
    }

    @Test
    void aFarMatchSaysHowFarEvenWhenAlreadyInReview() {
        // One outline across two long lots; one lot's address point is 150 m down the lot.
        Fixtures.rect(parcels, 0, 0, 30, 400, "oa:pid=A");
        Fixtures.rect(parcels, 30, 0, 30, 400, "oa:pid=B");
        Fixtures.rect(target, 15, 190, 20, 12, "building=semidetached_house");
        Fixtures.node(source, 0, 30, "addr:housenumber=12", "addr:street=Long Lot Road");
        Fixtures.node(source, 30, 180, "addr:housenumber=14", "addr:street=Long Lot Road");
        Proposal p = proposalFor(run(), "12");
        assertEquals(Bucket.REVIEW, p.getBucket(), p.getReasons().toString());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.matches("Building is \\d+ m from the address point")), p.getReasons().toString());
        assertTrue(p.getConfidence() <= 0.4, "confidence " + p.getConfidence());
    }
}
