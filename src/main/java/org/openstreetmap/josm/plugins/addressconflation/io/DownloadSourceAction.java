// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.io.OsmTransferException;
import org.openstreetmap.josm.plugins.addressconflation.gui.AddressConflationPreferences;
import org.openstreetmap.josm.plugins.addressconflation.gui.CustomSourceEditor;
import org.openstreetmap.josm.plugins.addressconflation.gui.OutsideUsNotice;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseAssessment;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseBadge;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseStatus;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;
import org.xml.sax.SAXException;

/**
 * Download National Address Database points, Microsoft building footprints
 * (as placement hints) and, where OpenAddresses knows a parcel source for the
 * area, parcels for the current map view. Results land in non-uploadable layers
 * the analysis popup picks by default.
 *
 * {@link DownloadTask} can also download the layers of an OpenAddresses source
 * (by id, URL or file). That path has no UI yet.
 */
public class DownloadSourceAction extends JosmAction {
    /** Whether each row is ticked; the names predate the dropdowns. */
    private static final String PREF_NAD = "addressconflation.download.nad";
    private static final String PREF_MS_BUILDINGS = "addressconflation.download.msBuildings";
    private static final String PREF_PARCELS = "addressconflation.download.parcels";
    /** The source last picked in each row, as a {@link Choice} key. */
    private static final String PREF_ADDRESS_CHOICE = "addressconflation.download.addressSource";
    private static final String PREF_BUILDING_CHOICE = "addressconflation.download.buildingSource";
    private static final String PREF_PARCEL_CHOICE = "addressconflation.download.parcelSource";

