// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.cells;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Typical distance between neighbouring addresses around a point: the median,
 * over the point's nearest addresses, of each one's distance to its own
 * nearest neighbour. Unlike cell size this is not inflated at the edge of a
 * cluster, and it follows the local density, so a town core and the farms
 * around it each get their own scale.
 */
final class LocalSpacing {
    /** How many nearby addresses the median is taken over. */
    static final int NEIGHBOURHOOD = 10;
    /** Points closer than this are the same spot (units, duplicates), not neighbours. Metres. */
    private static final double SAME_SPOT = 1.0;
    /** How many candidates to look at when skipping same-spot neighbours. */
    private static final int NN_CANDIDATES = 8;
    private static final ItemDistance DIST = (a, b) -> ((Coordinate) a.getItem()).distance((Coordinate) b.getItem());

    private final STRtree index = new STRtree();
    private final Map<Coordinate, Double> nearest = new HashMap<>();
    private final double fallback;

    LocalSpacing(List<Coordinate> sites) {
        List<Coordinate> distinct = new ArrayList<>();
        for (Coordinate c : sites) {
            if (!nearest.containsKey(c)) {
                nearest.put(c, Double.NaN);
                distinct.add(c);
                index.insert(new Envelope(c), c);
            }
        }
        if (distinct.size() > 1) {
            index.build();
        }
        List<Double> all = new ArrayList<>();
        for (Coordinate c : distinct) {
            double d = nearestOther(c);
            nearest.put(c, d);
            if (!Double.isNaN(d)) {
                all.add(d);
            }
        }
        fallback = all.isEmpty() ? Double.NaN : median(all);
    }

    /** Local spacing around the site, in metres; NaN when there is only one address. */
    double at(Coordinate site) {
        if (nearest.size() < 2) {
            return Double.NaN;
        }
        Object[] near = index.nearestNeighbour(new Envelope(site), site, DIST, Math.min(NEIGHBOURHOOD, nearest.size()));
        List<Double> ds = new ArrayList<>();
        for (Object o : near) {
            Double d = nearest.get(o);
            if (d != null && !Double.isNaN(d)) {
                ds.add(d);
            }
        }
        return ds.isEmpty() ? fallback : median(ds);
    }

    private double nearestOther(Coordinate c) {
        if (nearest.size() < 2) {
            return Double.NaN;
        }
        Object[] near = index.nearestNeighbour(new Envelope(c), c, DIST, Math.min(NN_CANDIDATES, nearest.size()));
        double best = Double.NaN;
        for (Object o : near) {
            double d = ((Coordinate) o).distance(c);
            if (d >= SAME_SPOT && (Double.isNaN(best) || d < best)) {
                best = d;
            }
        }
        return best;
    }

    private static double median(List<Double> ds) {
        double[] a = ds.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }
}
