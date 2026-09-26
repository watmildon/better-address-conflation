// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseAssessment;
import org.openstreetmap.josm.plugins.addressconflation.license.Licensing;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

/**
 * Finds OpenAddresses parcel sources covering a map view, so the user never has
 * to look a source id up by hand.
 * <ol>
 * <li>OpenAddresses' coverage map, served as vector tiles, says which source
 * coverage areas touch the view and whether each has parcels.</li>
 * <li>The parcel dataset list joins a coverage area (its "map" id) to source ids
 * such as {@code us/az/maricopa}.</li>
 * <li>Each source definition says whether its parcel layer is an ESRI service,
 * which is what we can download for just the view.</li>
 * </ol>
 * Coverage is the area a source declares, not where its data is, so an offer
 * means "worth trying", not "has parcels here".
 */
public final class ParcelSourceFinder {
    static final String API = "https://batch.openaddresses.io/api";
    /** Zoom of the coverage tiles we read: about 10 km across, a few KB each. */
    static final int ZOOM = 12;
    /** Never read more coverage tiles than this for one view. */
    private static final int MAX_TILES = 9;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Parcel dataset list, fetched once per JOSM session. */
    private static volatile Map<Long, List<Dataset>> catalog;

    private ParcelSourceFinder() {
    }

    /** One OpenAddresses parcel dataset from the batch API. */
    static final class Dataset {
        final String source;
        /** OpenAddresses' name for the layer entry: city, county, state... */
        final String level;
        final String updated;

        Dataset(String source, String level, String updated) {
            this.source = source;
            this.level = level;
            this.updated = updated;
        }
    }

    /** A parcel source offered for the view. */
    public static final class Offer {
        private final String sourceId;
        private final String level;
        private final String updated;
        private final EsriFeatureSource esri;
        private final String problem;

        Offer(String sourceId, String level, String updated, EsriFeatureSource esri, String problem) {
            this.sourceId = sourceId;
            this.level = level;
            this.updated = updated;
            this.esri = esri;
            this.problem = problem;
        }

        public String getSourceId() {
            return sourceId;
        }

        /** The ESRI parcel layer to download, or null when this source cannot be fetched for a view. */
        public EsriFeatureSource getEsriSource() {
            return esri;
        }

        /** The licence verdict for the parcels, or null when the source cannot be downloaded. */
        public LicenseAssessment getLicense() {
            return esri == null ? null : esri.getLicense();
        }

        /** Why the source cannot be downloaded, or null. */
        public String getProblem() {
            return problem;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(sourceId).append(" (").append(level);
            if (updated != null && updated.length() >= 10) {
                sb.append(", ").append(tr("updated {0}", updated.substring(0, 10)));
            }
            sb.append(')');
            if (problem != null) {
                sb.append(" - ").append(problem);
            }
            return sb.toString();
        }
    }

    /**
     * Parcel sources for the view, most local first (city, then county, then state).
     * Blocks on the network; call it off the EDT.
     */
    public static List<Offer> find(Bounds view) throws IOException {
        Set<Long> ids = new LinkedHashSet<>();
        List<int[]> tiles = tilesFor(view, ZOOM);
        if (tiles.size() > MAX_TILES) {
            tiles = tiles.subList(0, MAX_TILES);
        }
        for (int[] t : tiles) {
            byte[] tile = fetchBytes(API + "/map/" + ZOOM + "/" + t[0] + "/" + t[1] + ".mvt");
            ids.addAll(parcelAreasInTile(tile, viewInTile(view, ZOOM, t[0], t[1])));
        }
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<Dataset> datasets = datasetsFor(ids, catalog());
        List<Offer> offers = new ArrayList<>();
        for (Dataset d : datasets) {
            offers.add(offerFor(d));
        }
        return offers;
    }

    private static Offer offerFor(Dataset d) {
        List<EsriFeatureSource> layers;
        try {
            layers = OpenAddressesSourceReader.load(OpenAddressesSourceReader.resolve(d.source), false);
        } catch (IOException e) {
            Logging.warn("OpenAddresses source " + d.source + ": " + e.getMessage());
            return new Offer(d.source, d.level, d.updated, null, tr("source definition unavailable"));
        }
        for (EsriFeatureSource s : layers) {
            if (s.getKind() == EsriFeatureSource.Kind.PARCELS) {
                EsriFeatureSource named = s.withName(tr("Parcels {0}", d.source))
                        .withLicense(Licensing.assess(d.source, "parcels", s.getUrl(), s.getDeclaredLicense()));
                return new Offer(d.source, d.level, d.updated, named, null);
            }
        }
        return new Offer(d.source, d.level, d.updated, null, tr("not an ESRI service, cannot download by area"));
    }

    // ---- tiles -------------------------------------------------------------------------

