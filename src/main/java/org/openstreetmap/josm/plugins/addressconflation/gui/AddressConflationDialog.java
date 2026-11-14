// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
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
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerAddEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerChangeListener;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerOrderChangeEvent;
import org.openstreetmap.josm.gui.layer.LayerManager.LayerRemoveEvent;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.addressconflation.apply.ChangesetSources;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier;
import org.openstreetmap.josm.plugins.addressconflation.apply.ProposalApplier.Applied;
import org.openstreetmap.josm.plugins.addressconflation.cells.CellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.RoadClippedVoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.ParcelCellSource;
import org.openstreetmap.josm.plugins.addressconflation.cells.VoronoiCellSource;
import org.openstreetmap.josm.plugins.addressconflation.engine.Analyzer;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;
import org.openstreetmap.josm.plugins.addressconflation.engine.LocalProjection;
import org.openstreetmap.josm.plugins.addressconflation.engine.ParcelShiftEstimator;
import org.openstreetmap.josm.plugins.addressconflation.engine.ShiftEstimator;
import org.openstreetmap.josm.plugins.addressconflation.io.DownloadSourceAction;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesLayer;
import org.openstreetmap.josm.plugins.addressconflation.model.AnalysisResult;
import org.openstreetmap.josm.plugins.addressconflation.model.Bucket;
import org.openstreetmap.josm.plugins.addressconflation.model.BuildingCandidate;
import org.openstreetmap.josm.plugins.addressconflation.model.CellBuilding;
import org.openstreetmap.josm.plugins.addressconflation.model.Proposal;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Side panel: download source data, run the analysis (layers are picked in
 * {@link AnalysisSetupDialog}), review proposals grouped by bucket, apply them.
 * Selecting address nodes, hint footprints or parcels on the map selects their rows.
 */
