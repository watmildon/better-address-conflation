// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.engine;

import org.locationtech.jts.geom.Coordinate;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * Equirectangular projection around a local origin, in metres. Good enough
 * for areas of a few kilometres, and it keeps areas and distances in units
 * mappers understand.
 */
public final class LocalProjection {
    private final double lat0;
    private final double lon0;
    private final double kx;
    private static final double KY = 110540.0;

    public LocalProjection(double lat0, double lon0) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.kx = 111320.0 * Math.cos(Math.toRadians(lat0));
    }

    public Coordinate toXY(LatLon ll) {
        return toXY(ll.lat(), ll.lon());
    }

    public Coordinate toXY(double lat, double lon) {
        return new Coordinate((lon - lon0) * kx, (lat - lat0) * KY);
    }

    public LatLon toLatLon(Coordinate c) {
        return new LatLon(lat0 + c.y / KY, lon0 + c.x / kx);
    }
}