    /** Slippy-map tiles (x, y) at zoom z covering the bounds, row by row. */
    static List<int[]> tilesFor(Bounds b, int z) {
        int x0 = tileX(b.getMinLon(), z);
        int x1 = tileX(b.getMaxLon(), z);
        int y0 = tileY(b.getMaxLat(), z);
        int y1 = tileY(b.getMinLat(), z);
        List<int[]> out = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                out.add(new int[] {x, y});
            }
        }
        return out;
    }

    static int tileX(double lon, int z) {
        int n = 1 << z;
        return Math.max(0, Math.min(n - 1, (int) Math.floor((lon + 180) / 360 * n)));
    }

    static int tileY(double lat, int z) {
        int n = 1 << z;
        double r = Math.toRadians(lat);
        return Math.max(0, Math.min(n - 1, (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n)));
    }

    /** The bounds in the tile's own 0..1 coordinates (y down); scaled to the layer extent later. */
    static Rectangle2D viewInTile(Bounds b, int z, int x, int y) {
        int n = 1 << z;
        double left = (b.getMinLon() + 180) / 360 * n - x;
        double right = (b.getMaxLon() + 180) / 360 * n - x;
        double top = mercY(b.getMaxLat()) * n - y;
        double bottom = mercY(b.getMinLat()) * n - y;
        return new Rectangle2D.Double(left, top, right - left, bottom - top);
    }

    private static double mercY(double lat) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2;
    }

    /**
     * Ids of the coverage areas in a tile that have parcels and touch the view.
     * Point coverages (some cities declare only a point) are kept: their extent is unknown.
     */
    static Set<Long> parcelAreasInTile(byte[] tile, Rectangle2D viewUnit) throws IOException {
        Set<Long> ids = new LinkedHashSet<>();
        for (CoverageTile.Feature f : CoverageTile.read(tile)) {
            if (Boolean.TRUE.equals(f.tags.get("parcels")) && (f.isPoint() || f.touches(viewUnit))) {
                ids.add(f.id);
            }
        }
        return ids;
    }

    // ---- catalog -----------------------------------------------------------------------

    private static Map<Long, List<Dataset>> catalog() throws IOException {
        Map<Long, List<Dataset>> c = catalog;
        if (c == null) {
            c = parseCatalog(new String(fetchBytes(API + "/data?layer=parcels"), java.nio.charset.StandardCharsets.UTF_8));
            catalog = c;
        }
        return c;
    }

    /** Parcel datasets by coverage ("map") id. */
    static Map<Long, List<Dataset>> parseCatalog(String json) throws IOException {
        JsonArray arr;
        try (JsonReader r = JsonSupport.JSON.createReader(new StringReader(json))) {
            arr = r.readArray();
        } catch (JsonException | IllegalStateException e) {
            throw new IOException("Unexpected OpenAddresses dataset list: " + e.getMessage(), e);
        }
        Map<Long, List<Dataset>> out = new HashMap<>();
        for (JsonValue v : arr) {
            JsonObject o = v.asJsonObject();
            if (!"parcels".equals(o.getString("layer", "")) || !o.containsKey("map") || o.isNull("map")) {
                continue;
            }
            long map = o.getJsonNumber("map").longValue();
            if (map == 0) {
                continue;
            }
            out.computeIfAbsent(map, k -> new ArrayList<>())
                    .add(new Dataset(o.getString("source", ""), o.getString("name", ""), o.getString("updated", null)));
        }
        return out;
    }

    /** Datasets for the coverage ids, one per source, most local first. */
    static List<Dataset> datasetsFor(Collection<Long> ids, Map<Long, List<Dataset>> catalog) {
        Map<String, Dataset> bySource = new HashMap<>();
        for (Long id : ids) {
            for (Dataset d : catalog.getOrDefault(id, List.of())) {
                bySource.putIfAbsent(d.source, d);
            }
        }
        List<Dataset> out = new ArrayList<>(bySource.values());
        out.sort(Comparator.comparingInt((Dataset d) -> rank(d.level)).thenComparing(d -> d.source));
        return out;
    }

    /** City before county before state: the more local source is usually the better parcel map. */
    static int rank(String level) {
        switch (level) {
        case "city":
        case "town":
            return 0;
        case "state":
            return 2;
        default:
            return 1;
        }
    }

    private static byte[] fetchBytes(String url) throws IOException {
        HttpClient.Response resp = HttpClient.create(URI.create(url).toURL())
                .setConnectTimeout(CONNECT_TIMEOUT_MS).setReadTimeout(READ_TIMEOUT_MS).connect();
        try {
            if (resp.getResponseCode() != 200) {
                throw new IOException("HTTP " + resp.getResponseCode() + " for " + url);
            }
            return resp.getContent().readAllBytes();
        } finally {
            resp.disconnect();
        }
    }
}
