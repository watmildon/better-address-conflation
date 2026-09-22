// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Hand-built geometry for engine tests. Everything is placed around a fixed
 * origin (Glendale) with offsets in metres, so tests read like sketches.
 */
public final class Fixtures {
    public static final double LAT0 = 33.565;
    public static final double LON0 = -112.175;
    private static final double KY = 110540.0;
    private static final double KX = 111320.0 * Math.cos(Math.toRadians(LAT0));

    private Fixtures() {
    }

    /** Lat/lon at (x, y) metres east/north of the origin. */
    public static LatLon at(double x, double y) {
        return new LatLon(LAT0 + y / KY, LON0 + x / KX);
    }

    /** A node with tags given as "k=v" strings. */
    public static Node node(DataSet ds, double x, double y, String... tags) {
        Node n = new Node(at(x, y));
        ds.addPrimitive(n);
        tag(n, tags);
        return n;
    }

    /** Axis-aligned closed square/rectangle way centred at (cx, cy), w by h metres. */
    public static Way rect(DataSet ds, double cx, double cy, double w, double h, String... tags) {
        return polygon(ds, tags, at(cx - w / 2, cy - h / 2), at(cx + w / 2, cy - h / 2), at(cx + w / 2, cy + h / 2), at(cx - w / 2, cy + h / 2));
    }

    /** Closed way through the given corners. */
    public static Way polygon(DataSet ds, String[] tags, LatLon... corners) {
        List<Node> nodes = new ArrayList<>();
        for (LatLon c : corners) {
            Node n = new Node(c);
            ds.addPrimitive(n);
            nodes.add(n);
        }
        nodes.add(nodes.get(0));
        Way w = new Way();
        w.setNodes(nodes);
        ds.addPrimitive(w);
        tag(w, tags);
        return w;
    }

    /** Multipolygon relation with one outer and any number of inner ways (untagged members). */
    public static Relation multipolygon(DataSet ds, Way outer, List<Way> inners, String... tags) {
        Relation r = new Relation();
        r.addMember(new RelationMember("outer", outer));
        for (Way in : inners) {
            r.addMember(new RelationMember("inner", in));
        }
        ds.addPrimitive(r);
        tag(r, "type=multipolygon");
        tag(r, tags);
        return r;
    }

    public static void tag(org.openstreetmap.josm.data.osm.OsmPrimitive p, String... tags) {
        for (String t : tags) {
            int i = t.indexOf('=');
            p.put(t.substring(0, i), t.substring(i + 1));
        }
    }

    public static String[] addr(String number, String street) {
        return new String[] {"addr:housenumber=" + number, "addr:street=" + street, "addr:city=Glendale", "addr:postcode=85302"};
    }

    public static String[] concat(String[] a, String... b) {
        List<String> l = new ArrayList<>(Arrays.asList(a));
        l.addAll(Arrays.asList(b));
        return l.toArray(new String[0]);
    }

    /** True when every entry of expected is present on the primitive. */
    public static boolean hasTags(org.openstreetmap.josm.data.osm.OsmPrimitive p, Map<String, String> expected) {
        for (Map.Entry<String, String> e : expected.entrySet()) {
            if (!e.getValue().equals(p.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
