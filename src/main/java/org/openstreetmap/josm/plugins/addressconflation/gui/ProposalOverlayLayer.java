// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.swing.Action;
import javax.swing.Icon;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.model.AddressGroup;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Cell;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Paints the analysis on the map: cell outlines, a link from every address
 * point to the building it would go to (coloured by bucket), and a heavier
 * stroke for the proposals selected in the panel. Never saved or uploaded.
 */
public class ProposalOverlayLayer extends Layer {
    private static final Map<Bucket, Color> COLORS = new EnumMap<>(Bucket.class);
    private static final Color CELL_COLOR = new Color(120, 120, 120, 110);
    private static final Color SELECTED_COLOR = new Color(255, 255, 0, 220);
    private static final Stroke CELL_STROKE = new BasicStroke(1f);
    private static final Stroke LINK_STROKE = new BasicStroke(2f);
    private static final Stroke SELECTED_STROKE = new BasicStroke(4f);
    /** Do not draw cells when the view is wider than this, in degrees. */
    private static final double CELL_MAX_VIEW_DEGREES = 0.03;

    /*
     * Colour says what the mapper has to do, not which bucket: on a map any two colours can
     * sit side by side, and three is the most that stay apart for colour-blind mappers
     * (validated all-pairs against JOSM's black background: worst deuteranopia OKLab
     * difference 9.4, normal vision 20.9, all over 3:1 contrast). The panel names the bucket,
     * and its bucket headings carry the same colours as a legend.
     */
    /** Apply, in bulk if you like: green. */
    static final Color APPLY = new Color(0x199e70);
    /** Look first, then apply or pick: orange. */
    static final Color LOOK = new Color(0xd95926);
    /** Resolve by hand: blue. */
    static final Color BY_HAND = new Color(0x3987e5);
    /** Casing around address dots, so they hold up on aerial imagery. */
    private static final Color CASING = new Color(0, 0, 0, 200);

    static {
        COLORS.put(Bucket.CLEAN, APPLY);
        COLORS.put(Bucket.MULTI_ADDRESS_BUILDING, APPLY);
        COLORS.put(Bucket.REVIEW, LOOK);
        COLORS.put(Bucket.NO_BUILDING, LOOK);
        COLORS.put(Bucket.AMBIGUOUS_BUILDING, LOOK);
        COLORS.put(Bucket.EXISTING_ADDRESS, BY_HAND);
        COLORS.put(Bucket.DUPLICATE, BY_HAND);
        COLORS.put(Bucket.OUTSIDE_CELLS, BY_HAND);
    }

    private AnalysisResult result;
    private final Set<Proposal> selected = new HashSet<>();
    private final Set<Proposal> hidden = new HashSet<>();

    public ProposalOverlayLayer() {
        super(tr("Better Address Conflation overlay"));
    }

    public void setResult(AnalysisResult result) {
        this.result = result;
        selected.clear();
        hidden.clear();
        invalidate();
    }

    public void setSelected(Collection<Proposal> proposals) {
        selected.clear();
        selected.addAll(proposals);
        invalidate();
    }

    /** Stop drawing a proposal (it was applied). */
    public void hide(Proposal p) {
        hidden.add(p);
        invalidate();
    }

    public void unhide(Proposal p) {
        hidden.remove(p);
        invalidate();
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        if (result == null) {
            return;
        }
        LocalProjection proj = result.getProjection();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Envelope view = new Envelope(proj.toXY(bbox.getMin()), proj.toXY(bbox.getMax()));

        if (bbox.getWidth() < CELL_MAX_VIEW_DEGREES) {
            g.setColor(CELL_COLOR);
            g.setStroke(CELL_STROKE);
            for (Cell cell : result.getCells()) {
                Geometry geom = cell.getGeometry();
                if (!geom.getEnvelopeInternal().intersects(view)) {
                    continue;
                }
                for (int i = 0; i < geom.getNumGeometries(); i++) {
                    Geometry part = geom.getGeometryN(i);
                    if (part instanceof Polygon) {
                        drawRing(g, mv, proj, ((Polygon) part).getExteriorRing().getCoordinates());
                    }
                }
            }
        }

        for (Proposal p : result.getProposals()) {
            if (hidden.contains(p) || selected.contains(p)) {
                continue;
            }
            drawProposal(g, mv, proj, view, p, false);
        }
        for (Proposal p : selected) {
            if (!hidden.contains(p)) {
                drawProposal(g, mv, proj, view, p, true);
            }
        }
    }

