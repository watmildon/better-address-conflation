// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/** A proposal stays applied while any of its commands is in effect, through undo and redo. */
class AppliedProposalsTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private final DataSet ds = new DataSet();
    private final AppliedProposals applied = new AppliedProposals();
    private final Proposal p = new Proposal(Bucket.NO_BUILDING, Collections.emptyList(), null, null, 0.9, List.of("test"), null, null, null);
    /** The edit layer's change, then the address layer's deletions, as the panel adds them. */
    private final Command target = command();
    private final Command source = command();

    private Command command() {
        return new AddCommand(ds, new Node(new LatLon(40, -87)));
    }

    @Test
    void comesBackOnlyWhenEveryCommandIsUndone() {
        applied.applied(p, List.of(target, source));
        assertTrue(applied.isApplied(p));
        assertNull(applied.undone(source), "the edit layer's change is still in effect");
        assertTrue(applied.isApplied(p));
        assertSame(p, applied.undone(target));
        assertFalse(applied.isApplied(p));
    }

    @Test
    void leavesTheListAgainOnTheFirstRedo() {
        applied.applied(p, List.of(target, source));
        applied.undone(source);
        applied.undone(target);
        assertSame(p, applied.redone(target));
        assertNull(applied.redone(source), "already applied");
        assertTrue(applied.isApplied(p));
    }

    @Test
    void unknownCommandsChangeNothing() {
        applied.applied(p, List.of(target));
        assertNull(applied.undone(command()));
        assertNull(applied.redone(command()));
        assertTrue(applied.isApplied(p));
    }

    @Test
    void clearedUndoHistoryKeepsProposalsApplied() {
        applied.applied(p, List.of(target, source));
        applied.forgetCommands();
        assertTrue(applied.isApplied(p));
        assertNull(applied.undone(source));
        applied.clear();
        assertFalse(applied.isApplied(p));
    }
}
