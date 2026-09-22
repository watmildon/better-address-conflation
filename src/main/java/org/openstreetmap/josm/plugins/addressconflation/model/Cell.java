// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.model;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.openstreetmap.josm.data.osm.OsmPrimitive;

/** A parcel, or a synthetic Voronoi cell standing in for one. */
public final class Cell {
    private final String id;
    private final Geometry geometry;
    private final PreparedGeometry prepared;
    private final boolean synthetic;
    private final OsmPrimitive sourcePrimitive;
    private final List<CellBuilding> buildings = new ArrayList<>();
    private final List<AddressGroup> addresses = new ArrayList<>();
    private final List<ExistingAddress> existing = new ArrayList<>();

    public Cell(String id, Geometry geometry, boolean synthetic, OsmPrimitive sourcePrimitive) {
        this.id = id;
        this.geometry = geometry;
        this.prepared = PreparedGeometryFactory.prepare(geometry);
        this.synthetic = synthetic;
        this.sourcePrimitive = sourcePrimitive;
    }

    public String getId() {
        return id;
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public PreparedGeometry getPrepared() {
        return prepared;
    }

    /** True for Voronoi cells, false for real parcels. */
    public boolean isSynthetic() {
        return synthetic;
    }

    /** The parcel primitive in the parcel layer, or null for synthetic cells. */
    public OsmPrimitive getSourcePrimitive() {
        return sourcePrimitive;
    }

    /** Buildings overlapping this cell, sorted best first once scored. */
    public List<CellBuilding> getBuildings() {
        return buildings;
    }

    public List<AddressGroup> getAddresses() {
        return addresses;
    }

    /** Addresses already in the edit layer whose representative point is in this cell. */
    public List<ExistingAddress> getExisting() {
        return existing;
    }

    @Override
    public String toString() {
        return "Cell[" + id + (synthetic ? " voronoi" : "") + "]";
    }
}
