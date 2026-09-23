// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;

/** Coverage tiles and the dataset list are real OpenAddresses responses saved as fixtures. */
class ParcelSourceFinderTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    private static final Bounds GLENDALE = new Bounds(33.560, -112.180, 33.570, -112.170);
    private static final Bounds OWYHEE = new Bounds(42.980, -116.100, 42.995, -116.085);

    private static byte[] bytes(String name) throws IOException {
        try (InputStream is = JosmTestSetup.resource("fixtures/" + name)) {
            return is.readAllBytes();
        }
    }

    private static Map<Long, List<ParcelSourceFinder.Dataset>> catalog() throws IOException {
        return ParcelSourceFinder.parseCatalog(new String(bytes("oa-parcel-datasets.json"), StandardCharsets.UTF_8));
    }

    private static List<String> sourcesFor(Bounds view, String tileFixture) throws IOException {
        int[] t = ParcelSourceFinder.tilesFor(view, ParcelSourceFinder.ZOOM).get(0);
        Set<Long> ids = ParcelSourceFinder.parcelAreasInTile(bytes(tileFixture),
                ParcelSourceFinder.viewInTile(view, ParcelSourceFinder.ZOOM, t[0], t[1]));
        return ParcelSourceFinder.datasetsFor(ids, catalog()).stream().map(d -> d.source).collect(Collectors.toList());
    }

    @Test
    void tileMath() {
        List<int[]> tiles = ParcelSourceFinder.tilesFor(GLENDALE, ParcelSourceFinder.ZOOM);
        assertEquals(1, tiles.size());
        assertArrayEquals(new int[] {771, 1642}, tiles.get(0));
        assertArrayEquals(new int[] {727, 1505}, ParcelSourceFinder.tilesFor(OWYHEE, ParcelSourceFinder.ZOOM).get(0));
        // a view straddling a tile corner needs four tiles
        assertEquals(4, ParcelSourceFinder.tilesFor(new Bounds(33.575, -112.240, 33.585, -112.230), ParcelSourceFinder.ZOOM).size());
    }

    @Test
    void glendaleOffersCityThenCountyThenState() throws IOException {
        assertEquals(List.of("us/az/city_of_glendale", "us/az/maricopa", "us/az/statewide"),
                sourcesFor(GLENDALE, "oa-coverage-glendale-12-771-1642.mvt"));
    }

    @Test
    void owyheeOffersIdahoStatewide() throws IOException {
        assertEquals(List.of("us/id/statewide"), sourcesFor(OWYHEE, "oa-coverage-owyhee-12-727-1505.mvt"));
    }

    @Test
    void polygonsAreTestedAgainstTheView() throws IOException {
        // Arizona and Maricopa arrive unclipped, far past 16-bit tile coordinates. A view
        // well away from both must not offer them; the city, a bare point, is always kept.
        Set<Long> far = ParcelSourceFinder.parcelAreasInTile(bytes("oa-coverage-glendale-12-771-1642.mvt"),
                new java.awt.geom.Rectangle2D.Double(-200, -200, 0.1, 0.1));
        assertEquals(Set.of(179L), far);
    }

    @Test
    void catalogSkipsEntriesWithoutCoverage() throws IOException {
        Map<Long, List<ParcelSourceFinder.Dataset>> c = ParcelSourceFinder.parseCatalog(
                "[{\"source\":\"us/xx/a\",\"layer\":\"parcels\",\"name\":\"county\",\"map\":0},"
                + "{\"source\":\"us/xx/b\",\"layer\":\"addresses\",\"name\":\"county\",\"map\":5},"
                + "{\"source\":\"us/xx/c\",\"layer\":\"parcels\",\"name\":\"city\",\"map\":7}]");
        assertEquals(Set.of(7L), c.keySet());
    }
}
