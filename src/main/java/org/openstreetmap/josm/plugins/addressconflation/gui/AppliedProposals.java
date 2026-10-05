// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Which proposals are applied, followed through undo and redo. Applying one proposal can take
 * two undoable commands (the edit layer's change, the address layer's deletions), and JOSM
 * undoes them one at a time. A proposal counts as applied while any of its commands is in
 * effect, so it only returns to the list once all of them are undone; otherwise a half-undone
 * proposal could be applied again on top of its own leftovers.
 */
final class AppliedProposals {
    private final Map<Command, Proposal> byCommand = new IdentityHashMap<>();
    /** Commands in effect per applied proposal. */
    private final Map<Proposal, Integer> inEffect = new IdentityHashMap<>();

    /** The proposal was applied by these executed commands. */
    void applied(Proposal p, Collection<Command> commands) {
        for (Command c : commands) {
            byCommand.put(c, p);
        }
        inEffect.merge(p, commands.size(), Integer::sum);
    }

    boolean isApplied(Proposal p) {
        return inEffect.getOrDefault(p, 0) > 0;
    }

    /**
     * A command was undone.
     * @return its proposal when that left none of the proposal's commands in effect, else null
     */
    Proposal undone(Command c) {
        Proposal p = byCommand.get(c);
        if (p == null || !isApplied(p)) {
            return null;
        }
        int left = inEffect.get(p) - 1;
        inEffect.put(p, left);
        return left == 0 ? p : null;
    }

    /**
     * A command was redone.
     * @return its proposal when it was not applied before, else null
     */
    Proposal redone(Command c) {
        Proposal p = byCommand.get(c);
        if (p == null) {
            return null;
        }
        boolean wasApplied = isApplied(p);
        inEffect.merge(p, 1, Integer::sum);
        return wasApplied ? null : p;
    }

    /** Forget everything: a new analysis, or JOSM cleared its undo history. */
    void clear() {
        byCommand.clear();
        inEffect.clear();
    }

    /** Forget the commands only: undo history is gone, so applied proposals stay applied for good. */
    void forgetCommands() {
        byCommand.clear();
    }
}