public class AddressConflationDialog extends ToggleDialog
        implements LayerChangeListener, CommandQueuePreciseListener, DataSelectionListener {

    private final JLabel summary = new JLabel(" ");
    /** Shown only after the mapper turned the shift popup down, as a way back. */
    private final JButton shiftButton = new JButton();
    private final JButton parcelShiftButton = new JButton();
    /** Layers whose shift the mapper turned down: not asked again this session. */
    private final Set<OsmDataLayer> declinedAddressShift = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<OsmDataLayer> declinedParcelShift = Collections.newSetFromMap(new WeakHashMap<>());
    private ProposalOverlayLayer overlay;
    /** Commands we issued, so undo can bring the proposal back. */
    private final AppliedProposals applied = new AppliedProposals();
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
    /** Hint and parcel data of the last run, or null; looked up when the mapper selects in them. */
    private DataSet hintData;
    private DataSet parcelData;
    /** Hint and parcel layers of the last run, or null; named in the changeset source tag. */
    private OsmDataLayer hintLayer;
    private OsmDataLayer parcelLayer;
    private ProposalIndex index;
    /** Layers and options of the last run, reused by shift-and-rerun. */
    private AnalysisSetupDialog.Choice lastChoice;
    /** Set while the panel changes a map selection, so that change does not come back as a row selection. */
    private boolean updatingSelection;
    /** While a map selection drives the list: the dataset the mapper is selecting in, left as it is. */
    private DataSet syncingFrom;

    public AddressConflationDialog() {
        super(tr("Better Address Conflation"), "address-conflation", tr("Match address points to buildings"),
                Shortcut.registerShortcut("subwindow:addressconflation", tr("Windows: {0}", tr("Better Address Conflation")),
                        KeyEvent.VK_A, Shortcut.ALT_CTRL_SHIFT), 250);

        analyzeAction = new AbstractAction(tr("Analyze...")) {
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
        analyzeAction.putValue(javax.swing.Action.SHORT_DESCRIPTION,
                tr("Choose the address, parcel and hint layers, then match addresses against buildings in the active layer"));
        applyAction.putValue(javax.swing.Action.SHORT_DESCRIPTION,
                tr("Apply the selected proposals to the analyzed layer (undoable). Select rows in the list, "
                        + "or select address nodes, hint footprints or parcels on the map."));
        applyBucketAction.putValue(javax.swing.Action.SHORT_DESCRIPTION,
                tr("Apply every remaining proposal in the selected bucket. Only buckets that are safe to apply in bulk allow this."));
        zoomAction.putValue(javax.swing.Action.SHORT_DESCRIPTION, tr("Zoom the map to the selected proposals (or double-click a row)"));
        new ImageProvider("dialogs", "search").getResource().attachImageIcon(analyzeAction, true);
        new ImageProvider("apply").getResource().attachImageIcon(applyAction, true);
        new ImageProvider("misc", "check_large").getResource().attachImageIcon(applyBucketAction, true);
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
        gc.weightx = 1;
        top.add(summary, gc);
        gc.gridy = 1;
        shiftButton.setVisible(false);
        shiftButton.addActionListener(e -> shiftAndRerun());
        top.add(shiftButton, gc);
        gc.gridy = 2;
        parcelShiftButton.setVisible(false);
        parcelShiftButton.addActionListener(e -> shiftParcelsAndRerun());
        top.add(parcelShiftButton, gc);
        summary.setToolTipText(tr("Result of the last analysis: address points read, exact duplicates dropped, cells built, proposals made"));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ProposalRenderer());
        // JTree shows renderer tooltips (bucket descriptions, match reasons) only once registered.
        ToolTipManager.sharedInstance().registerComponent(tree);
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
        SelectionEventManager.getInstance().addSelectionListenerForEdt(this);
    }

    // ---- layers -----------------------------------------------------------------

    @Override
    public void layerAdded(LayerAddEvent e) {
        // layers are picked when Analyze opens its popup
    }

    @Override
    public void layerRemoving(LayerRemoveEvent e) {
        if (e.getRemovedLayer() == targetLayer || e.getRemovedLayer() == sourceLayer) {
            clearResult();
        }
        if (lastChoice != null && lastChoice.uses(e.getRemovedLayer())) {
            lastChoice = null;
        }
        if (e.getRemovedLayer() == overlay) {
            // Deleted from the Layers panel: the next analysis brings it back.
            overlay = null;
        }
    }

    @Override
    public void layerOrderChanged(LayerOrderChangeEvent e) {
        // not interesting
    }

    @Override
    public void destroy() {
        MainApplication.getLayerManager().removeLayerChangeListener(this);
        UndoRedoHandler.getInstance().removeCommandQueuePreciseListener(this);
        SelectionEventManager.getInstance().removeSelectionListener(this);
        removeOverlay();
        super.destroy();
    }

    // ---- overlay -----------------------------------------------------------------

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
        // Undo history gone: applied proposals stay applied.
        applied.forgetCommands();
    }

    @Override
    public void commandUndone(CommandUndoneEvent e) {
        Proposal p = applied.undone(e.getCommand());
        if (p != null) {
            addToTree(p);
            if (overlay != null) {
                overlay.unhide(p);
            }
        }
    }

    @Override
    public void commandRedone(CommandRedoneEvent e) {
        Proposal p = applied.redone(e.getCommand());
        if (p != null) {
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

    private boolean addressShiftFound(AnalysisResult r) {
        return r.getShift() != null && r.getShift().isSignificant() && sourceLayer != null;
    }

    private boolean parcelShiftFound(AnalysisResult r) {
        return r.getParcelShift() != null && r.getParcelShift().isSignificant() && parcelLayer != null;
    }

    /**
     * After an analysis: when a layer looks shifted and the mapper has not turned that down
     * for it already, explain and ask. Accepted moves are made, then the analysis runs once more.
     */
    private void offerShifts(AnalysisResult r) {
        if (r != result || lastChoice == null) {
            // A newer analysis replaced this one, or there is nothing to re-run.
            return;
        }
        boolean moved = false;
        if (addressShiftFound(r) && !declinedAddressShift.contains(sourceLayer)) {
            if (ask(tr("Address layer looks shifted"), addressShiftMessage(r.getShift()))) {
                moved |= moveAddresses(r.getShift());
            } else {
                declinedAddressShift.add(sourceLayer);
            }
        }
        if (parcelShiftFound(r) && !declinedParcelShift.contains(parcelLayer)) {
            if (ask(tr("Parcel layer looks shifted"), parcelShiftMessage(r.getParcelShift()))) {
                moved |= moveParcels(r.getParcelShift());
            } else {
                declinedParcelShift.add(parcelLayer);
            }
        }
        if (moved) {
            run(lastChoice);
        } else {
            updateShiftButtons(r);
        }
    }

    private static boolean ask(String title, String message) {
        Object[] options = {tr("Accept and re-run"), tr("No thanks")};
        int choice = JOptionPane.showOptionDialog(MainApplication.getMainFrame(), message, title,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        return choice == 0;
    }

    private static String addressShiftMessage(ShiftEstimator.Shift s) {
        return tr("<html><body style=''width: 400px''><b>The address layer looks shifted.</b><br><br>"
                + "Most address points sit about {0} m from their buildings, all in the same direction. That usually means "
                + "the address layer as a whole should be offset to reduce mismatches caused by the misalignment.<br><br>"
                + "Shifting the address layer {1} brings the points to within a typical {2} m of their building''s centre "
                + "({3} addresses measured).<br><br>Would you like this shift to be applied to the address layer?</body></html>",
                Math.round(s.getDistance()), direction(s.getDx(), s.getDy()),
                String.format(Locale.ROOT, "%.1f", s.getSpread()), s.getSamples());
    }

    private static String parcelShiftMessage(ParcelShiftEstimator.Shift s) {
        return tr("<html><body style=''width: 400px''><b>The parcel layer looks shifted.</b><br><br>"
                + "Many buildings are split by parcel boundaries. That usually means the parcel layer is offset from "
                + "the imagery the buildings were traced from, and shifting it would reduce mismatches caused by the "
                + "misalignment.<br><br>"
                + "Shifting the parcel layer {0} puts {1}% of the building outlines inside a single parcel, up from {2}% "
                + "({3} buildings measured).<br><br>Would you like this shift to be applied to the parcel layer?</body></html>",
                direction(s.getDx(), s.getDy()), Math.round(s.getShareAfter() * 100), Math.round(s.getShareBefore() * 100),
                s.getSamples());
    }

    /** "2.5 m west and 0.8 m south", leaving out a direction the move does not go in. */
    private static String direction(double dx, double dy) {
        String ew = Math.abs(dx) < 0.05 ? null : String.format(Locale.ROOT, "%.1f m %s", Math.abs(dx), dx > 0 ? tr("east") : tr("west"));
        String ns = Math.abs(dy) < 0.05 ? null : String.format(Locale.ROOT, "%.1f m %s", Math.abs(dy), dy > 0 ? tr("north") : tr("south"));
        if (ew != null && ns != null) {
            return tr("{0} and {1}", ew, ns);
        }
        return ew != null ? ew : ns;
    }

    /** The pane's buttons: a way back to a shift the mapper turned down. */
    private void updateShiftButtons(AnalysisResult r) {
        if (addressShiftFound(r) && declinedAddressShift.contains(sourceLayer)) {
            shiftButton.setText(tr("Points look shifted by {0} m: move the address layer and re-run", Math.round(r.getShift().getDistance())));
            shiftButton.setToolTipText(addressShiftMessage(r.getShift()));
            shiftButton.setVisible(true);
        } else {
            shiftButton.setVisible(false);
        }
        if (parcelShiftFound(r) && declinedParcelShift.contains(parcelLayer)) {
            parcelShiftButton.setText(tr("Parcels look shifted by {0} m: move the parcel layer and re-run",
                    String.format(Locale.ROOT, "%.1f", r.getParcelShift().getDistance())));
            parcelShiftButton.setToolTipText(parcelShiftMessage(r.getParcelShift()));
            parcelShiftButton.setVisible(true);
        } else {
            parcelShiftButton.setVisible(false);
        }
    }

    private void shiftAndRerun() {
        if (result != null && result.getShift() != null && lastChoice != null && moveAddresses(result.getShift())) {
            run(lastChoice);
        }
    }

    private void shiftParcelsAndRerun() {
        if (result != null && result.getParcelShift() != null && lastChoice != null && moveParcels(result.getParcelShift())) {
            run(lastChoice);
        }
    }

    private boolean moveAddresses(ShiftEstimator.Shift shift) {
        if (sourceLayer == null) {
            return false;
        }
        List<OsmPrimitive> nodes = new ArrayList<>();
        for (Node n : sourceLayer.getDataSet().getNodes()) {
            if (n.isUsable() && n.hasKey("addr:housenumber") && n.getCoor() != null) {
                nodes.add(n);
            }
        }
        if (!move(nodes, shift.getDx(), shift.getDy())) {
            return false;
        }
        sourceLayer.invalidate();
        return true;
    }

    private boolean moveParcels(ParcelShiftEstimator.Shift shift) {
        if (parcelLayer == null) {
            return false;
        }
        List<OsmPrimitive> nodes = new ArrayList<>();
        for (Node n : parcelLayer.getDataSet().getNodes()) {
            if (n.isUsable() && n.getCoor() != null) {
                nodes.add(n);
            }
        }
        if (!move(nodes, shift.getDx(), shift.getDy())) {
            return false;
        }
        parcelLayer.invalidate();
        return true;
    }

    /** Move nodes by a metre offset as one undoable command; false when there is nothing to move. */
    private boolean move(List<OsmPrimitive> nodes, double dx, double dy) {
        if (nodes.isEmpty()) {
            return false;
        }
        LocalProjection proj = result.getProjection();
        // Express the metre offset in the map projection's units through two points.
        EastNorth a = ProjectionRegistry.getProjection().latlon2eastNorth(proj.toLatLon(new Coordinate(0, 0)));
        EastNorth b = ProjectionRegistry.getProjection().latlon2eastNorth(proj.toLatLon(new Coordinate(dx, dy)));
        UndoRedoHandler.getInstance().add(new MoveCommand(nodes, b.east() - a.east(), b.north() - a.north()));
        return true;
    }

    // ---- analysis ----------------------------------------------------------------

    private void analyze() {
        OsmDataLayer edit = MainApplication.getLayerManager().getEditLayer();
        if (edit == null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), tr("Need an active edit layer with buildings."),
                    tr("Better Address Conflation"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        AnalysisSetupDialog.Choice choice = AnalysisSetupDialog.show(edit);
        if (choice != null && OutsideUsNotice.confirm(addressCenter(choice.addressLayer))) {
            lastChoice = choice;
            run(choice);
        }
    }

    /** Middle of the layer's address points, or null when it has none. */
    private static LatLon addressCenter(OsmDataLayer layer) {
        BBox box = new BBox();
        for (Node n : layer.getDataSet().getNodes()) {
            if (n.isUsable() && n.hasKey("addr:housenumber") && n.isLatLonKnown()) {
                box.add(n);
            }
        }
        return box.isValid() ? box.getCenter() : null;
    }

    private void run(AnalysisSetupDialog.Choice choice) {
        OsmDataLayer edit = MainApplication.getLayerManager().getEditLayer();
        OsmDataLayer addr = choice.addressLayer;
        if (edit == null) {
            return;
        }
        CellSource cellSource;
        if (choice.parcelLayer != null) {
            cellSource = new ParcelCellSource(choice.parcelLayer.getDataSet(), choice.parcelLayer.getName());
        } else if (choice.roadClip) {
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
        DataSet hintDs = choice.hintLayer != null && choice.hintLayer != edit ? choice.hintLayer.getDataSet() : null;
        hintData = hintDs;
        parcelData = choice.parcelLayer != null ? choice.parcelLayer.getDataSet() : null;
        hintLayer = hintDs != null ? choice.hintLayer : null;
        parcelLayer = choice.parcelLayer;
        SwingWorker<AnalysisResult, Void> worker = new SwingWorker<AnalysisResult, Void>() {
            @Override
            protected AnalysisResult doInBackground() {
                src.getReadLock().lock();
                try {
                    if (src != tgt) {
                        tgt.getReadLock().lock();
                    }
                    try {
                        return Analyzer.analyze(src, tgt, hintDs, cellSource, settings);
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
        index = new ProposalIndex(r.getProposals(), sourceLayer.getDataSet(), targetLayer.getDataSet(), hintData, parcelData);
        applied.clear();
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
        updateShiftButtons(r);
        // Shown and hidden from the Layers panel like any other layer.
        ensureOverlay().setResult(r);
        // Ask once the results are on screen, so the mapper sees what the question is about.
        SwingUtilities.invokeLater(() -> offerShifts(r));
    }

    private void clearResult() {
        result = null;
        index = null;
        applied.clear();
        root.removeAllChildren();
        treeModel.reload();
        summary.setText(" ");
        shiftButton.setVisible(false);
        parcelShiftButton.setVisible(false);
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
            // Rows picked from the map: keep the mapper's own selection in the layer they are working in.
            if (targetLayer.getDataSet() != syncingFrom) {
                targetLayer.getDataSet().setSelected(targetPrims);
            }
            if (sourceLayer != null && sourceLayer != targetLayer && sourceLayer.getDataSet() != syncingFrom) {
                sourceLayer.getDataSet().setSelected(sourcePrims);
            }
        } finally {
            updatingSelection = false;
        }
        if (overlay != null) {
            overlay.setSelected(sel);
        }
    }

    /** Address nodes, buildings, hint footprints or parcels selected on the map select their rows. */
    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        if (updatingSelection || index == null) {
            return;
        }
        if (withinSelectedRows(event.getSelection())) {
            // Narrowing what the rows highlight, such as picking one building for an ambiguous
            // row: the rows stay, so Apply uses the pick.
            return;
        }
        Set<Proposal> found = index.find(event.getSource(), event.getSelection());
        if (found.isEmpty()) {
            // Nothing of ours: keep the rows as they are.
            return;
        }
        List<TreePath> paths = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode bn = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < bn.getChildCount(); j++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) bn.getChildAt(j);
                if (found.contains(leaf.getUserObject())) {
                    paths.add(new TreePath(leaf.getPath()));
                }
            }
        }
        if (paths.isEmpty()) {
            // already applied
            return;
        }
        syncingFrom = event.getSource();
        try {
            tree.setSelectionPaths(paths.toArray(new TreePath[0]));
            tree.scrollPathToVisible(paths.get(0));
        } finally {
            syncingFrom = null;
        }
    }

    /** True when every selected primitive is something the selected rows already highlight. */
    private boolean withinSelectedRows(Collection<? extends OsmPrimitive> selection) {
        List<Proposal> rows = selectedProposals();
        if (selection.isEmpty() || rows.isEmpty()) {
            return false;
        }
        Set<OsmPrimitive> highlighted = new HashSet<>();
        for (Proposal p : rows) {
            highlighted.addAll(p.getHighlightPrimitives());
        }
        return highlighted.containsAll(selection);
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
            if (p.getBucket() == b && !applied.isApplied(p)) {
                ps.add(p);
            }
        }
        apply(ps);
    }

    private void apply(List<Proposal> proposals) {
        if (result == null || targetLayer == null || sourceLayer == null) {
            return;
        }
        int done = 0;
        int needPick = 0;
        int gone = 0;
        int failed = 0;
        for (Proposal p : proposals) {
            if (applied.isApplied(p)) {
                continue;
            }
            if (isStale(p)) {
                // Its address points were all deleted since the analysis, by hand or by another tool.
                gone++;
                dropFromList(p);
                continue;
            }
            BuildingCandidate pick = pickedCandidate(p);
            if (p.requiresPick() && pick == null) {
                needPick++;
                continue;
            }
            List<Command> executed = new ArrayList<>();
            Command running = null;
            try {
                Applied a = ProposalApplier.build(p, pick, targetLayer.getDataSet(), sourceLayer.getDataSet(), result.getProjection(), settings);
                if (a == null || a.isEmpty()) {
                    continue;
                }
                for (Command c : Arrays.asList(a.getTargetCommand(), a.getSourceCommand())) {
                    if (c != null) {
                        running = c;
                        UndoRedoHandler.getInstance().add(c);
                        executed.add(c);
                        running = null;
                    }
                }
                if (a.getTargetCommand() != null) {
                    ChangesetSources.HOOK.record(a.getTargetCommand().getParticipatingPrimitives(), sourcesOf(p, pick));
                }
                done++;
            } catch (RuntimeException ex) {
                // One proposal that cannot be applied must not cost the mapper the others.
                Logging.error(ex);
                failed++;
                if (running != null && UndoRedoHandler.getInstance().getLastCommand() == running) {
                    // It ran and is on the undo stack; something listening to it failed.
                    executed.add(running);
                }
            } finally {
                // Anything that took effect makes the proposal applied, so it cannot be applied twice.
                if (!executed.isEmpty()) {
                    applied.applied(p, executed);
                    dropFromList(p);
                }
            }
        }
        StringBuilder text = new StringBuilder(tr("Applied {0} proposals", done));
        if (needPick > 0) {
            text.append("; ").append(tr("{0} need a building picked: select one of the highlighted buildings on the map, then Apply", needPick));
        }
        if (gone > 0) {
            text.append("; ").append(tr("{0} skipped: their address points were already deleted", gone));
        }
        if (failed > 0) {
            text.append("; ").append(tr("{0} could not be applied (details in the JOSM log)", failed));
        }
        summary.setText(text.toString());
        if (done > 0) {
            targetLayer.invalidate();
            if (sourceLayer != targetLayer) {
                sourceLayer.invalidate();
            }
        }
    }

    /** True when none of the proposal's address points exists any more. */
    private static boolean isStale(Proposal p) {
        return ProposalApplier.liveAddresses(p).isEmpty();
    }

    /** Take a proposal out of the list and off the overlay. */
    private void dropFromList(Proposal p) {
        removeFromTree(p);
        if (overlay != null) {
            overlay.hide(p);
        }
    }

    /**
     * The plugin's layers an applied proposal drew on, as changeset source names: the address
     * layer always, the hint layer when the address went on a hint footprint, the parcel layer
     * when a parcel decided the match. Layers the mapper loaded some other way are not named.
     */
    private List<String> sourcesOf(Proposal p, BuildingCandidate pick) {
        List<String> out = new ArrayList<>();
        addSource(out, sourceLayer);
        BuildingCandidate target = pick != null ? pick : p.getTarget();
        if (target != null && target.isHint()) {
            addSource(out, hintLayer);
        }
        if (p.getCell() != null && !p.getCell().isSynthetic()) {
            addSource(out, parcelLayer);
        }
        return out;
    }

    private static void addSource(List<String> out, OsmDataLayer layer) {
        if (layer instanceof OpenAddressesLayer && !out.contains(((OpenAddressesLayer) layer).getSourceLabel())) {
            out.add(((OpenAddressesLayer) layer).getSourceLabel());
        }
    }

    /**
     * The candidate the mapper picked: exactly one of the proposal's buildings selected in the
     * edit layer. Selecting a row selects all of them, so this only kicks in after the mapper
     * narrows the selection on the map.
     */
    private BuildingCandidate pickedCandidate(Proposal p) {
        if (targetLayer == null || p.getCandidates().isEmpty()) {
            return null;
        }
        Collection<OsmPrimitive> selected = targetLayer.getDataSet().getSelected();
        BuildingCandidate found = null;
        for (CellBuilding c : p.getCandidates()) {
            BuildingCandidate b = c.getBuilding();
            if (!b.isHint() && selected.contains(b.getPrimitive())) {
                if (found != null) {
                    return null;
                }
                found = b;
            }
        }
        if (found == null) {
            return null;
        }
        // the only candidate selected, but other things selected too: not a clear pick
        long otherBuildings = selected.stream().filter(o -> o.hasKey("building")).count();
        return otherBuildings == 1 ? found : null;
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
        /** The overlay's colour for each bucket, shown on the bucket headings as its legend. */
        private static final Map<Bucket, Icon> SWATCHES = new EnumMap<>(Bucket.class);

        static {
            for (Map.Entry<Bucket, Color> e : ProposalOverlayLayer.colors().entrySet()) {
                SWATCHES.put(e.getKey(), swatch(e.getValue()));
            }
        }

        private static Icon swatch(Color color) {
            return new Icon() {
                @Override
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(color);
                    g2.fillOval(x + 1, y + 1, 10, 10);
                    g2.setColor(new Color(0, 0, 0, 160));
                    g2.drawOval(x + 1, y + 1, 10, 10);
                    g2.dispose();
                }

                @Override
                public int getIconWidth() {
                    return 12;
                }

                @Override
                public int getIconHeight() {
                    return 12;
                }
            };
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof Bucket) {
                Bucket b = (Bucket) o;
                setText(b.getLabel() + " (" + ((DefaultMutableTreeNode) value).getChildCount() + ")");
                setToolTipText(b.getDescription());
                setIcon(SWATCHES.get(b));
            } else if (o instanceof Proposal) {
                Proposal p = (Proposal) o;
                StringBuilder sb = new StringBuilder(p.describe());
                if (p.getBucket() == Bucket.CLEAN && !p.getExisting().isEmpty()) {
                    sb.append(" → already on ").append(p.getExisting().get(0).getPrimitive().getDisplayName(DefaultNameFormatter.getInstance()));
                } else if (p.getBucket() == Bucket.FILL_EXISTING && !p.getExisting().isEmpty()) {
                    sb.append(" → fills ").append(p.getExisting().get(0).getPrimitive().getDisplayName(DefaultNameFormatter.getInstance()));
                } else if (p.getTarget() != null && p.getTarget().isNode()) {
                    sb.append(" → building node ").append(Objects.toString(p.getTarget().getBuildingValue(), "?"));
                } else if (p.getTarget() != null) {
                    sb.append(p.getTarget().isHint() ? " → hint " : " → building=").append(Objects.toString(p.getTarget().getBuildingValue(), "?"))
                      .append(" (").append(Math.round(p.getTarget().getArea())).append(" m²)");
                } else if (p.requiresPick()) {
                    sb.append(" → pick one of ").append(p.getCandidates().size()).append(" buildings");
                }
                sb.append("  ").append(Math.round(p.getConfidence() * 100)).append('%');
                setText(sb.toString());
                setToolTipText("<html>" + String.join("<br>", p.getReasons()) + "</html>");
            }
            return this;
        }
    }
}