    public DownloadSourceAction() {
        super(tr("Download..."), "download_in_view",
                tr("Download address points, building outlines and parcels for the current view"),
                null, true, "addressconflation/download", false);
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.isDisplayingMapView());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!MainApplication.isDisplayingMapView()) {
            return;
        }
        Bounds bounds = MainApplication.getMap().mapView.getRealBounds();
        String problem = EsriFeatureClient.areaProblem(bounds);
        if (problem != null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), problem, tr("Download addresses"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!OutsideUsNotice.confirm(bounds.getCenter())) {
            return;
        }

        // One row per kind of data: tick it, pick where from. The mapper's own sources that
        // cover the view join the built-in ones; parcels also get what OpenAddresses lists.
        SourceRow addresses = new SourceRow(tr("Addresses from"), tr("Address points to match to buildings"),
                PREF_NAD, PREF_ADDRESS_CHOICE);
        SourceRow buildings = new SourceRow(tr("Building outlines (hints) from"),
                tr("Used only to position addresses where OSM has no building. Never imported."), PREF_MS_BUILDINGS, PREF_BUILDING_CHOICE);
        SourceRow parcels = new SourceRow(tr("Parcels from"),
                tr("Parcel boundaries decide which building each address belongs to. Never uploaded."), PREF_PARCELS, PREF_PARCEL_CHOICE);
        Map<FeatureSource.Kind, SourceRow> rows = new EnumMap<>(FeatureSource.Kind.class);
        rows.put(FeatureSource.Kind.ADDRESSES, addresses);
        rows.put(FeatureSource.Kind.BUILDINGS, buildings);
        rows.put(FeatureSource.Kind.PARCELS, parcels);

        addresses.add(new Choice("nad", tr("National Address Database (NAD)"), FeatureSource.nad()));
        buildings.add(new Choice("microsoft", tr("Microsoft building footprints"), FeatureSource.microsoftBuildings()));
        int elsewhere = 0;
        for (CustomSource cs : CustomSource.load()) {
            if (cs.covers(bounds)) {
                rows.get(cs.getKind()).add(Choice.of(cs));
            } else {
                elsewhere++;
            }
        }
        for (SourceRow row : rows.values()) {
            row.pickDefault(null);
        }

        JLabel parcelStatus = new JLabel(tr("Looking up parcel sources for this area..."));
        JLabel note = new JLabel(elsewhere == 0 ? " "
                : trn("{0} of your sources does not cover this area.", "{0} of your sources do not cover this area.", elsewhere, elsewhere));
        JButton addSource = new JButton(tr("Add source..."));
        addSource.setToolTipText(tr("Add your own ArcGIS REST or OGC API layer of addresses, parcels or building outlines"));
        addSource.addActionListener(ev -> {
            List<CustomSource> all = CustomSource.load();
            CustomSource cs = CustomSourceEditor.edit(addSource, null, CustomSourceEditor.namesExcept(all, null));
            if (cs == null) {
                return;
            }
            all.add(cs);
            CustomSource.save(all);
            if (cs.covers(bounds)) {
                rows.get(cs.getKind()).addAndUse(Choice.of(cs));
            } else {
                note.setText(tr("{0} is saved but does not cover this area.", cs.getName()));
            }
            Window w = SwingUtilities.getWindowAncestor(addSource);
            if (w != null) {
                w.pack();
            }
        });

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridy = 0;
        for (SourceRow row : Arrays.asList(addresses, buildings, parcels)) {
            row.addTo(panel, gc);
            gc.gridy++;
        }
        gc.gridx = 0;
        gc.gridwidth = 2;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(parcelStatus, gc);
        JPanel footer = new JPanel(new GridBagLayout());
        GridBagConstraints fc = new GridBagConstraints();
        fc.anchor = GridBagConstraints.WEST;
        footer.add(addSource, fc);
        fc.insets = new Insets(0, 8, 0, 0);
        fc.weightx = 1;
        footer.add(note, fc);
        gc.gridy++;
        gc.insets = new Insets(10, 3, 3, 3);
        panel.add(footer, gc);

        SwingWorker<List<ParcelSourceFinder.Offer>, Void> lookup = new SwingWorker<List<ParcelSourceFinder.Offer>, Void>() {
            @Override
            protected List<ParcelSourceFinder.Offer> doInBackground() throws IOException {
                return ParcelSourceFinder.find(bounds);
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                List<ParcelSourceFinder.Offer> offers;
                try {
                    offers = get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException ex) {
                    Logging.warn(ex);
                    parcelStatus.setText(tr("Could not reach OpenAddresses: {0}", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    return;
                }
                // Only what can be downloaded for the view: a file-only source is no use here.
                Choice compatible = null;
                int usable = 0;
                for (ParcelSourceFinder.Offer o : offers) {
                    if (o.getEsriSource() != null) {
                        Choice c = new Choice("oa:" + o.getSourceId(), o.toString(), o.getEsriSource());
                        parcels.add(c);
                        usable++;
                        if (compatible == null && o.getLicense() != null && o.getLicense().getStatus() == LicenseStatus.COMPATIBLE) {
                            compatible = c;
                        }
                    }
                }
                if (usable == 0) {
                    parcelStatus.setText(offers.isEmpty() ? tr("OpenAddresses has no parcel source for this area.")
                            : trn("OpenAddresses lists a parcel source here, but it cannot be downloaded for just this view.",
                                    "OpenAddresses lists {0} parcel sources here, but none can be downloaded for just this view.",
                                    offers.size(), offers.size()));
                } else {
                    // Most local first, but prefer a source whose licence is known to fit OSM.
                    parcels.pickDefault(compatible);
                    parcelStatus.setText(tr("From the OpenAddresses coverage map; a source may still have gaps."));
                }
                Window w = SwingUtilities.getWindowAncestor(parcelStatus);
                if (w != null) {
                    w.pack();
                }
            }
        };
        lookup.execute();

        ExtendedDialog dlg = new ExtendedDialog(MainApplication.getMainFrame(), tr("Download for current view"),
                tr("Download"), tr("Cancel"));
        dlg.setContent(panel, false);
        dlg.setButtonIcons("download", "cancel");
        int answer = dlg.showDialog().getValue();
        lookup.cancel(true);
        if (answer != 1) {
            return;
        }
        List<FeatureSource> sources = new ArrayList<>();
        for (SourceRow row : rows.values()) {
            row.save();
            if (row.selectedSource() != null) {
                sources.add(row.selectedSource());
            }
        }
        if (!sources.isEmpty()) {
            MainApplication.worker.submit(new DownloadTask(sources, bounds));
        }
    }

    private static final String OWN = "own:";

    /** One source in a row's dropdown. */
    static final class Choice {
        /** Stable name for remembering the mapper's pick: nad, microsoft, oa:us/in/statewide, own:Name. */
        final String key;
        final String label;
        final FeatureSource source;

        Choice(String key, String label, FeatureSource source) {
            this.key = key;
            this.label = label;
            this.source = source;
        }

        static Choice of(CustomSource cs) {
            return new Choice(OWN + cs.getName(), cs.getName(), cs.toFeatureSource());
        }

        boolean isOwn() {
            return key.startsWith(OWN);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A row of the dialog: tick to download, pick the source, see its licence. */
    private static final class SourceRow {
        final JCheckBox box;
        final JComboBox<Choice> combo = new JComboBox<>();
        final LicenseBadge badge = new LicenseBadge(null);
        final String prefOn;
        final String prefChoice;
        /** Set once the mapper picks from the dropdown, so late OpenAddresses results leave it alone. */
        boolean picked;
        boolean selecting;

        SourceRow(String label, String tooltip, String prefOn, String prefChoice) {
            this.prefOn = prefOn;
            this.prefChoice = prefChoice;
            box = new JCheckBox(label, Config.getPref().getBoolean(prefOn, true));
            box.setToolTipText(tooltip);
            box.setEnabled(false);
            combo.setEnabled(false);
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    LicenseAssessment a = value instanceof Choice ? ((Choice) value).source.getLicense() : null;
                    // Badges in the open list only: the closed box has the badge beside it already.
                    if (a != null && index >= 0) {
                        setText("<html>" + Utils.escapeReservedCharactersHTML(value.toString()) + " &nbsp; " + LicenseBadge.inline(a) + "</html>");
                    }
                    // The renderer is reused for every row, so always reset the tooltip.
                    setToolTipText(a == null ? null : a.toolTipHtml());
                    return this;
                }
            });
            combo.addActionListener(e -> {
                Choice c = (Choice) combo.getSelectedItem();
                badge.setAssessment(c == null ? null : c.source.getLicense());
                if (!selecting) {
                    picked = true;
                }
            });
        }

        void add(Choice c) {
            selecting = true;
            try {
                combo.addItem(c);
            } finally {
                selecting = false;
            }
            box.setEnabled(true);
            combo.setEnabled(true);
        }

        /** A source the mapper just added: there, chosen and ticked. */
        void addAndUse(Choice c) {
            add(c);
            combo.setSelectedItem(c);
            box.setSelected(true);
        }

        /**
         * Unless the mapper already picked: their last choice if offered, else their own source
         * (they added it for a reason), else {@code fallback}, else the first.
         */
        void pickDefault(Choice fallback) {
            if (picked || combo.getItemCount() == 0) {
                return;
            }
            String remembered = Config.getPref().get(prefChoice, null);
            Choice pick = null;
            Choice own = null;
            for (int i = 0; i < combo.getItemCount(); i++) {
                Choice c = combo.getItemAt(i);
                if (c.key.equals(remembered)) {
                    pick = c;
                }
                if (own == null && c.isOwn()) {
                    own = c;
                }
            }
            if (pick == null) {
                pick = own != null ? own : fallback != null ? fallback : combo.getItemAt(0);
            }
            selecting = true;
            try {
                combo.setSelectedItem(pick);
            } finally {
                selecting = false;
            }
        }

        void addTo(JPanel panel, GridBagConstraints gc) {
            JPanel row = new JPanel(new GridBagLayout());
            GridBagConstraints rc = new GridBagConstraints();
            rc.anchor = GridBagConstraints.WEST;
            row.add(box, rc);
            rc.weightx = 1;
            rc.fill = GridBagConstraints.HORIZONTAL;
            row.add(combo, rc);
            gc.gridx = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(row, gc);
            gc.gridx = 1;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            panel.add(badge, gc);
        }

        /** The source to download, or null when the row is unticked or empty. */
        FeatureSource selectedSource() {
            Choice c = (Choice) combo.getSelectedItem();
            return box.isEnabled() && box.isSelected() && c != null ? c.source : null;
        }

        void save() {
            if (!box.isEnabled()) {
                return;
            }
            Config.getPref().putBoolean(prefOn, box.isSelected());
            Choice c = (Choice) combo.getSelectedItem();
            if (c != null) {
                Config.getPref().put(prefChoice, c.key);
            }
        }
    }

    /** Downloads each source and adds or merges its layer. */
    public static final class DownloadTask extends PleaseWaitRunnable {
        private final List<FeatureSource> sources;
        /** OpenAddresses source to resolve in the background, or null. */
        private final String oaRef;
        private final Set<FeatureSource.Kind> oaKinds;
        private final Bounds bounds;
        private final List<OpenAddressesLayer> newLayers = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private boolean cancelled;

        DownloadTask(List<FeatureSource> sources, Bounds bounds) {
            super(tr("Downloading"));
            this.sources = new ArrayList<>(sources);
            this.oaRef = null;
            this.oaKinds = EnumSet.noneOf(FeatureSource.Kind.class);
            this.bounds = bounds;
        }

        /**
         * Download the wanted layers of an OpenAddresses source: an id such as
         * us/az/maricopa, a URL, or a local source file.
         */
        public DownloadTask(String oaRef, Set<FeatureSource.Kind> kinds, Bounds bounds) {
            super(tr("Downloading {0}", oaRef));
            this.sources = new ArrayList<>();
            this.oaRef = oaRef;
            this.oaKinds = EnumSet.copyOf(kinds);
            this.bounds = bounds;
        }

        @Override
        protected void realRun() throws SAXException, IOException, OsmTransferException {
            ProgressMonitor pm = getProgressMonitor();
            if (oaRef != null) {
                pm.indeterminateSubTask(tr("Resolving source {0}", oaRef));
                for (FeatureSource s : OpenAddressesSourceReader.load(OpenAddressesSourceReader.resolve(oaRef),
                        AddressConflationPreferences.isExpandStreets())) {
                    if (oaKinds.contains(s.getKind())) {
                        sources.add(s);
                    }
                }
                if (sources.isEmpty()) {
                    messages.add(tr("{0} has no ESRI address, parcel or building layer that can be downloaded.", oaRef));
                    return;
                }
            }
            for (FeatureSource src : sources) {
                if (cancelled) {
                    return;
                }
                pm.indeterminateSubTask(tr("Downloading {0}", src.getName()));
                DataSet ds;
                try {
                    ds = src.getProtocol() == FeatureSource.Protocol.OGC_FEATURES
                            ? OgcFeatureClient.download(src, bounds, pm) : EsriFeatureClient.download(src, bounds, pm);
                } catch (IOException e) {
                    // One county server being down must not cost the user the other layers.
                    Logging.warn(e);
                    failures.add(tr("{0}: {1}", src.getName(), EsriFeatureClient.describe(e)));
                    continue;
                }
                int n = src.getKind() == FeatureSource.Kind.ADDRESSES ? ds.getNodes().size() : ds.getWays().size() + ds.getRelations().size();
                OpenAddressesReader.Layer kind = src.getKind() == FeatureSource.Kind.PARCELS ? OpenAddressesReader.Layer.PARCELS
                        : src.getKind() == FeatureSource.Kind.BUILDINGS ? OpenAddressesReader.Layer.BUILDINGS : OpenAddressesReader.Layer.ADDRESSES;
                newLayers.add(new OpenAddressesLayer(ds, src.getName(), null, kind, src.getLicense()));
                messages.add(tr("{0}: {1} features", src.getName(), n));
            }
        }

        @Override
        protected void finish() {
            // Adding a data layer makes it active; keep the OSM layer the user was editing.
            Layer active = MainApplication.getLayerManager().getActiveLayer();
            for (OpenAddressesLayer l : newLayers) {
                OpenAddressesLayer existing = null;
                for (Layer layer : MainApplication.getLayerManager().getLayers()) {
                    if (layer instanceof OpenAddressesLayer && layer.getName().equals(l.getName())) {
                        existing = (OpenAddressesLayer) layer;
                        break;
                    }
                }
                if (existing != null) {
                    existing.mergeFrom(l);
                    existing.invalidate();
                } else {
                    MainApplication.getLayerManager().addLayer(l);
                }
            }
            if (active != null && MainApplication.getLayerManager().containsLayer(active)) {
                MainApplication.getLayerManager().setActiveLayer(active);
            }
            if (!failures.isEmpty()) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Some downloads failed:") + "\n" + String.join("\n", failures), tr("Download"), JOptionPane.WARNING_MESSAGE);
            }
            if (!messages.isEmpty()) {
                Logging.info(String.join("; ", messages));
                if (newLayers.isEmpty() && failures.isEmpty()) {
                    JOptionPane.showMessageDialog(MainApplication.getMainFrame(), String.join("\n", messages), tr("Download addresses"), JOptionPane.WARNING_MESSAGE);
                }
            }
        }

        @Override
        protected void cancel() {
            cancelled = true;
        }
    }
}
