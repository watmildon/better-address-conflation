// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Mapbox Vector Tile reader for OpenAddresses' coverage tiles: feature
 * ids, tags and geometry. JOSM has an MVT reader, but it keeps coordinates in
 * 16-bit shorts, and the coverage server sends whole states unclipped, far past
 * that range. Coordinates here are longs.
 */
final class CoverageTile {
    private static final int POINT = 1;
    private static final int POLYGON = 3;

    /** One feature, with geometry in tile units (0..extent, y down). */
    static final class Feature {
        final long id;
        final Map<String, Object> tags;
        final int type;
        final int extent;
        /** Polygon rings, even-odd; null for other types. */
        final Path2D.Double path;
        /** Point coordinates as x, y pairs; empty for other types. */
        final List<long[]> points;

        Feature(long id, Map<String, Object> tags, int type, int extent, Path2D.Double path, List<long[]> points) {
            this.id = id;
            this.tags = tags;
            this.type = type;
            this.extent = extent;
            this.path = path;
            this.points = points;
        }

        boolean isPoint() {
            return type == POINT;
        }

        /** Does the geometry touch the rectangle, given in 0..1 tile units? */
        boolean touches(Rectangle2D unit) {
            Rectangle2D r = new Rectangle2D.Double(unit.getX() * extent, unit.getY() * extent,
                    unit.getWidth() * extent, unit.getHeight() * extent);
            return path != null && path.intersects(r);
        }
    }

    private CoverageTile() {
    }

    static List<Feature> read(byte[] tile) throws IOException {
        List<Feature> out = new ArrayList<>();
        Reader r = new Reader(tile, 0, tile.length);
        while (r.more()) {
            int key = (int) r.varint();
            if (key >>> 3 == 3 && (key & 7) == 2) {
                readLayer(r.sub(), out);
            } else {
                r.skip(key & 7);
            }
        }
        return out;
    }

    private static void readLayer(Reader r, List<Feature> out) throws IOException {
        List<String> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Reader> features = new ArrayList<>();
        int extent = 4096;
        while (r.more()) {
            int key = (int) r.varint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 2 && wire == 2) {
                features.add(r.sub());
            } else if (field == 3 && wire == 2) {
                keys.add(r.sub().string());
            } else if (field == 4 && wire == 2) {
                values.add(readValue(r.sub()));
            } else if (field == 5 && wire == 0) {
                extent = (int) r.varint();
            } else {
                r.skip(wire);
            }
        }
        for (Reader f : features) {
            out.add(readFeature(f, keys, values, extent));
        }
    }

    private static Object readValue(Reader r) throws IOException {
        Object v = null;
        while (r.more()) {
            int key = (int) r.varint();
            switch (key >>> 3) {
            case 1:
                v = r.sub().string();
                break;
            case 2:
                v = Float.intBitsToFloat((int) r.fixed(4));
                break;
            case 3:
                v = Double.longBitsToDouble(r.fixed(8));
                break;
            case 4:
            case 5:
                v = r.varint();
                break;
            case 6:
                v = zigzag(r.varint());
                break;
            case 7:
                v = r.varint() != 0;
                break;
            default:
                r.skip(key & 7);
            }
        }
        return v;
    }

    private static Feature readFeature(Reader r, List<String> keys, List<Object> values, int extent) throws IOException {
        long id = 0;
        int type = 0;
        Map<String, Object> tags = new HashMap<>();
        long[] geom = new long[0];
        while (r.more()) {
            int key = (int) r.varint();
            int field = key >>> 3;
            int wire = key & 7;
            if (field == 1 && wire == 0) {
                id = r.varint();
            } else if (field == 3 && wire == 0) {
                type = (int) r.varint();
            } else if (field == 2 && wire == 2) {
                long[] t = r.sub().packed();
                for (int i = 0; i + 1 < t.length; i += 2) {
                    if (t[i] < keys.size() && t[i + 1] < values.size()) {
                        tags.put(keys.get((int) t[i]), values.get((int) t[i + 1]));
                    }
                }
            } else if (field == 4 && wire == 2) {
                geom = r.sub().packed();
            } else {
                r.skip(wire);
            }
        }
        Path2D.Double path = type == POLYGON ? new Path2D.Double(Path2D.WIND_EVEN_ODD) : null;
        List<long[]> points = new ArrayList<>();
        long x = 0;
        long y = 0;
        int i = 0;
        while (i < geom.length) {
            int cmd = (int) (geom[i] & 7);
            int count = (int) (geom[i] >>> 3);
            i++;
            if (cmd == 7) {
                if (path != null) {
                    path.closePath();
                }
                continue;
            }
            for (int k = 0; k < count && i + 1 < geom.length; k++, i += 2) {
                x += zigzag(geom[i]);
                y += zigzag(geom[i + 1]);
                if (type == POINT) {
                    points.add(new long[] {x, y});
                } else if (path != null) {
                    if (cmd == 1) {
                        path.moveTo(x, y);
                    } else {
                        path.lineTo(x, y);
                    }
                }
            }
        }
        return new Feature(id, tags, type, extent, path, points);
    }

    private static long zigzag(long n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /** Protocol buffer cursor over a byte range. */
    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int start, int end) {
            this.b = b;
            this.pos = start;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new IOException("Truncated vector tile");
                }
                byte c = b[pos++];
                result |= (long) (c & 0x7f) << shift;
                if ((c & 0x80) == 0) {
                    return result;
                }
            }
            throw new IOException("Malformed varint in vector tile");
        }

        long fixed(int bytes) throws IOException {
            if (pos + bytes > end) {
                throw new IOException("Truncated vector tile");
            }
            long v = 0;
            for (int k = 0; k < bytes; k++) {
                v |= (long) (b[pos++] & 0xff) << (8 * k);
            }
            return v;
        }

        Reader sub() throws IOException {
            int len = (int) varint();
            if (len < 0 || pos + len > end) {
                throw new IOException("Truncated vector tile");
            }
            Reader r = new Reader(b, pos, pos + len);
            pos += len;
            return r;
        }

        String string() {
            String s = new String(b, pos, end - pos, StandardCharsets.UTF_8);
            pos = end;
            return s;
        }

        long[] packed() throws IOException {
            List<Long> vs = new ArrayList<>();
            while (more()) {
                vs.add(varint());
            }
            long[] a = new long[vs.size()];
            for (int k = 0; k < a.length; k++) {
                a[k] = vs.get(k);
            }
            return a;
        }

        void skip(int wire) throws IOException {
            switch (wire) {
            case 0:
                varint();
                break;
            case 1:
                fixed(8);
                break;
            case 2:
                sub();
                break;
            case 5:
                fixed(4);
                break;
            default:
                throw new IOException("Unsupported wire type " + wire + " in vector tile");
            }
        }
    }
}
