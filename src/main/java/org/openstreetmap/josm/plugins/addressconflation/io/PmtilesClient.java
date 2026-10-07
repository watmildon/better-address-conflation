// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.vectortile.mapbox.Command;
import org.openstreetmap.josm.data.imagery.vectortile.mapbox.CommandInteger;
import org.openstreetmap.josm.data.imagery.vectortile.mapbox.Feature;
import org.openstreetmap.josm.data.imagery.vectortile.mapbox.GeometryTypes;
import org.openstreetmap.josm.data.imagery.vectortile.mapbox.Layer;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.protobuf.ProtobufParser;
import org.openstreetmap.josm.data.protobuf.ProtobufRecord;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.plugins.addressconflation.JsonSupport;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Reads address points from a PMTiles (version 3) archive of Mapbox vector tiles, by HTTP range
 * requests: the header and root directory once per URL and session, then the tiles at the
 * archive's maximum zoom that cover the view, decoded with JOSM's own vector tile parser.
 * The archive can be gigabytes, so it is never read whole.
 *
 * Points only: vector tiles cut polygons at tile edges, so parcels and outlines would need
 * stitching back together. A URL may name one layer of the archive after a {@code #}, as in
 * {@code https://example.org/nad-r24.pmtiles#addresses}; without one, every layer is read.
 *
 * @see <a href="https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md">PMTiles v3</a>
 */
public final class PmtilesClient {
    static final int HEADER_BYTES = 127;
    /** The header and root directory, which the format guarantees fit in the first 16 KiB. */
    private static final int FIRST_READ = 16_384;
    /** Root plus up to three levels of leaf directories, as the format allows. */
    private static final int MAX_DEPTH = 4;
    /** Leaf directories kept per archive; one is a few hundred KB at most. */
    private static final int MAX_LEAVES = 64;
    /** Tiles fetched at once. */
    private static final int THREADS = 6;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    static final int COMPRESSION_NONE = 1;
    static final int COMPRESSION_GZIP = 2;
    static final int TILE_TYPE_MVT = 1;

    /** Opened archives by file URL, for the session. */
    private static final Map<String, Archive> ARCHIVES = new ConcurrentHashMap<>();

    private PmtilesClient() {
    }

    // ---- download -----------------------------------------------------------------------------

    /** Fetch the points in the bounds and convert them through the source's conform, stopping at {@link FeatureLimit#MAX}. */
    public static DataSet download(FeatureSource source, Bounds bounds, ProgressMonitor monitor) throws IOException {
        return download(source, bounds, monitor, FeatureLimit.stop());
    }

    /** As {@link #download(FeatureSource, Bounds, ProgressMonitor)}, with {@code limit} deciding what happens at the feature limit. */
    public static DataSet download(FeatureSource source, Bounds bounds, ProgressMonitor monitor, FeatureLimit limit) throws IOException {
        if (source.getKind() != FeatureSource.Kind.ADDRESSES) {
            throw new IOException(tr("a PMTiles source can only provide addresses"));
        }
        List<JsonObject> features;
        try {
            try {
                features = fetchRaw(source, bounds, monitor, limit);
            } catch (ArchiveChangedException e) {
                // Republished under the same URL (nad-current moving to a new release): start over once.
                Logging.info(e.getMessage());
                features = fetchRaw(source, bounds, monitor, limit);
            }
        } catch (IOException e) {
            throw new IOException(EsriFeatureClient.describe(e), e);
        }
        return EsriFeatureClient.toDataSet(source, features);
    }

    /**
     * GeoJSON-shaped point features (properties as in the tiles) inside the bounds. Stopping at the
     * limit keeps whole tiles, so the gaps are whole tiles too; tiles keep arriving while the
     * mapper is asked.
     */
    static List<JsonObject> fetchRaw(FeatureSource source, Bounds bounds, ProgressMonitor monitor, FeatureLimit limit) throws IOException {
        Archive archive = archive(fileUrl(source.getUrl()));
        String layer = layerName(source.getUrl());
        // The maximum zoom is the complete one; lower zooms are thinned for display.
        int z = archive.header.maxZoom;
        List<int[]> tiles = tilesCovering(bounds, z);
        List<JsonObject> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(THREADS, tiles.size())),
                Utils.newThreadFactory("addressconflation-pmtiles-%d", Thread.NORM_PRIORITY));
        List<Future<List<JsonObject>>> futures = new ArrayList<>();
        try {
            CompletionService<List<JsonObject>> done = new ExecutorCompletionService<>(pool);
            for (int[] t : tiles) {
                futures.add(done.submit(() -> points(archive, z, t[0], t[1], layer, bounds)));
            }
            for (int i = 0; i < tiles.size(); i++) {
                if (monitor != null) {
                    if (monitor.isCanceled()) {
                        throw new IOException(tr("Cancelled"));
                    }
                    monitor.setCustomText(tr("{0}: tile {1} of {2}, {3} features so far", source.getName(), i + 1, tiles.size(), all.size()));
                }
                try {
                    all.addAll(done.take().get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(tr("Cancelled"), e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException) {
                        throw (IOException) e.getCause();
                    }
                    throw new IOException(e.getCause() != null ? e.getCause().toString() : e.toString(), e);
                }
                if (i + 1 < tiles.size() && limit.reached(source.getName(), all.size())) {
                    break;
                }
            }
        } finally {
            // Drop the tiles not started; let those in flight finish rather than interrupt their requests.
            for (Future<List<JsonObject>> f : futures) {
                f.cancel(false);
            }
            pool.shutdown();
        }
        return all;
    }

    /** The points of one tile that fall inside the bounds; none when the archive has no such tile. */
    private static List<JsonObject> points(Archive archive, int z, int x, int y, String layer, Bounds bounds) throws IOException {
        byte[] tile = archive.tile(z, x, y);
        List<JsonObject> out = new ArrayList<>();
        if (tile != null) {
            decode(tile, z, x, y, layer, bounds, out, null);
        }
        return out;
    }

    /** The {@code {x, y}} tiles at zoom {@code z} that cover the bounds. */
    static List<int[]> tilesCovering(Bounds b, int z) {
        int max = (1 << z) - 1;
        int x0 = clamp(tileX(b.getMinLon(), z), max);
        int x1 = clamp(tileX(b.getMaxLon(), z), max);
        int y0 = clamp(tileY(b.getMaxLat(), z), max);
        int y1 = clamp(tileY(b.getMinLat(), z), max);
        List<int[]> out = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                out.add(new int[] {x, y});
            }
        }
        return out;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    static int tileX(double lon, int z) {
        return (int) Math.floor((lon + 180) / 360 * (1 << z));
    }

    static int tileY(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }

    // ---- inspect ------------------------------------------------------------------------------

    /**
     * What the archive says about itself, for the source editor: the metadata's name and the
     * fields of its vector layer, the header's bounds, and whether it holds points (from the
     * metadata's tile statistics, or a sample tile at the centre). Re-reads the archive, so a
     * Check picks up a new release behind the same URL.
     */
    static ServiceInspector.Info inspect(String url) throws IOException {
        String file = fileUrl(url);
        ARCHIVES.remove(file);
        Archive archive = archive(file);
        JsonObject meta = archive.metadata();
        Map<String, List<String>> layers = vectorLayers(meta);
        String wanted = layerName(url);
        if (wanted == null && layers.size() > 1) {
            Map<String, String> choices = new LinkedHashMap<>();
            for (String id : layers.keySet()) {
                choices.put(file + "#" + id, id);
            }
            throw new ServiceInspector.NotALayerException(tr("this tileset has several layers, not one"), choices);
        }
        if (wanted != null && !layers.isEmpty() && !layers.containsKey(wanted)) {
            throw new IOException(tr("the tileset has no layer called {0}", wanted));
        }
        String layer = wanted != null ? wanted : layers.isEmpty() ? null : layers.keySet().iterator().next();
        Set<String> fields = new LinkedHashSet<>(layer == null ? new ArrayList<>() : layers.get(layer));

        ServiceInspector.Geometry geometry = statsGeometry(meta, layer);
        if (geometry == ServiceInspector.Geometry.UNKNOWN || fields.isEmpty()) {
            // A tile near the centre shows the geometry, and the fields when the metadata lists none.
            Header h = archive.header;
            int z = h.maxZoom;
            int x = clamp(tileX(h.centerLon, z), (1 << z) - 1);
            int y = clamp(tileY(h.centerLat, z), (1 << z) - 1);
            try {
                byte[] tile = archive.tile(z, x, y);
                if (tile != null) {
                    Set<GeometryTypes> seen = new LinkedHashSet<>();
                    List<JsonObject> sample = new ArrayList<>();
                    decode(tile, z, x, y, layer, null, sample, seen);
                    if (geometry == ServiceInspector.Geometry.UNKNOWN && !seen.isEmpty()) {
                        geometry = seen.contains(GeometryTypes.POLYGON) ? ServiceInspector.Geometry.POLYGONS
                                : seen.equals(Set.of(GeometryTypes.POINT)) ? ServiceInspector.Geometry.POINTS : ServiceInspector.Geometry.OTHER;
                    }
                    for (JsonObject f : sample) {
                        fields.addAll(f.getJsonObject("properties").keySet());
                    }
                }
            } catch (IOException e) {
                Logging.info("No sample tile from " + file + ": " + e.getMessage());
            }
        }
        if (geometry == ServiceInspector.Geometry.POLYGONS || geometry == ServiceInspector.Geometry.OTHER) {
            throw new IOException(tr("this tileset holds outlines or lines; only address points can be read from PMTiles"));
        }
        String title = meta.getString("name", "").trim();
        if (title.isEmpty()) {
            title = layer;
        }
        return new ServiceInspector.Info(title, geometry, new ArrayList<>(fields), archive.header.bounds());
    }

    /** Layer id to its field names, from the metadata's {@code vector_layers}, in the archive's order. */
    static Map<String, List<String>> vectorLayers(JsonObject meta) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        JsonValue v = meta.get("vector_layers");
        if (v == null || v.getValueType() != JsonValue.ValueType.ARRAY) {
            return out;
        }
        for (JsonValue l : v.asJsonArray()) {
            if (l.getValueType() != JsonValue.ValueType.OBJECT || l.asJsonObject().getString("id", "").isEmpty()) {
                continue;
            }
            JsonObject layer = l.asJsonObject();
            JsonValue f = layer.get("fields");
            out.put(layer.getString("id"), f != null && f.getValueType() == JsonValue.ValueType.OBJECT
                    ? new ArrayList<>(f.asJsonObject().keySet()) : new ArrayList<>());
        }
        return out;
    }

    /** The geometry tippecanoe's {@code tilestats} records for the layer, or UNKNOWN. */
    private static ServiceInspector.Geometry statsGeometry(JsonObject meta, String layer) {
        try {
            for (JsonValue v : meta.getJsonObject("tilestats").getJsonArray("layers")) {
                JsonObject l = v.asJsonObject();
                if (layer == null || layer.equals(l.getString("layer", null))) {
                    String g = l.getString("geometry", "");
                    return "Point".equals(g) ? ServiceInspector.Geometry.POINTS : "Polygon".equals(g) ? ServiceInspector.Geometry.POLYGONS
                            : g.isEmpty() ? ServiceInspector.Geometry.UNKNOWN : ServiceInspector.Geometry.OTHER;
                }
            }
        } catch (RuntimeException e) {
            Logging.trace(e);
        }
        return ServiceInspector.Geometry.UNKNOWN;
    }

    // ---- URLs ---------------------------------------------------------------------------------

    /** The archive's URL, without the {@code #layer}. */
    static String fileUrl(String url) {
        int hash = url.indexOf('#');
        return (hash < 0 ? url : url.substring(0, hash)).trim();
    }

    /** The layer the URL names after {@code #}, or null for every layer. */
    static String layerName(String url) {
        int hash = url.indexOf('#');
        if (hash < 0 || hash == url.length() - 1) {
            return null;
        }
        return URLDecoder.decode(url.substring(hash + 1).trim(), StandardCharsets.UTF_8);
    }

    // ---- the archive --------------------------------------------------------------------------

    /** The archive at the URL, opened once per session. */
    static Archive archive(String fileUrl) throws IOException {
        Archive a = ARCHIVES.get(fileUrl);
        if (a == null) {
            a = Archive.open(fileUrl);
            ARCHIVES.put(fileUrl, a);
        }
        return a;
    }

    /** The fixed-size header at the start of the archive. */
    static final class Header {
        long rootOffset;
        long rootLength;
        long metadataOffset;
        long metadataLength;
        long leafOffset;
        long tileDataOffset;
        int internalCompression;
        int tileCompression;
        int tileType;
        int minZoom;
        int maxZoom;
        double minLon;
        double minLat;
        double maxLon;
        double maxLat;
        double centerLon;
        double centerLat;

        static Header parse(byte[] b) throws IOException {
            if (b.length < HEADER_BYTES || !"PMTiles".equals(new String(b, 0, 7, StandardCharsets.US_ASCII))) {
                throw new IOException(tr("this is not a PMTiles file"));
            }
            if (b[7] != 3) {
                throw new IOException(tr("this is PMTiles version {0}; only version 3 can be read", b[7]));
            }
            ByteBuffer bb = ByteBuffer.wrap(b, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            Header h = new Header();
            h.rootOffset = bb.getLong(8);
            h.rootLength = bb.getLong(16);
            h.metadataOffset = bb.getLong(24);
            h.metadataLength = bb.getLong(32);
            h.leafOffset = bb.getLong(40);
            h.tileDataOffset = bb.getLong(56);
            h.internalCompression = b[97] & 0xff;
            h.tileCompression = b[98] & 0xff;
            h.tileType = b[99] & 0xff;
            h.minZoom = b[100] & 0xff;
            h.maxZoom = b[101] & 0xff;
            h.minLon = bb.getInt(102) / 1e7;
            h.minLat = bb.getInt(106) / 1e7;
            h.maxLon = bb.getInt(110) / 1e7;
            h.maxLat = bb.getInt(114) / 1e7;
            h.centerLon = bb.getInt(119) / 1e7;
            h.centerLat = bb.getInt(123) / 1e7;
            if (h.tileType != TILE_TYPE_MVT) {
                throw new IOException(tr("this tileset holds images, not vector tiles"));
            }
            checkCompression(h.internalCompression);
            checkCompression(h.tileCompression);
            if (h.maxZoom > 26 || h.maxZoom < h.minZoom) {
                throw new IOException(tr("this PMTiles file has a broken header (zoom {0} to {1})", h.minZoom, h.maxZoom));
            }
            return h;
        }

        private static void checkCompression(int c) throws IOException {
            if (c != COMPRESSION_NONE && c != COMPRESSION_GZIP) {
                throw new IOException(tr("this PMTiles file uses {0} compression; only gzip can be read",
                        c == 3 ? "brotli" : c == 4 ? "zstd" : tr("unknown")));
            }
        }

        /** The archive's coverage, or null when the header's bounds are nonsense. */
        Bounds bounds() {
            if (!(minLat >= -90 && maxLat <= 90 && minLon >= -180 && maxLon <= 180 && minLat < maxLat && minLon < maxLon)) {
                return null;
            }
            return new Bounds(minLat, minLon, maxLat, maxLon);
        }
    }

    /** A decoded directory: entries sorted by tile id. A run length of 0 marks a leaf directory. */
    static final class Directory {
        final long[] tileIds;
        final long[] offsets;
        final long[] lengths;
        final long[] runLengths;

        Directory(int n) {
            tileIds = new long[n];
            offsets = new long[n];
            lengths = new long[n];
            runLengths = new long[n];
        }

        static Directory parse(byte[] b) throws IOException {
            Varints in = new Varints(b);
            long n = in.next();
            // Every entry takes at least four bytes, so a bigger count is a broken directory.
            if (n < 0 || n > b.length) {
                throw new IOException(tr("this PMTiles file has a broken directory"));
            }
            Directory d = new Directory((int) n);
            long id = 0;
            for (int i = 0; i < n; i++) {
                id += in.next();
                d.tileIds[i] = id;
            }
            for (int i = 0; i < n; i++) {
                d.runLengths[i] = in.next();
            }
            for (int i = 0; i < n; i++) {
                d.lengths[i] = in.next();
            }
            for (int i = 0; i < n; i++) {
                long v = in.next();
                // 0 means "right after the previous entry", saving the bytes of clustered archives.
                d.offsets[i] = v == 0 && i > 0 ? d.offsets[i - 1] + d.lengths[i - 1] : v - 1;
            }
            return d;
        }

        /** The entry holding the tile or the leaf directory that may, or -1. */
        int find(long tileId) {
            int lo = 0;
            int hi = tileIds.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (tileIds[mid] < tileId) {
                    lo = mid + 1;
                } else if (tileIds[mid] > tileId) {
                    hi = mid - 1;
                } else {
                    return mid;
                }
            }
            // The last entry before the tile: a leaf directory, or a run of identical tiles reaching it.
            if (hi >= 0 && (runLengths[hi] == 0 || tileId - tileIds[hi] < runLengths[hi])) {
                return hi;
            }
            return -1;
        }
    }

    /** Unsigned LEB128 varints, as directories are written. */
    private static final class Varints {
        private final byte[] b;
        private int pos;

        Varints(byte[] b) {
            this.b = b;
        }

        long next() throws IOException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= b.length) {
                    throw new IOException(tr("this PMTiles file has a broken directory"));
                }
                byte x = b[pos++];
                v |= (long) (x & 0x7f) << shift;
                if (x >= 0) {
                    return v;
                }
            }
            throw new IOException(tr("this PMTiles file has a broken directory"));
        }
    }

    /**
     * The tile's id: the tiles of all lower zooms, then its place along the Hilbert curve that
     * orders this zoom's tiles.
     */
    static long tileId(int z, int x, int y) {
        if (z < 0 || z > 26 || x < 0 || y < 0 || x >= 1 << z || y >= 1 << z) {
            throw new IllegalArgumentException("No tile " + z + "/" + x + "/" + y);
        }
        long n = 1L << z;
        long id = (n * n - 1) / 3;
        long tx = x;
        long ty = y;
        for (long s = n / 2; s > 0; s /= 2) {
            int rx = (tx & s) > 0 ? 1 : 0;
            int ry = (ty & s) > 0 ? 1 : 0;
            id += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) {
                    tx = n - 1 - tx;
                    ty = n - 1 - ty;
                }
                long t = tx;
                tx = ty;
                ty = t;
            }
        }
        return id;
    }

    /** An opened archive: header and root directory, plus the leaf directories read so far. */
    static final class Archive {
        final String url;
        final Header header;
        final Directory root;
        /** The ETag of the first read, to notice the file being replaced mid-session; may be null. */
        final String etag;
        private final Map<Long, Directory> leaves = new ConcurrentHashMap<>();

        private Archive(String url, Header header, Directory root, String etag) {
            this.url = url;
            this.header = header;
            this.root = root;
            this.etag = etag;
        }

        static Archive open(String url) throws IOException {
            Range first = readRange(url, 0, FIRST_READ, null, true);
            Header h = Header.parse(first.bytes);
            byte[] rootBytes;
            if (h.rootOffset + h.rootLength <= first.bytes.length) {
                rootBytes = Arrays.copyOfRange(first.bytes, (int) h.rootOffset, (int) (h.rootOffset + h.rootLength));
            } else {
                rootBytes = readRange(url, h.rootOffset, length(h.rootLength), first.etag, false).bytes;
            }
            return new Archive(url, h, Directory.parse(decompress(rootBytes, h.internalCompression)), first.etag);
        }

        /** The decoded tile bytes, or null when the archive has no tile there (nothing at that spot). */
        byte[] tile(int z, int x, int y) throws IOException {
            long id = tileId(z, x, y);
            Directory d = root;
            for (int depth = 0; depth < MAX_DEPTH; depth++) {
                int i = d.find(id);
                if (i < 0) {
                    return null;
                }
                if (d.runLengths[i] > 0) {
                    byte[] raw = read(header.tileDataOffset + d.offsets[i], length(d.lengths[i]));
                    return decompress(raw, header.tileCompression);
                }
                d = leaf(d.offsets[i], d.lengths[i]);
            }
            throw new IOException(tr("this PMTiles file has directories nested too deep"));
        }

        private Directory leaf(long offset, long length) throws IOException {
            Directory d = leaves.get(offset);
            if (d == null) {
                d = Directory.parse(decompress(read(header.leafOffset + offset, length(length)), header.internalCompression));
                if (leaves.size() >= MAX_LEAVES) {
                    leaves.clear();
                }
                leaves.put(offset, d);
            }
            return d;
        }

        /** The decoded metadata JSON; empty when the archive has none. */
        JsonObject metadata() throws IOException {
            if (header.metadataLength == 0) {
                return JsonValue.EMPTY_JSON_OBJECT;
            }
            byte[] b = decompress(read(header.metadataOffset, length(header.metadataLength)), header.internalCompression);
            try (JsonReader r = JsonSupport.JSON.createReader(new StringReader(new String(b, StandardCharsets.UTF_8)))) {
                return r.readObject();
            } catch (JsonException | IllegalStateException e) {
                Logging.info("Unreadable PMTiles metadata in " + url + ": " + e.getMessage());
                return JsonValue.EMPTY_JSON_OBJECT;
            }
        }

        private byte[] read(long offset, int length) throws IOException {
            return readRange(url, offset, length, etag, false).bytes;
        }
    }

    private static int length(long l) throws IOException {
        if (l < 0 || l > Integer.MAX_VALUE - 8) {
            throw new IOException(tr("this PMTiles file has a broken directory"));
        }
        return (int) l;
    }

    /** The file behind the URL is not the one whose header we read. */
    static final class ArchiveChangedException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveChangedException(String url) {
            super("PMTiles file changed on the server: " + url);
        }
    }

    private static final class Range {
        final byte[] bytes;
        final String etag;

        Range(byte[] bytes, String etag) {
            this.bytes = bytes;
            this.etag = etag;
        }
    }

    /**
     * {@code length} bytes from {@code offset}. The server must honour the range: an answer with
     * the whole file is refused unread, since it can be gigabytes. {@code mayBeShort} allows
     * fewer bytes, for the opening read of a file smaller than 16 KiB.
     */
    private static Range readRange(String url, long offset, int length, String expectedEtag, boolean mayBeShort) throws IOException {
        if (length == 0) {
            return new Range(new byte[0], expectedEtag);
        }
        Logging.debug("PMTiles range " + offset + "+" + length + ": " + url);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException(tr("not a valid web address"), e);
        }
        HttpClient.Response resp = HttpClient.create(uri.toURL())
                .setHeader("Range", "bytes=" + offset + "-" + (offset + length - 1))
                // Ranges count bytes of the stored file; a compressed answer would shift them.
                .setHeader("Accept-Encoding", "identity")
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS)
                .connect();
        try {
            int code = resp.getResponseCode();
            if (code == 416 && !mayBeShort) {
                // Past the end of the file whose directory we hold: it was replaced by a smaller one.
                ARCHIVES.remove(url);
                throw new ArchiveChangedException(url);
            }
            boolean wholeSmallFile = code == 200 && offset == 0 && mayBeShort && resp.getContentLength() >= 0
                    && resp.getContentLength() <= length;
            if (code == 200 && !wholeSmallFile) {
                throw new IOException(tr("the server does not support range requests, which reading a PMTiles file needs"));
            }
            if (code != 206 && !wholeSmallFile) {
                throw new IOException(code == 404 || code == 403 ? tr("file not found (HTTP {0})", code)
                        : tr("server error (HTTP {0})", code));
            }
            String etag = resp.getHeaderField("ETag");
            if (expectedEtag != null && etag != null && !Objects.equals(expectedEtag, etag)) {
                ARCHIVES.remove(url);
                throw new ArchiveChangedException(url);
            }
            byte[] b;
            try (InputStream in = resp.getContent()) {
                b = in.readNBytes(length);
            }
            if (b.length < length && !mayBeShort) {
                throw new IOException(tr("the server sent {0} of {1} bytes", b.length, length));
            }
            return new Range(b, etag);
        } finally {
            resp.disconnect();
        }
    }

    static byte[] decompress(byte[] b, int compression) throws IOException {
        if (compression == COMPRESSION_NONE) {
            return b;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return in.readAllBytes();
        }
    }

    // ---- vector tiles -------------------------------------------------------------------------

    /**
     * Add the tile's points to {@code out} as GeoJSON-shaped features. Points in the tile's
     * buffer belong to its neighbour and are skipped, so no point is read twice; with
     * {@code bounds}, so are points outside it. {@code geometries} collects every geometry type
     * met, when not null.
     */
    static void decode(byte[] mvt, int z, int x, int y, String layerName, Bounds bounds, List<JsonObject> out,
            Set<GeometryTypes> geometries) throws IOException {
        List<ProtobufRecord> records;
        try (ProtobufParser parser = new ProtobufParser(mvt)) {
            records = new ArrayList<>(parser.allRecords());
        }
        double n = 1 << z;
        for (ProtobufRecord r : records) {
            if (r.getField() != Layer.LAYER_FIELD) {
                continue;
            }
            Layer layer;
            try {
                layer = new Layer(r.getBytes());
            } catch (IllegalArgumentException e) {
                throw new IOException(tr("unreadable vector tile {0}/{1}/{2}: {3}", z, x, y, e.getMessage()), e);
            }
            if (layerName != null && !layerName.equals(layer.getName())) {
                continue;
            }
            int extent = layer.getExtent();
            for (Feature f : layer.getFeatures()) {
                if (geometries != null) {
                    geometries.add(f.getGeometryType());
                }
                if (f.getGeometryType() != GeometryTypes.POINT) {
                    continue;
                }
                JsonObject props = null;
                // JOSM keeps coordinate deltas as shorts, plenty for the usual extent of 4096.
                int px = 0;
                int py = 0;
                for (CommandInteger c : f.getGeometry()) {
                    if (c.getType() != Command.MoveTo) {
                        continue;
                    }
                    short[] ops = c.getOperations();
                    for (int k = 0; k + 1 < ops.length; k += 2) {
                        px += ops[k];
                        py += ops[k + 1];
                        if (px < 0 || py < 0 || px >= extent || py >= extent) {
                            continue;
                        }
                        double lon = (x + px / (double) extent) / n * 360 - 180;
                        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + py / (double) extent) / n))));
                        if (bounds != null && !bounds.contains(new LatLon(lat, lon))) {
                            continue;
                        }
                        if (props == null) {
                            props = properties(f);
                        }
                        out.add(point(props, lon, lat));
                    }
                }
            }
        }
    }

    private static JsonObject properties(Feature f) {
        JsonObjectBuilder p = JsonSupport.JSON.createObjectBuilder();
        if (f.getTags() != null) {
            for (Map.Entry<String, String> t : f.getTags().entrySet()) {
                p.add(t.getKey(), t.getValue());
            }
        }
        return p.build();
    }

    private static JsonObject point(JsonObject props, double lon, double lat) {
        return JsonSupport.JSON.createObjectBuilder()
                .add("type", "Feature")
                .add("properties", props)
                .add("geometry", JsonSupport.JSON.createObjectBuilder().add("type", "Point")
                        .add("coordinates", JsonSupport.JSON.createArrayBuilder().add(lon).add(lat)))
                .build();
    }
}
