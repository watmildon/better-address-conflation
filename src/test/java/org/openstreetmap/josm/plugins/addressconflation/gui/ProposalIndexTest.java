// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.Fixtures;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/** Selecting address nodes, hint footprints or parcels on the map finds their proposals. */
class ProposalIndexTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private final DataSet target = new DataSet();
    private final DataSet source = new DataSet();
    private final DataSet hints = new DataSet();
    private final DataSet parcels = new DataSet();
    private Way house;
    private Way parcelB;
    private Way parcelCOuter;
    private Way footprintA;
    private Way footprintB;
    private Node addrA;
    private Node addrB;
    private Node addrC;
    private Proposal pA;
    private Proposal pB;
    private Proposal pC;
    private ProposalIndex index;

    @BeforeEach
    void analyze() {
        // A: an OSM house plus a hint footprint beside it. B: only a hint footprint.
        // C: a multipolygon parcel with nothing in it.
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        parcelB = Fixtures.rect(parcels, 50, 0, 40, 60, "oa:pid=B");
        parcelCOuter = Fixtures.rect(parcels, 100, 0, 40, 60);
        Fixtures.multipolygon(parcels, parcelCOuter, Collections.emptyList(), "oa:pid=C");
        house = Fixtures.rect(target, 0, 10, 12, 10, "building=house");
        footprintA = Fixtures.rect(hints, 0, -15, 10, 8, "building=yes");
        footprintB = Fixtures.rect(hints, 50, 10, 12, 10, "building=yes");
        addrA = Fixtures.node(source, 0, -25, Fixtures.addr("10", "West Olive Avenue"));
        addrB = Fixtures.node(source, 50, -25, Fixtures.addr("20", "West Olive Avenue"));
        addrC = Fixtures.node(source, 100, -25, Fixtures.addr("30", "West Olive Avenue"));

        AnalysisResult r = Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), new ConflationSettings());
        pA = proposalFor(r, addrA);
        pB = proposalFor(r, addrB);
        pC = proposalFor(r, addrC);
        assertEquals(Bucket.CLEAN, pA.getBucket());
        assertEquals(Bucket.CLEAN, pB.getBucket());
        assertEquals(Bucket.NO_BUILDING, pC.getBucket());
        index = new ProposalIndex(r.getProposals(), source, target, hints, parcels);
    }

    private static Proposal proposalFor(AnalysisResult r, Node n) {
        return r.getProposals().stream().filter(p -> p.getSourceNodes().contains(n)).findFirst()
                .orElseThrow(() -> new AssertionError("no proposal for " + n + " in " + r.getProposals()));
    }

    @Test
    void addressNodesFindTheirProposals() {
        assertEquals(Set.of(pA), index.find(source, List.of(addrA)));
        assertEquals(Set.of(pA, pC), index.find(source, Arrays.asList(addrA, addrC)));
    }

    @Test
    void hintFootprintFindsTheProposalPlacedOnIt() {
        assertEquals(Set.of(pB), index.find(hints, List.of(footprintB)));
    }

    @Test
    void hintFootprintTheAddressDoesNotUseFindsNothing() {
        // Applying the clean row would ignore this footprint, so selecting it must not offer that row.
        assertTrue(index.find(hints, List.of(footprintA)).isEmpty());
    }

    @Test
    void parcelFindsTheAddressesInIt() {
        assertEquals(Set.of(pB), index.find(parcels, List.of(parcelB)));
    }

    @Test
    void multipolygonParcelIsFoundThroughItsWay() {
        assertEquals(Set.of(pC), index.find(parcels, List.of(parcelCOuter)));
    }

    @Test
    void osmBuildingFindsTheAddressGoingOnIt() {
        assertEquals(Set.of(pA), index.find(target, List.of(house)));
    }

    /** One parcel holding the given OSM buildings and one address; the index over the result. */
    private static ProposalIndex oneParcel(DataSet osm, DataSet addresses, Proposal[] out) {
        DataSet lots = new DataSet();
        Fixtures.rect(lots, 0, 0, 40, 60, "oa:pid=A");
        AnalysisResult r = Analyzer.analyze(addresses, osm, null, new ParcelCellSource(lots, "p"), new ConflationSettings());
        assertEquals(1, r.getProposals().size(), r.getProposals().toString());
        out[0] = r.getProposals().get(0);
        return new ProposalIndex(r.getProposals(), addresses, osm, null, lots);
    }

    @Test
    void everyChoiceOfAnAmbiguousRowFindsIt() {
        DataSet osm = new DataSet();
        Way left = Fixtures.rect(osm, -10, 10, 10, 10, "building=house");
        Way right = Fixtures.rect(osm, 10, 10, 10, 10, "building=house");
        DataSet addresses = new DataSet();
        Fixtures.node(addresses, 0, -25, Fixtures.addr("10", "West Olive Avenue"));
        Proposal[] p = new Proposal[1];
        ProposalIndex idx = oneParcel(osm, addresses, p);
        assertEquals(Bucket.AMBIGUOUS_BUILDING, p[0].getBucket());
        assertEquals(Set.of(p[0]), idx.find(osm, List.of(left)));
        assertEquals(Set.of(p[0]), idx.find(osm, List.of(right)));
    }

    @Test
    void losingRunnerUpFindsNothing() {
        // Applying with the shed selected would move the address onto it.
        DataSet osm = new DataSet();
        Fixtures.rect(osm, 0, 10, 12, 10, "building=house");
        Way shed = Fixtures.rect(osm, 10, -15, 4, 4, "building=shed");
        DataSet addresses = new DataSet();
        Fixtures.node(addresses, 0, -25, Fixtures.addr("10", "West Olive Avenue"));
        Proposal[] p = new Proposal[1];
        ProposalIndex idx = oneParcel(osm, addresses, p);
        assertEquals(Bucket.CLEAN, p[0].getBucket());
        assertTrue(idx.find(osm, List.of(shed)).isEmpty());
    }

    @Test
    void buildingAlreadyCarryingTheAddressFindsTheRow() {
        DataSet osm = new DataSet();
        Way addressed = Fixtures.rect(osm, 0, 10, 12, 10, Fixtures.concat(Fixtures.addr("10", "West Olive Avenue"), "building=house"));
        DataSet addresses = new DataSet();
        Fixtures.node(addresses, 0, -25, Fixtures.addr("10", "West Olive Avenue"));
        Proposal[] p = new Proposal[1];
        ProposalIndex idx = oneParcel(osm, addresses, p);
        assertEquals(Bucket.EXISTING_ADDRESS, p[0].getBucket());
        assertEquals(Set.of(p[0]), idx.find(osm, List.of(addressed)));
    }

    @Test
    void primitivesAreOnlyLookedUpInTheirOwnLayersRole() {
        assertTrue(index.find(hints, List.of(addrA)).isEmpty());
        assertTrue(index.find(source, List.of(footprintB)).isEmpty());
        assertTrue(index.find(null, List.of(addrA)).isEmpty());
    }

    @Test
    void voronoiRunHasNoParcelLayer() {
        ProposalIndex noParcels = new ProposalIndex(List.of(pA, pB, pC), source, target, hints, null);
        assertTrue(noParcels.find(parcels, List.of(parcelB)).isEmpty());
        assertEquals(Set.of(pA), noParcels.find(source, List.of(addrA)));
    }
}
