// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.locationtech.jts.geom.Coordinate;
import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandAddedEvent;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueueCleanedEvent;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandQueuePreciseListener;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandRedoneEvent;
import org.openstreetmap.josm.data.UndoRedoHandler.CommandUndoneEvent;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.CellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.RoadClippedVoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.ShiftEstimator;
import org.openstreetmap.josm.plugins.addressconflation.io.DownloadSourceAction;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Side panel: pick the address layer and the parcel layer (or Voronoi), run
 * the analysis, review proposals grouped by bucket, apply them.
 */
public class AddressConflationDialog extends ToggleDialog implements LayerChangeListener, CommandQueuePreciseListener {

    private static final String VORONOI = "voronoi";

    private final JComboBox<OsmDataLayer> addressLayerBox = new JComboBox<>();
    private final JComboBox<Object> cellSourceBox = new JComboBox<>();
    private final JLabel summary = new JLabel(" ");
    private final JButton shiftButton = new JButton();
    private final JCheckBox overlayBox = new JCheckBox(tr("Overlay"), true);
    private final JCheckBox roadClipBox = new JCheckBox(tr("Clip Voronoi by roads"), true);
    private ProposalOverlayLayer overlay;
    /** Commands we issued, so undo can bring the proposal back. */
    private final Map<Command, Proposal> commandProposals = new HashMap<>();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode(tr("Proposals"));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);

    private final AbstractAction analyzeAction;
    private final AbstractAction applyAction;
    private final AbstractAction applyBucketAction;
    private final AbstractAction zoomAction;
    private final AbstractAction downloadAction = new DownloadSourceAction();

    private AnalysisResult result;
    private OsmDataLayer targetLayer;
    private OsmDataLayer sourceLayer;
    private ConflationSettings settings;
    private boolean updatingSelection;

    public AddressConflationDialog() {
        super(tr("Address Conflation"), "address-conflation", tr("Match address points to buildings"),
                Shortcut.registerShortcut("subwindow:addressconflation", tr("Windows: {0}", tr("Address Conflation")),
                        KeyEvent.VK_A, Shortcut.ALT_CTRL_SHIFT), 250);

        analyzeAction = new AbstractAction(tr("Analyze")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                analyze();
            }
        };
        applyAction = new AbstractAction(tr("Apply")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                applySelected();
            }
        };
        applyBucketAction = new AbstractAction(tr("Apply bucket")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                applyBucket();
            }
        };
        zoomAction = new AbstractAction(tr("Zoom")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                zoomToSelected();
            }
        };
        analyzeAction.putValue(javax.swing.Action.SHORT_DESCRIPTION, tr("Match the address layer against buildings in the edit layer"));
        applyAction.putValue(javax.swing.Action.SHORT_DESCRIPTION, tr("Apply the selected proposals"));
        applyBucketAction.putValue(javax.swing.Action.SHORT_DESCRIPTION, tr("Apply every proposal in the selected bucket"));
        zoomAction.putValue(javax.swing.Action.SHORT_DESCRIPTION, tr("Zoom to the selected proposal"));
        new ImageProvider("dialogs", "address-conflation").getResource().attachImageIcon(analyzeAction, true);
        new ImageProvider("dialogs", "address-conflation").getResource().attachImageIcon(applyAction, true);
        new ImageProvider("dialogs", "address-conflation").getResource().attachImageIcon(applyBucketAction, true);
        new ImageProvider("dialogs/autoscale", "selection").getResource().attachImageIcon(zoomAction, true);
        applyAction.setEnabled(false);
        applyBucketAction.setEnabled(false);
        zoomAction.setEnabled(false);

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.gridy = 0;
        top.add(new JLabel(tr("Addresses:")), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        top.add(addressLayerBox, gc);
        gc.gridx = 0;
        gc.gridy = 1;
        gc.weightx = 0;
        top.add(new JLabel(tr("Parcels:")), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        top.add(cellSourceBox, gc);
        gc.gridx = 0;
        gc.gridy = 2;
        gc.gridwidth = 2;
        JPanel options = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        options.add(overlayBox);
        options.add(roadClipBox);
        top.add(options, gc);
        gc.gridy = 3;
        top.add(summary, gc);
        gc.gridy = 4;
        shiftButton.setVisible(false);
        shiftButton.addActionListener(e -> shiftAndRerun());
        top.add(shiftButton, gc);
        overlayBox.setToolTipText(tr("Draw cells and address-to-building links on the map"));
        overlayBox.addActionListener(e -> updateOverlayVisibility());
        roadClipBox.setToolTipText(tr("With no parcel layer, cut Voronoi cells along streets so a cell never reaches the house across the road"));

        DefaultListCellRenderer layerRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Object v = value instanceof Layer ? ((Layer) value).getName() : VORONOI.equals(value) ? tr("Voronoi cells (no parcel layer)") : value;
                return super.getListCellRendererComponent(list, v, index, isSelected, cellHasFocus);
            }
        };
        addressLayerBox.setRenderer(layerRenderer);
        cellSourceBox.setRenderer(layerRenderer);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ProposalRenderer());
        tree.addTreeSelectionListener(e -> onTreeSelection());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    zoomToSelected();
                }
            }
        });

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(new JScrollPane(tree), BorderLayout.CENTER);
        createLayout(content, false, Arrays.asList(new SideButton(downloadAction), new SideButton(analyzeAction),
                new SideButton(applyAction), new SideButton(applyBucketAction), new SideButton(zoomAction)));

        MainApplication.getLayerManager().addLayerChangeListener(this);
        UndoRedoHandler.getInstance().addCommandQueuePreciseListener(this);
        refreshLayerBoxes();
    }

    // ---- layers -----------------------------------------------------------------

    private void refreshLayerBoxes() {
        Object selectedAddr = addressLayerBox.getSelectedItem();
        Object selectedCell = cellSourceBox.getSelectedItem();
        addressLayerBox.removeAllItems();
        cellSourceBox.removeAllItems();
        cellSourceBox.addItem(VORONOI);
        List<OsmDataLayer> layers = MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class);
        for (OsmDataLayer l : layers) {
            addressLayerBox.addItem(l);
            cellSourceBox.addItem(l);
        }
        if (selectedAddr != null && layers.contains(selectedAddr)) {
            addressLayerBox.setSelectedItem(selectedAddr);
        } else {
            // Guess: a non-edit layer whose name smells like addresses, else the first non-edit layer.
            OsmDataLayer edit = MainApplication.getLayerManager().getEditLayer();
            OsmDataLayer guess = layers.stream().filter(l -> l != edit && l.getName().toLowerCase().contains("addr")).findFirst()
                    .orElse(layers.stream().filter(l -> l != edit).findFirst().orElse(null));
            if (guess != null) {
                addressLayerBox.setSelectedItem(guess);
            }
        }
        if (selectedCell != null && (VORONOI.equals(selectedCell) || layers.contains(selectedCell))) {
            cellSourceBox.setSelectedItem(selectedCell);
        } else {
            OsmDataLayer parcels = layers.stream().filter(l -> l.getName().toLowerCase().contains("parcel")).findFirst().orElse(null);
            cellSourceBox.setSelectedItem(parcels != null ? parcels : VORONOI);
        }
    }

    @Override
    public void layerAdded(LayerAddEvent e) {
        refreshLayerBoxes();
    }

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        if (e.getRemovedLayer() == targetLayer || e.getRemovedLayer() == sourceLayer) {
            clearResult();
        }
        refreshLayerBoxes();
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // not interesting
    }

    @Override
    public void destroy() {
        MainApplication.getLayerManager().removeLayerChangeListener(this);
        UndoRedoHandler.getInstance().removeCommandQueuePreciseListener(this);
        removeOverlay();
        super.destroy();
    }

    // ---- overlay -----------------------------------------------------------------

    private void updateOverlayVisibility() {
        if (overlayBox.isSelected()) {
            if (result != null) {
                ensureOverlay().setResult(result);
                for (Proposal p : applied) {
                    overlay.hide(p);
                }
            }
        } else {
            removeOverlay();
        }
    }

    private ProposalOverlayLayer ensureOverlay() {
        if (overlay == null || !MainApplication.getLayerManager().containsLayer(overlay)) {
            overlay = new ProposalOverlayLayer();
            MainApplication.getLayerManager().addLayer(overlay, false);
        }
        return overlay;
    }

    private void removeOverlay() {
        if (overlay != null && MainApplication.getLayerManager().containsLayer(overlay)) {
            MainApplication.getLayerManager().removeLayer(overlay);
        }
        overlay = null;
    }

    // ---- undo / redo -----------------------------------------------------------------

    @Override
    public void commandAdded(CommandAddedEvent e) {
        // nothing: we track our own commands when we add them
    }

    @Override
    public void cleaned(CommandQueueCleanedEvent e) {
        commandProposals.clear();
    }

    @Override
    public void commandUndone(CommandUndoneEvent e) {
        Proposal p = commandProposals.get(e.getCommand());
        if (p != null && applied.remove(p)) {
            addToTree(p);
            if (overlay != null) {
                overlay.unhide(p);
            }
        }
    }

    @Override
    public void commandRedone(CommandRedoneEvent e) {
        Proposal p = commandProposals.get(e.getCommand());
        if (p != null && !applied.contains(p)) {
            applied.add(p);
            removeFromTree(p);
            if (overlay != null) {
                overlay.hide(p);
            }
        }
    }

    /** Put a proposal back under its bucket, keeping bucket order. */
    private void addToTree(Proposal p) {
        DefaultMutableTreeNode bucketNode = null;
        int insertAt = root.getChildCount();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode bn = (DefaultMutableTreeNode) root.getChildAt(i);
            Bucket b = (Bucket) bn.getUserObject();
            if (b == p.getBucket()) {
                bucketNode = bn;
                break;
            }
            if (b.ordinal() > p.getBucket().ordinal()) {
                insertAt = i;
                break;
            }
        }
        if (bucketNode == null) {
            bucketNode = new DefaultMutableTreeNode(p.getBucket());
            treeModel.insertNodeInto(bucketNode, root, insertAt);
        }
        treeModel.insertNodeInto(new DefaultMutableTreeNode(p), bucketNode, bucketNode.getChildCount());
        treeModel.nodeChanged(bucketNode);
    }

    // ---- shift -----------------------------------------------------------------------

    private void shiftAndRerun() {
        if (result == null || result.getShift() == null || sourceLayer == null) {
            return;
        }
        ShiftEstimator.Shift shift = result.getShift();
        LocalProjection proj = result.getProjection();
        // Express the metre offset in the map projection's units through two points.
        EastNorth a = ProjectionRegistry.getProjection().latlon2eastNorth(proj.toLatLon(new Coordinate(0, 0)));
        EastNorth b = ProjectionRegistry.getProjection().latlon2eastNorth(proj.toLatLon(new Coordinate(shift.getDx(), shift.getDy())));
        List<OsmPrimitive> nodes = new ArrayList<>();
        for (Node n : sourceLayer.getDataSet().getNodes()) {
            if (n.isUsable() && n.hasKey("addr:housenumber") && n.getCoor() != null) {
                nodes.add(n);
            }
        }
        if (nodes.isEmpty()) {
            return;
        }
        UndoRedoHandler.getInstance().add(new MoveCommand(nodes, b.east() - a.east(), b.north() - a.north()));
        sourceLayer.invalidate();
        analyze();
    }

    // ---- analysis ----------------------------------------------------------------

    private void analyze() {
        OsmDataLayer edit = MainApplication.getLayerManager().getEditLayer();
        OsmDataLayer addr = (OsmDataLayer) addressLayerBox.getSelectedItem();
        Object cellChoice = cellSourceBox.getSelectedItem();
        if (edit == null || addr == null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), tr("Need an active edit layer with buildings and an address layer."),
                    tr("Address Conflation"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        CellSource cellSource;
        if (cellChoice instanceof OsmDataLayer) {
            OsmDataLayer parcels = (OsmDataLayer) cellChoice;
            cellSource = new ParcelCellSource(parcels.getDataSet(), parcels.getName());
        } else if (roadClipBox.isSelected()) {
            cellSource = new RoadClippedVoronoiCellSource(edit.getDataSet());
        } else {
            cellSource = new VoronoiCellSource();
        }
        targetLayer = edit;
        sourceLayer = addr;
        settings = AddressConflationPreferences.fromPreferences();
        summary.setText(tr("Analyzing..."));
        analyzeAction.setEnabled(false);
        DataSet src = addr.getDataSet();
        DataSet tgt = edit.getDataSet();
        SwingWorker<AnalysisResult, Void> worker = new SwingWorker<AnalysisResult, Void>() {
            @Override
            protected AnalysisResult doInBackground() {
                src.getReadLock().lock();
                try {
                    if (src != tgt) {
                        tgt.getReadLock().lock();
                    }
                    try {
                        return Analyzer.analyze(src, tgt, cellSource, settings);
                    } finally {
                        if (src != tgt) {
                            tgt.getReadLock().unlock();
                        }
                    }
                } finally {
                    src.getReadLock().unlock();
                }
            }

            @Override
            protected void done() {
                analyzeAction.setEnabled(true);
                try {
                    showResult(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Logging.error(ex);
                    summary.setText(tr("Analysis failed: {0}", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                }
            }
        };
        worker.execute();
    }

    private void showResult(AnalysisResult r) {
        result = r;
        applied.clear();
        commandProposals.clear();
        root.removeAllChildren();
        Map<Bucket, DefaultMutableTreeNode> bucketNodes = new EnumMap<>(Bucket.class);
        for (Proposal p : r.getProposals()) {
            DefaultMutableTreeNode bn = bucketNodes.computeIfAbsent(p.getBucket(), b -> {
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(b);
                root.add(n);
                return n;
            });
            bn.add(new DefaultMutableTreeNode(p));
        }
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount() && i < 1; i++) {
            tree.expandRow(i);
        }
        summary.setText(tr("{0} addresses, {1} duplicates dropped, {2} cells ({3}), {4} proposals",
                r.getSourceNodes(), r.getDuplicatesRemoved(), r.getCells().size(),
                r.isSynthetic() ? tr("Voronoi") : tr("parcels"), r.getProposals().size()));
        applyBucketAction.setEnabled(false);
        applyAction.setEnabled(false);
        zoomAction.setEnabled(false);
        ShiftEstimator.Shift shift = r.getShift();
        if (shift != null && shift.isSignificant()) {
            shiftButton.setText(tr("Points look shifted by {0} m: move the address layer and re-run", Math.round(shift.getDistance())));
            shiftButton.setToolTipText(shift.toString());
            shiftButton.setVisible(true);
        } else {
            shiftButton.setVisible(false);
        }
        if (overlayBox.isSelected()) {
            ensureOverlay().setResult(r);
        }
    }

    private void clearResult() {
        result = null;
        applied.clear();
        commandProposals.clear();
        root.removeAllChildren();
        treeModel.reload();
        summary.setText(" ");
        shiftButton.setVisible(false);
        removeOverlay();
    }

    // ---- selection ------------------------------------------------------------------

    private List<Proposal> selectedProposals() {
        List<Proposal> out = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) {
            return out;
        }
        for (TreePath tp : paths) {
            Object o = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
            if (o instanceof Proposal) {
                out.add((Proposal) o);
            }
        }
        return out;
    }

    private Bucket selectedBucket() {
        TreePath tp = tree.getSelectionPath();
        if (tp == null) {
            return null;
        }
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) tp.getLastPathComponent();
        if (n.getUserObject() instanceof Bucket) {
            return (Bucket) n.getUserObject();
        }
        if (n.getParent() != null && ((DefaultMutableTreeNode) n.getParent()).getUserObject() instanceof Bucket) {
            return (Bucket) ((DefaultMutableTreeNode) n.getParent()).getUserObject();
        }
        return null;
    }

    private void onTreeSelection() {
        if (updatingSelection) {
            return;
        }
        List<Proposal> sel = selectedProposals();
        Bucket b = selectedBucket();
        applyAction.setEnabled(sel.stream().anyMatch(ProposalApplier::isApplicable));
        applyBucketAction.setEnabled(b != null && b.isBulkApplicable());
        zoomAction.setEnabled(!sel.isEmpty());
        if (sel.isEmpty() || targetLayer == null) {
            return;
        }
        List<OsmPrimitive> targetPrims = new ArrayList<>();
        List<OsmPrimitive> sourcePrims = new ArrayList<>();
        for (Proposal p : sel) {
            for (OsmPrimitive prim : p.getHighlightPrimitives()) {
                if (prim.getDataSet() == targetLayer.getDataSet()) {
                    targetPrims.add(prim);
                } else if (sourceLayer != null && prim.getDataSet() == sourceLayer.getDataSet()) {
                    sourcePrims.add(prim);
                }
            }
        }
        updatingSelection = true;
        try {
            targetLayer.getDataSet().setSelected(targetPrims);
            if (sourceLayer != null && sourceLayer != targetLayer) {
                sourceLayer.getDataSet().setSelected(sourcePrims);
            }
        } finally {
            updatingSelection = false;
        }
        if (overlay != null) {
            overlay.setSelected(sel);
        }
    }

    private void zoomToSelected() {
        List<Proposal> sel = selectedProposals();
        if (sel.isEmpty()) {
            return;
        }
        List<OsmPrimitive> prims = new ArrayList<>();
        for (Proposal p : sel) {
            prims.addAll(p.getHighlightPrimitives());
        }
        prims.removeIf(pr -> pr.getDataSet() == null || pr.isDeleted());
        if (!prims.isEmpty()) {
            AutoScaleAction.zoomTo(prims);
        }
    }

    // ---- apply --------------------------------------------------------------------------

    private void applySelected() {
        apply(selectedProposals());
    }

    private void applyBucket() {
        Bucket b = selectedBucket();
        if (b == null || result == null) {
            return;
        }
        List<Proposal> ps = new ArrayList<>();
        for (Proposal p : result.getProposals()) {
            if (p.getBucket() == b && !applied.contains(p)) {
                ps.add(p);
            }
        }
        apply(ps);
    }

    private final List<Proposal> applied = new ArrayList<>();

    private void apply(List<Proposal> proposals) {
        if (result == null || targetLayer == null || sourceLayer == null) {
            return;
        }
        int done = 0;
        for (Proposal p : proposals) {
            if (applied.contains(p)) {
                continue;
            }
            Applied a = ProposalApplier.build(p, targetLayer.getDataSet(), sourceLayer.getDataSet(), result.getProjection(), settings);
            if (a == null || a.isEmpty()) {
                continue;
            }
            for (Command c : Arrays.asList(a.getTargetCommand(), a.getSourceCommand())) {
                if (c != null) {
                    commandProposals.put(c, p);
                    UndoRedoHandler.getInstance().add(c);
                }
            }
            applied.add(p);
            removeFromTree(p);
            if (overlay != null) {
                overlay.hide(p);
            }
            done++;
        }
        summary.setText(tr("Applied {0} proposals", done));
        if (done > 0) {
            targetLayer.invalidate();
            if (sourceLayer != targetLayer) {
                sourceLayer.invalidate();
            }
        }
    }

    private void removeFromTree(Proposal p) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode bn = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < bn.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) bn.getChildAt(j);
                if (leaf.getUserObject() == p) {
                    treeModel.removeNodeFromParent(leaf);
                    if (bn.getChildCount() == 0) {
                        treeModel.removeNodeFromParent(bn);
                    } else {
                        treeModel.nodeChanged(bn);
                    }
                    return;
                }
            }
        }
    }

    // ---- rendering ----------------------------------------------------------------------

    private static final class ProposalRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof Bucket) {
                Bucket b = (Bucket) o;
                setText(b.getLabel() + " (" + ((DefaultMutableTreeNode) value).getChildCount() + ")");
                setToolTipText(b.getDescription());
            } else if (o instanceof Proposal) {
                Proposal p = (Proposal) o;
                StringBuilder sb = new StringBuilder(p.describe());
                if (p.getTarget() != null) {
                    sb.append(" → building=").append(Objects.toString(p.getTarget().getBuildingValue(), "?"))
                      .append(" (").append(Math.round(p.getTarget().getArea())).append(" m²)");
                }
                sb.append("  ").append(Math.round(p.getConfidence() * 100)).append('%');
                setText(sb.toString());
                setToolTipText("<html>" + String.join("<br>", p.getReasons()) + "</html>");
            }
            return this;
        }
    }
}
