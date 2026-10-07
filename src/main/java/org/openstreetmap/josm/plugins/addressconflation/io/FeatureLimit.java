// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.util.function.BiPredicate;

import org.openstreetmap.josm.tools.Logging;

/**
 * The safety limit on the features one source downloads, and what happens on reaching it:
 * stop there, or ask the mapper whether to fetch the rest. One per source download; once the
 * mapper says go on, the rest comes without asking again.
 */
public final class FeatureLimit {
    /** Usually plenty: a dense city view at the largest allowed area holds about this many addresses. */
    public static final int MAX = 200_000;

    private final int max;
    private final BiPredicate<String, Integer> ask;
    private boolean lifted;

    FeatureLimit(int max, BiPredicate<String, Integer> ask) {
        this.max = max;
        this.ask = ask;
    }

    /** Stop at the limit without asking: tests, scripts and other callers without a mapper to ask. */
    public static FeatureLimit stop() {
        return new FeatureLimit(MAX, (name, count) -> false);
    }

    /**
     * Ask on reaching the limit.
     *
     * @param ask given the source name and the features so far; true to download the rest.
     *            Called on the downloading thread.
     */
    public static FeatureLimit asking(BiPredicate<String, Integer> ask) {
        return new FeatureLimit(MAX, ask);
    }

    /** True when the download should stop with {@code count} features: the limit is reached and nobody wants more. */
    boolean reached(String sourceName, int count) {
        if (lifted || count < max) {
            return false;
        }
        if (ask.test(sourceName, count)) {
            lifted = true;
            return false;
        }
        Logging.warn(sourceName + ": download stopped at the safety limit of " + max + " features");
        return true;
    }
}