    private void drawProposal(Graphics2D g, MapView mv, LocalProjection proj, Envelope view, Proposal p, boolean highlight) {
        Color color = COLORS.getOrDefault(p.getBucket(), Color.BLACK);
        Point targetPt = null;
        if (p.getTarget() != null) {
            Coordinate c = p.getTarget().getGeometry().getInteriorPoint().getCoordinate();
            targetPt = mv.getPoint(proj.toLatLon(c));
        }
        for (AddressGroup group : p.getAddresses()) {
            LatLon ll = group.getPosition();
            if (ll == null) {
                continue;
            }
            Coordinate a = proj.toXY(ll);
            if (!view.contains(a) && (targetPt == null || !view.intersects(new Envelope(a, p.getTarget().getGeometry().getCentroid().getCoordinate())))) {
                continue;
            }
            Point from = mv.getPoint(ll);
            if (highlight) {
                g.setColor(SELECTED_COLOR);
                g.setStroke(SELECTED_STROKE);
                if (targetPt != null) {
                    g.drawLine(from.x, from.y, targetPt.x, targetPt.y);
                }
                g.fillOval(from.x - 6, from.y - 6, 12, 12);
            }
            g.setColor(color);
            g.setStroke(LINK_STROKE);
            if (targetPt != null) {
                g.drawLine(from.x, from.y, targetPt.x, targetPt.y);
            }
            g.fillOval(from.x - 4, from.y - 4, 8, 8);
            g.setColor(CASING);
            g.setStroke(CELL_STROKE);
            g.drawOval(from.x - 5, from.y - 5, 10, 10);
        }
        if (targetPt != null) {
            g.setColor(color);
            g.drawOval(targetPt.x - 5, targetPt.y - 5, 10, 10);
            if (p.getTarget().isHint()) {
                // hint footprints are invisible otherwise: outline them
                Geometry hg = p.getTarget().getGeometry();
                for (int i = 0; i < hg.getNumGeometries(); i++) {
                    if (hg.getGeometryN(i) instanceof Polygon) {
                        drawRing(g, mv, proj, ((Polygon) hg.getGeometryN(i)).getExteriorRing().getCoordinates());
                    }
                }
            }
        }
    }

    private static void drawRing(Graphics2D g, MapView mv, LocalProjection proj, Coordinate[] ring) {
        int n = ring.length;
        int[] xs = new int[n];
        int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            Point pt = mv.getPoint(proj.toLatLon(ring[i]));
            xs[i] = pt.x;
            ys[i] = pt.y;
        }
        g.drawPolyline(xs, ys, n);
    }

    @Override
    public Icon getIcon() {
        return ImageProvider.get("dialogs", "address-conflation");
    }

    @Override
    public String getToolTipText() {
        return result == null ? tr("No analysis yet") : tr("{0} proposals", result.getProposals().size());
    }

    @Override
    public void mergeFrom(Layer from) {
        // not mergeable
    }

    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        if (result == null) {
            return;
        }
        for (Proposal p : result.getProposals()) {
            for (AddressGroup g : p.getAddresses()) {
                if (g.getPosition() != null) {
                    v.visit(g.getPosition());
                }
            }
        }
    }

    @Override
    public Object getInfoComponent() {
        return getToolTipText();
    }

    @Override
    public Action[] getMenuEntries() {
        return new Action[] {
            LayerListDialog.getInstance().createShowHideLayerAction(),
            LayerListDialog.getInstance().createDeleteLayerAction(),
            SeparatorLayerAction.INSTANCE,
            new LayerListPopup.InfoAction(this)};
    }

    @Override
    public boolean isSavable() {
        return false;
    }

    public static Map<Bucket, Color> colors() {
        return Collections.unmodifiableMap(COLORS);
    }
}
