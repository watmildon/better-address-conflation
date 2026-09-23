// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
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
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
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
    private static final String PREF_NAD = "addressconflation.download.nad";
    private static final String PREF_MS_BUILDINGS = "addressconflation.download.msBuildings";
    private static final String PREF_PARCELS = "addressconflation.download.parcels";

    public DownloadSourceAction() {
        super(tr("Download..."), "download_in_view",
                tr("Download NAD address points, Microsoft building footprints and parcels for the current view"),
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

        JCheckBox nad = new JCheckBox(tr("Address points from the National Address Database (NAD)"),
                Config.getPref().getBoolean(PREF_NAD, true));
        JCheckBox msBuildings = new JCheckBox(tr("Microsoft building footprints, as placement hints"),
                Config.getPref().getBoolean(PREF_MS_BUILDINGS, true));
        nad.setToolTipText(tr("US address points for the current view, from Esri''s copy of the National Address Database"));
        msBuildings.setToolTipText(tr("Used only to position addresses where OSM has no building. Never imported."));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.gridy = 0;
        panel.add(nad, gc);
        gc.gridy++;
        panel.add(msBuildings, gc);

        // Parcels: offered once OpenAddresses has been asked what covers the view.
        JCheckBox parcels = new JCheckBox(tr("Parcels from"), false);
        parcels.setEnabled(false);
        parcels.setToolTipText(tr("Parcel boundaries decide which building each address belongs to. Never uploaded."));
        JComboBox<ParcelSourceFinder.Offer> parcelSource = new JComboBox<>();
        parcelSource.setEnabled(false);
        parcelSource.setToolTipText(tr("Parcel sources OpenAddresses lists for this area, most local first. "
                + "Only ESRI services can be downloaded for just the view."));
        JLabel parcelStatus = new JLabel(tr("Looking up parcel sources for this area..."));
        parcelSource.addActionListener(ev -> {
            ParcelSourceFinder.Offer o = (ParcelSourceFinder.Offer) parcelSource.getSelectedItem();
            boolean ok = o != null && o.getEsriSource() != null;
            parcels.setEnabled(ok);
            if (!ok) {
                parcels.setSelected(false);
            }
        });
        JPanel parcelRow = new JPanel(new GridBagLayout());
        GridBagConstraints rc = new GridBagConstraints();
        rc.anchor = GridBagConstraints.WEST;
        parcelRow.add(parcels, rc);
        rc.weightx = 1;
        rc.fill = GridBagConstraints.HORIZONTAL;
        parcelRow.add(parcelSource, rc);
        gc.gridy++;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(parcelRow, gc);
        gc.gridy++;
        panel.add(parcelStatus, gc);
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
                if (offers.isEmpty()) {
                    parcelStatus.setText(tr("OpenAddresses has no parcel source for this area."));
                    return;
                }
                offers.forEach(parcelSource::addItem);
                parcelSource.setEnabled(true);
                ParcelSourceFinder.Offer first = offers.stream().filter(o -> o.getEsriSource() != null).findFirst().orElse(offers.get(0));
                parcelSource.setSelectedItem(first);
                parcels.setSelected(parcels.isEnabled() && Config.getPref().getBoolean(PREF_PARCELS, true));
                parcelStatus.setText(tr("From the OpenAddresses coverage map; a source may still have gaps."));
                Window w = SwingUtilities.getWindowAncestor(parcelSource);
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
        Config.getPref().putBoolean(PREF_NAD, nad.isSelected());
        Config.getPref().putBoolean(PREF_MS_BUILDINGS, msBuildings.isSelected());
        if (parcels.isEnabled()) {
            Config.getPref().putBoolean(PREF_PARCELS, parcels.isSelected());
        }
        List<EsriFeatureSource> sources = new ArrayList<>();
        if (nad.isSelected()) {
            sources.add(EsriFeatureSource.nad());
        }
        if (msBuildings.isSelected()) {
            sources.add(EsriFeatureSource.microsoftBuildings());
        }
        ParcelSourceFinder.Offer offer = (ParcelSourceFinder.Offer) parcelSource.getSelectedItem();
        if (parcels.isSelected() && offer != null && offer.getEsriSource() != null) {
            sources.add(offer.getEsriSource());
        }
        if (!sources.isEmpty()) {
            MainApplication.worker.submit(new DownloadTask(sources, bounds));
        }
    }

    /** Downloads each source and adds or merges its layer. */
    public static final class DownloadTask extends PleaseWaitRunnable {
        private final List<EsriFeatureSource> sources;
        /** OpenAddresses source to resolve in the background, or null. */
        private final String oaRef;
        private final Set<EsriFeatureSource.Kind> oaKinds;
        private final Bounds bounds;
        private final List<OpenAddressesLayer> newLayers = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private boolean cancelled;

        DownloadTask(List<EsriFeatureSource> sources, Bounds bounds) {
            super(tr("Downloading"));
            this.sources = new ArrayList<>(sources);
            this.oaRef = null;
            this.oaKinds = EnumSet.noneOf(EsriFeatureSource.Kind.class);
            this.bounds = bounds;
        }

        /**
         * Download the wanted layers of an OpenAddresses source: an id such as
         * us/az/maricopa, a URL, or a local source file.
         */
        public DownloadTask(String oaRef, Set<EsriFeatureSource.Kind> kinds, Bounds bounds) {
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
                for (EsriFeatureSource s : OpenAddressesSourceReader.load(OpenAddressesSourceReader.resolve(oaRef),
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
            for (EsriFeatureSource src : sources) {
                if (cancelled) {
                    return;
                }
                pm.indeterminateSubTask(tr("Downloading {0}", src.getName()));
                DataSet ds;
                try {
                    ds = EsriFeatureClient.download(src, bounds, pm);
                } catch (IOException e) {
                    // One county server being down must not cost the user the other layers.
                    Logging.warn(e);
                    failures.add(tr("{0}: {1}", src.getName(), EsriFeatureClient.describe(e)));
                    continue;
                }
                int n = src.getKind() == EsriFeatureSource.Kind.ADDRESSES ? ds.getNodes().size() : ds.getWays().size() + ds.getRelations().size();
                OpenAddressesReader.Layer kind = src.getKind() == EsriFeatureSource.Kind.PARCELS ? OpenAddressesReader.Layer.PARCELS
                        : src.getKind() == EsriFeatureSource.Kind.BUILDINGS ? OpenAddressesReader.Layer.BUILDINGS : OpenAddressesReader.Layer.ADDRESSES;
                newLayers.add(new OpenAddressesLayer(ds, src.getName(), null, kind));
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
