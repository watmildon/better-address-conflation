// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.openstreetmap.josm.plugins.addressconflation.model.BuildingCandidate;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;

/**
 * Detects a systematic offset between a parcel layer and the OSM buildings. County parcel
 * layers are often a few metres off the imagery the buildings were traced from, so houses
 * poke over the lot line into the neighbour's parcel. The shift of the parcel layer that puts
 * the most of the buildings' outlines inside a single parcel recovers it; how many buildings it helps
 * against how many it hurts says whether it is one shift or just noise.
 */
public final class ParcelShiftEstimator {

    /** Furthest shift tried in each direction. Metres. */
    static final double MAX_SHIFT = 12.0;
    /** Buildings measured at most; more only cost time. */
    static final int MAX_BUILDINGS = 300;
    /** Most points sampled along one building's outline. */
    private static final int MAX_POINTS_PER_BUILDING = 120;
    /** Closest spacing of the points along an outline. Metres. */
    private static final double MIN_SPACING = 1.0;
    /** A building's share in one parcel must change by more than this to count as helped or hurt. */
    private static final double CHANGE = 0.01;

    /** A suggested shift of the parcel layer, in metres east and north. */
    public static final class Shift {
        private final double dx;
        private final double dy;
        private final int samples;
        private final double before;
        private final double after;
        private final int improved;
        private final int worsened;

        Shift(double dx, double dy, int samples, double before, double after, int improved, int worsened) {
            this.dx = dx;
            this.dy = dy;
            this.samples = samples;
            this.before = before;
            this.after = after;
            this.improved = improved;
            this.worsened = worsened;
        }

        /** Metres to move the parcels east. */
        public double getDx() {
            return dx;
        }

        /** Metres to move the parcels north. */
        public double getDy() {
            return dy;
        }

        public double getDistance() {
            return Math.hypot(dx, dy);
        }

        /** Buildings measured. */
        public int getSamples() {
            return samples;
        }

        /** Mean share of a building's outline inside its best parcel, as the parcels are now. */
        public double getShareBefore() {
            return before;
        }

        /** Mean share of a building's outline inside its best parcel, with the parcels shifted. */
        public double getShareAfter() {
            return after;
        }

        /** Buildings that sit better in one parcel after the shift. */
        public int getImproved() {
            return improved;
        }

        /** Buildings that sit worse in one parcel after the shift. */
        public int getWorsened() {
            return worsened;
        }

