// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * An address OSM already has is clean when nothing disagrees: the source may add keys
 * (postcode, state) but a differing value sends the row to review.
 */
class ExistingAddressTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private final DataSet target = new DataSet();
    private final DataSet source = new DataSet();
    private final DataSet parcels = new DataSet();
    private final ConflationSettings settings = new ConflationSettings();

    /** Housenumber and street only, so each test says exactly which other keys either side has. */
    private static String[] addr(String number, String street) {
        return new String[] {"addr:housenumber=" + number, "addr:street=" + street};
    }

    private AnalysisResult run() {
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        return Analyzer.analyze(source, target, null, new ParcelCellSource(parcels, "p"), settings);
    }

    private static Proposal only(AnalysisResult r, Bucket b) {
        List<Proposal> ps = r.getProposals().stream().filter(p -> p.getBucket() == b).collect(Collectors.toList());
        assertEquals(1, ps.size(), "expected one " + b + ", got " + r.getProposals() + " " + r.getProposals().stream().map(Proposal::getReasons).collect(Collectors.toList()));
        return ps.get(0);
    }

    @Test
    void additionsAreCleanAndOnlyFillWhatOsmLacks() {
        Way house = Fixtures.rect(target, 0, 10, 12, 10,
                Fixtures.concat(addr("10", "West Market Street"), "building=house", "addr:city=Crawfordsville"));
        Node addr = Fixtures.node(source, 0, -20, Fixtures.concat(addr("10", "West Market Street"),
                "addr:city=CRAWFORDSVILLE", "addr:postcode=47933", "addr:state=IN"));
        AnalysisResult r = run();
        Proposal p = only(r, Bucket.CLEAN);
        assertSame(house, p.getTarget().getPrimitive());
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("addr:postcode") && x.contains("addr:state")), p.getReasons().toString());

        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), settings);
        assertTrue(a.getTargetCommand().executeCommand());
        assertEquals("47933", house.get("addr:postcode"));
        assertEquals("IN", house.get("addr:state"));
        assertEquals("Crawfordsville", house.get("addr:city"), "OSM's own value is kept; case alone is no conflict");
        assertTrue(a.getSourceCommand().executeCommand());
        assertTrue(addr.isDeleted());
    }

    @Test
    void aDifferingValueNeedsReview() {
        Way house = Fixtures.rect(target, 0, 10, 12, 10,
                Fixtures.concat(addr("10", "West Market Street"), "building=house", "addr:postcode=47933"));
        Fixtures.node(source, 0, -20, Fixtures.concat(addr("10", "West Market Street"), "addr:postcode=47934"));
        AnalysisResult r = run();
        Proposal p = only(r, Bucket.EXISTING_ADDRESS);
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("addr:postcode differs")), p.getReasons().toString());
        // Applying the review row keeps OSM as it is and only drops the source node.
        Applied a = ProposalApplier.build(p, target, source, r.getProjection(), settings);
        assertNull(a.getTargetCommand());
        assertEquals("47933", house.get("addr:postcode"));
    }

    @Test
    void addressOnAnOutbuildingIsNotClean() {
        Fixtures.rect(target, 0, 10, 14, 12, "building=house");
        Fixtures.rect(target, 12, -20, 6, 6, Fixtures.concat(addr("10", "West Market Street"), "building=garage"));
        Fixtures.node(source, 0, -20, addr("10", "West Market Street"));
        Proposal p = only(run(), Bucket.EXISTING_ADDRESS);
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("main building")), p.getReasons().toString());
    }

    @Test
    void addressOnAPoiNodeIsEnrichedInPlace() {
        Fixtures.rect(target, 0, 10, 14, 12, "building=retail");
        Node shop = Fixtures.node(target, 0, 10, Fixtures.concat(addr("10", "West Market Street"), "shop=convenience"));
        Fixtures.node(source, 0, -20, Fixtures.concat(addr("10", "West Market Street"), "addr:postcode=47933"));
        AnalysisResult r = run();
        Proposal p = only(r, Bucket.CLEAN);
        assertTrue(ProposalApplier.build(p, target, source, r.getProjection(), settings).getTargetCommand().executeCommand());
        assertEquals("47933", shop.get("addr:postcode"));
        assertTrue(target.getWays().stream().noneMatch(w -> w.hasKey("addr:housenumber")), "the building is left alone");
    }

    @Test
    void sameAddressTwiceInOsmNeedsReview() {
        Fixtures.rect(target, 0, 10, 14, 12, Fixtures.concat(addr("10", "West Market Street"), "building=house"));
        Fixtures.node(target, 0, -15, addr("10", "West Market Street"));
        Fixtures.node(source, 0, -20, addr("10", "West Market Street"));
        Proposal p = only(run(), Bucket.EXISTING_ADDRESS);
        assertTrue(p.getReasons().stream().anyMatch(x -> x.contains("2 features")), p.getReasons().toString());
    }

    @Test
    void unitOnOneSideIsStillAConflict() {
        Fixtures.rect(target, 0, 10, 14, 12, Fixtures.concat(addr("10", "West Market Street"), "building=apartments"));
        Fixtures.node(source, 0, -20, Fixtures.concat(addr("10", "West Market Street"), "addr:unit=2"));
        only(run(), Bucket.EXISTING_ADDRESS);
    }
}
