// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;

/**
 * Detects a systematic offset between the address points and the buildings
 * they were matched to. Some county feeds are shifted as a whole (a datum or
 * export problem); the median vector from address point to matched building
 * centroid over the Clean proposals recovers it, and the spread around the
 * median says whether it is one shift or just noise.
 */
public final class ShiftEstimator {

    /** A suggested shift of the address layer, in metres east and north. */
    public static final class Shift {
        private final double dx;
        private final double dy;
        private final int samples;
        private final double spread;

        Shift(double dx, double dy, int samples, double spread) {
            this.dx = dx;
            this.dy = dy;
            this.samples = samples;
            this.spread = spread;
        }

        /** Metres to move the address points east. */
        public double getDx() {
            return dx;
        }

        /** Metres to move the address points north. */
        public double getDy() {
            return dy;
        }

        public double getDistance() {
            return Math.hypot(dx, dy);
        }

        public int getSamples() {
            return samples;
        }

        /** Median absolute deviation of the per-address vectors around the median, metres. */
        public double getSpread() {
            return spread;
        }

        /** True when the offset is big enough and consistent enough to be worth applying. */
        public boolean isSignificant() {
            return samples >= 20 && getDistance() >= 5.0 && spread < getDistance() * 0.5;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "shift %.1f m east, %.1f m north (%.1f m, spread %.1f m, %d samples)", dx, dy, getDistance(), spread, samples);
        }
    }

    private ShiftEstimator() {
    }

    /** Estimate from Clean proposals; null when there are too few. */
    public static Shift estimate(List<Proposal> proposals, LocalProjection proj) {
        List<Double> dxs = new ArrayList<>();
        List<Double> dys = new ArrayList<>();
        for (Proposal p : proposals) {
            if (p.getBucket() != Bucket.CLEAN || p.getTarget() == null || p.getAddresses().size() != 1) {
                continue;
            }
            AddressGroup g = p.getAddresses().get(0);
            Point c = p.getTarget().getGeometry().getCentroid();
            Coordinate a = proj.toXY(g.getPosition());
            dxs.add(c.getX() - a.x);
            dys.add(c.getY() - a.y);
        }
        if (dxs.size() < 5) {
            return null;
        }
        double mx = median(dxs);
        double my = median(dys);
        List<Double> dev = new ArrayList<>(dxs.size());
        for (int i = 0; i < dxs.size(); i++) {
            dev.add(Math.hypot(dxs.get(i) - mx, dys.get(i) - my));
        }
        return new Shift(mx, my, dxs.size(), median(dev));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
