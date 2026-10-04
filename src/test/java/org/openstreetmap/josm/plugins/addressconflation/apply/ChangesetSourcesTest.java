// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.plugins.addressconflation.Fixtures;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/** The changeset source tag names the plugin's layers behind the uploaded edits, and only those. */
class ChangesetSourcesTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final String NAD = "National Address Database";
    private static final String PARCELS = "Parcels us/in/statewide";

    private final ChangesetSources hook = new ChangesetSources();
    private final DataSet ds = new DataSet();

    private Node newNode() {
        Node n = new Node(new LatLon(40.04, -86.91));
        ds.addPrimitive(n);
        return n;
    }

    private Node downloaded(DataSet in, long id) {
        Node n = new Node(id, 1);
        n.setCoor(new LatLon(40.04, -86.91));
        in.addPrimitive(n);
        return n;
    }

    private Map<String, String> upload(Node... nodes) {
        Map<String, String> tags = new HashMap<>();
        tags.put("comment", "Addresses in Crawfordsville");
        assertTrue(hook.checkUpload(new APIDataSet(Arrays.asList(nodes))));
        hook.modifyChangesetTags(tags);
        return tags;
    }

    @Test
    void uploadedEditsNameTheirSources() {
        Node added = newNode();
        Node tagged = downloaded(ds, 10);
        tagged.put("addr:housenumber", "10");
        tagged.setModified(true); // as the applied command does
        hook.record(List.of(added), List.of(NAD));
        hook.record(List.of(tagged), List.of(NAD, PARCELS));
        Node unrelated = newNode();
        assertEquals(NAD + "; " + PARCELS, upload(added, tagged, unrelated).get(ChangesetSources.SOURCE));
    }

    @Test
    void appliedCommandsCoverWhatJosmUploads() {
        // Parcel A: a house gets tagged. Parcel B: no OSM building, a node goes on a hint footprint.
        DataSet parcels = new DataSet();
        Fixtures.rect(parcels, 0, 0, 40, 60, "oa:pid=A");
        Fixtures.rect(parcels, 50, 0, 40, 60, "oa:pid=B");
        DataSet target = new DataSet();
        Fixtures.rect(target, 0, 10, 12, 10, "building=house");
        DataSet hints = new DataSet();
        Fixtures.rect(hints, 50, 10, 12, 10, "building=yes");
        DataSet source = new DataSet();
        Fixtures.node(source, 0, -25, Fixtures.addr("10", "West Market Street"));
        Fixtures.node(source, 50, -25, Fixtures.addr("20", "West Market Street"));
        ConflationSettings s = new ConflationSettings();
        AnalysisResult r = Analyzer.analyze(source, target, hints, new ParcelCellSource(parcels, "p"), s);
        for (Proposal p : r.getProposals()) {
            Command c = ProposalApplier.build(p, target, source, r.getProjection(), s).getTargetCommand();
            assertTrue(c.executeCommand());
            hook.record(c.getParticipatingPrimitives(), p.getTarget().isHint() ? List.of(NAD, "Microsoft building footprints") : List.of(NAD));
        }
        hook.checkUpload(new APIDataSet(target));
        Map<String, String> tags = new HashMap<>();
        hook.modifyChangesetTags(tags);
        assertEquals(NAD + "; Microsoft building footprints", tags.get(ChangesetSources.SOURCE));
    }

    @Test
    void anUploadWithoutOurEditsIsLeftAlone() {
        hook.record(List.of(newNode()), List.of(NAD));
        Map<String, String> tags = upload(newNode());
        assertFalse(tags.containsKey(ChangesetSources.SOURCE), tags.toString());
    }

    @Test
    void theMappersOwnSourceIsKept() {
        Node added = newNode();
        hook.record(List.of(added), List.of(NAD, PARCELS));
        Map<String, String> tags = new HashMap<>();
        tags.put(ChangesetSources.SOURCE, "survey; national address database");
        hook.checkUpload(new APIDataSet(List.of(added)));
        hook.modifyChangesetTags(tags);
        assertEquals("survey; national address database; " + PARCELS, tags.get(ChangesetSources.SOURCE));
    }

    @Test
    void sourcesAreUsedForOneUploadOnly() {
        Node added = newNode();
        hook.record(List.of(added), List.of(NAD));
        upload(added);
        Map<String, String> next = new HashMap<>();
        hook.modifyChangesetTags(next);
        assertFalse(next.containsKey(ChangesetSources.SOURCE), "nothing pending without a new checkUpload");
    }

    @Test
    void anObjectIsStillKnownAfterItsIdChanges() {
        // Uploading gives a new object its real id, which changes its hash code.
        Node added = newNode();
        hook.record(List.of(added), List.of(NAD));
        added.setOsmId(4242, 1);
        assertEquals(Set.of(NAD), hook.sourcesOf(List.of(added)));
    }

    @Test
    void theSameIdInAnotherLayerIsAnotherObject() {
        Node here = downloaded(ds, 77);
        DataSet other = new DataSet();
        Node there = downloaded(other, 77);
        hook.record(List.of(here), List.of(NAD));
        assertTrue(hook.sourcesOf(List.of(there)).isEmpty());
    }
}