        /**
         * True when the offset is big enough, helps enough and is consistent enough to be
         * worth applying: enough buildings, at least 1.5 m, the mean share in one parcel up by
         * two points or more, and at least three buildings helped for each one hurt.
         */
        public boolean isSignificant() {
            return samples >= 20 && getDistance() >= 1.5 && after - before >= 0.02 && improved >= 3 * worsened && improved >= 5;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "parcels %.1f m east, %.1f m north (%.1f m; buildings inside one parcel %.0f%% -> %.0f%%, "
                    + "%d helped, %d hurt, %d buildings)", dx, dy, getDistance(), before * 100, after * 100, improved, worsened, samples);
        }
    }

    /** One measured building: points along its outline and the parcels it could fall in after a shift. */
    private static final class Sample {
        final double[] xs;
        final double[] ys;
        final List<Parcel> near;

        Sample(double[] xs, double[] ys, List<Parcel> near) {
            this.xs = xs;
            this.ys = ys;
            this.near = near;
        }

        /** Largest share of the points inside a single parcel moved by (dx, dy). */
        double share(double dx, double dy) {
            int best = 0;
            for (Parcel p : near) {
                int n = 0;
                for (int i = 0; i < xs.length; i++) {
                    // Moving the parcel by (dx, dy) is moving the point by (-dx, -dy).
                    double x = xs[i] - dx;
                    double y = ys[i] - dy;
                    if (p.envelope.contains(x, y) && p.locator.locate(new Coordinate(x, y)) != Location.EXTERIOR) {
                        n++;
                    }
                }
                best = Math.max(best, n);
            }
            return (double) best / xs.length;
        }
    }

    private static final class Parcel {
        final Envelope envelope;
        final IndexedPointInAreaLocator locator;

        Parcel(Geometry g) {
            this.envelope = g.getEnvelopeInternal();
            this.locator = new IndexedPointInAreaLocator(g);
        }
    }

    private ParcelShiftEstimator() {
    }

    /**
     * Estimate from the parcels and the OSM buildings among them.
     *
     * @param parcels parcel cells (synthetic cells are ignored)
     * @param buildings OSM building outlines, in the same local projection
     * @return the best shift, or null when there are too few buildings to measure
     */
    public static Shift estimate(List<Cell> parcels, List<BuildingCandidate> buildings) {
        STRtree index = new STRtree();
        int count = 0;
        for (Cell c : parcels) {
            if (!c.isSynthetic()) {
                index.insert(c.getGeometry().getEnvelopeInternal(), new Parcel(c.getGeometry()));
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        index.build();

        List<BuildingCandidate> usable = new ArrayList<>();
        for (BuildingCandidate b : buildings) {
            if (!b.isHint() && !b.isNode() && b.getArea() >= 20 && b.getArea() <= 3000) {
                usable.add(b);
            }
        }
        usable.sort(Comparator.comparingLong(b -> b.getPrimitive().getUniqueId()));
        List<Sample> samples = new ArrayList<>();
        // Evenly spread over the area when there are more than enough.
        double step = Math.max(1.0, (double) usable.size() / MAX_BUILDINGS);
        for (double i = 0; i < usable.size(); i += step) {
            Sample s = sample(usable.get((int) i), index);
            // A building outside the parcel layer's coverage says nothing about the shift.
            if (s != null && s.share(0, 0) > 0) {
                samples.add(s);
            }
        }
        if (samples.size() < 5) {
            return null;
        }

        double[] best = {0, 0, score(samples, 0, 0)};
        search(samples, best, 0, 0, MAX_SHIFT, 2.0);
        search(samples, best, best[0], best[1], 2.0, 0.5);
        search(samples, best, best[0], best[1], 0.5, 0.25);

        double before = 0;
        double after = 0;
        int improved = 0;
        int worsened = 0;
        for (Sample s : samples) {
            double b = s.share(0, 0);
            double a = s.share(best[0], best[1]);
            before += b;
            after += a;
            if (a > b + CHANGE) {
                improved++;
            } else if (a < b - CHANGE) {
                worsened++;
            }
        }
        return new Shift(best[0], best[1], samples.size(), before / samples.size(), after / samples.size(), improved, worsened);
    }

    /**
     * Try every shift on a grid around (cx, cy) and keep the best in {@code best} (dx, dy,
     * score). Ties go to the smaller shift: when the buildings fit anywhere in a range, the
     * parcels move no further than they must.
     */
    private static void search(List<Sample> samples, double[] best, double cx, double cy, double reach, double step) {
        int n = (int) Math.round(reach / step);
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                double dx = cx + i * step;
                double dy = cy + j * step;
                if (Math.abs(dx) > MAX_SHIFT || Math.abs(dy) > MAX_SHIFT) {
                    continue;
                }
                double score = score(samples, dx, dy);
                if (score > best[2] + 1e-9 || (Math.abs(score - best[2]) <= 1e-9 && Math.hypot(dx, dy) < Math.hypot(best[0], best[1]))) {
                    best[0] = dx;
                    best[1] = dy;
                    best[2] = score;
                }
            }
        }
    }

    private static double score(List<Sample> samples, double dx, double dy) {
        double sum = 0;
        for (Sample s : samples) {
            sum += s.share(dx, dy);
        }
        return sum / samples.size();
    }

    /**
     * Points along the building's outline, with the parcels within reach of a shift. A house
     * pokes over a lot line at its edges, so that is where to look: points spread over the
     * inside would sit metres apart and miss an overhang narrower than their spacing.
     */
    @SuppressWarnings("unchecked")
    private static Sample sample(BuildingCandidate b, STRtree index) {
        Geometry g = b.getGeometry();
        Envelope env = g.getEnvelopeInternal();
        List<Coordinate> pts = new ArrayList<>();
        for (int k = 0; k < g.getNumGeometries(); k++) {
            Geometry part = g.getGeometryN(k);
            if (part instanceof Polygon) {
                LineString ring = ((Polygon) part).getExteriorRing();
                double spacing = Math.max(MIN_SPACING, ring.getLength() / MAX_POINTS_PER_BUILDING);
                along(ring.getCoordinates(), spacing, pts);
            }
        }
        if (pts.isEmpty()) {
            pts.add(g.getInteriorPoint().getCoordinate());
        }
        Envelope reach = new Envelope(env);
        reach.expandBy(MAX_SHIFT + 1);
        List<Parcel> near = new ArrayList<>((List<Parcel>) index.query(reach));
        if (near.isEmpty()) {
            return null;
        }
        double[] xs = new double[pts.size()];
        double[] ys = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i).x;
            ys[i] = pts.get(i).y;
        }
        return new Sample(xs, ys, near);
    }

    /** Points every {@code spacing} metres along a closed ring, starting at its first vertex. */
    private static void along(Coordinate[] ring, double spacing, List<Coordinate> out) {
        double carry = 0;
        for (int i = 0; i + 1 < ring.length; i++) {
            Coordinate a = ring[i];
            Coordinate b = ring[i + 1];
            double len = a.distance(b);
            double t = carry;
            while (t < len) {
                double f = t / len;
                out.add(new Coordinate(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y)));
                t += spacing;
            }
            carry = t - len;
        }
    }
}
