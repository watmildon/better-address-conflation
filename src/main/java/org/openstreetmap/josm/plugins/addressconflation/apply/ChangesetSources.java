// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.apply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.tools.Logging;

/**
 * Remembers which of the plugin's layers fed each edit, and names them in the changeset's
 * {@code source} tag when those edits are uploaded. The tag is set before JOSM shows its upload
 * dialog, so the mapper sees it and can change it.
 *
 * Objects are tracked by identity: a primitive's id, and with it its hash code, changes when a
 * new object is uploaded. Records live as long as the dataset they belong to.
 */
public final class ChangesetSources implements UploadHook {
    public static final String SOURCE = "source";

    /** The hook JOSM calls; registered once by the plugin. */
    public static final ChangesetSources HOOK = new ChangesetSources();

    private final Map<DataSet, Map<OsmPrimitive, Set<String>>> records = new WeakHashMap<>();
    /** Sources of the upload being prepared, from checkUpload to modifyChangesetTags. */
    private Set<String> pending = new LinkedHashSet<>();

    ChangesetSources() {
    }

    /** Note that these edited primitives came from these sources. */
    public synchronized void record(Collection<? extends OsmPrimitive> primitives, Collection<String> sources) {
        if (sources.isEmpty()) {
            return;
        }
        for (OsmPrimitive p : primitives) {
            if (p.getDataSet() == null) {
                continue;
            }
            records.computeIfAbsent(p.getDataSet(), ds -> new IdentityHashMap<>())
                    .computeIfAbsent(p, x -> new LinkedHashSet<>()).addAll(sources);
        }
    }

    /** The sources recorded for any of these primitives, in the order they were first used. */
    synchronized Set<String> sourcesOf(Collection<? extends OsmPrimitive> primitives) {
        Set<String> out = new LinkedHashSet<>();
        for (OsmPrimitive p : primitives) {
            Map<OsmPrimitive, Set<String>> byPrimitive = p.getDataSet() == null ? null : records.get(p.getDataSet());
            Set<String> s = byPrimitive == null ? null : byPrimitive.get(p);
            if (s != null) {
                out.addAll(s);
            }
        }
        return out;
    }

    @Override
    public synchronized boolean checkUpload(APIDataSet apiDataSet) {
        pending = sourcesOf(apiDataSet.getPrimitives());
        return true;
    }

    @Override
    public synchronized void modifyChangesetTags(Map<String, String> tags) {
        if (!pending.isEmpty()) {
            String merged = merge(tags.get(SOURCE), pending);
            Logging.info("Changeset source: " + merged);
            tags.put(SOURCE, merged);
        }
        pending = new LinkedHashSet<>();
    }

    /** The existing source value with the missing sources added, separated by "; ". */
    static String merge(String existing, Collection<String> sources) {
        List<String> parts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (existing != null) {
            for (String part : existing.split(";")) {
                String p = part.trim();
                if (!p.isEmpty() && seen.add(p.toLowerCase(Locale.ROOT))) {
                    parts.add(p);
                }
            }
        }
        for (String s : sources) {
            String p = s.trim();
            if (!p.isEmpty() && seen.add(p.toLowerCase(Locale.ROOT))) {
                parts.add(p);
            }
        }
        return String.join("; ", parts);
    }
}
