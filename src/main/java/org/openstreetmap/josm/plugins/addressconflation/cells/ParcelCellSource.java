// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.cells;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.OsmGeometry;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;

/**
 * Cells from a parcel layer: every closed way and every multipolygon relation
 * in the dataset. An OpenAddresses parcel layer carries {@code oa:pid}, which
 * becomes the cell id; anything else falls back to the primitive id.
 */
public class ParcelCellSource implements CellSource {
    public static final String PID_KEY = "oa:pid";

    private final DataSet parcels;
    private final String name;

    public ParcelCellSource(DataSet parcels, String name) {
        this.parcels = parcels;
        this.name = name;
    }

    @Override
    public List<Cell> buildCells(LocalProjection proj, List<Coordinate> addressSites, Envelope extent, ConflationSettings settings) {
        List<Cell> cells = new ArrayList<>();
        Envelope search = new Envelope(extent);
        search.expandBy(settings.matchDistanceMeters * 2 + 500);
        Set<Way> memberWays = new HashSet<>();
        for (Relation r : parcels.getRelations()) {
            if (r.isUsable() && r.isMultipolygon()) {
                r.getMemberPrimitives(Way.class).forEach(memberWays::add);
                add(cells, r, proj, search);
            }
        }
        for (Way w : parcels.getWays()) {
            if (w.isUsable() && w.isClosed() && !memberWays.contains(w)) {
                add(cells, w, proj, search);
            }
        }
        return cells;
    }

    private static void add(List<Cell> cells, OsmPrimitive prim, LocalProjection proj, Envelope search) {
        Geometry g = OsmGeometry.toPolygon(prim, proj);
        if (g == null || g.isEmpty() || !g.getEnvelopeInternal().intersects(search)) {
            return;
        }
        String id = prim.hasKey(PID_KEY) ? prim.get(PID_KEY) : prim.getType().getAPIName().charAt(0) + Long.toString(prim.getUniqueId());
        cells.add(new Cell(id, g, false, prim));
    }

    @Override
    public boolean isSynthetic() {
        return false;
    }

    @Override
    public String describe() {
        return name;
    }
}
