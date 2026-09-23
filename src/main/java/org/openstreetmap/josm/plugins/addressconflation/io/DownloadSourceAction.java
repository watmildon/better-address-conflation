// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

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
 * Download National Address Database points and Microsoft building footprints
 * (as placement hints) for the current map view. Results land in
 * non-uploadable layers the analysis popup picks by default.
 *
 * {@link DownloadTask} can also download the layers of an OpenAddresses source
 * (by id, URL or file). That path has no UI yet.
 */
public class DownloadSourceAction extends JosmAction {
    private static final String PREF_NAD = "addressconflation.download.nad";
    private static final String PREF_MS_BUILDINGS = "addressconflation.download.msBuildings";

    public DownloadSourceAction() {
        super(tr("Download..."), "download_in_view",
                tr("Download NAD address points and Microsoft building footprints for the current view"),
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

        ExtendedDialog dlg = new ExtendedDialog(MainApplication.getMainFrame(), tr("Download for current view"),
                tr("Download"), tr("Cancel"));
        dlg.setContent(panel, false);
        dlg.setButtonIcons("download", "cancel");
        if (dlg.showDialog().getValue() != 1) {
            return;
        }
        Config.getPref().putBoolean(PREF_NAD, nad.isSelected());
        Config.getPref().putBoolean(PREF_MS_BUILDINGS, msBuildings.isSelected());
        List<EsriFeatureSource> sources = new ArrayList<>();
        if (nad.isSelected()) {
            sources.add(EsriFeatureSource.nad());
        }
        if (msBuildings.isSelected()) {
            sources.add(EsriFeatureSource.microsoftBuildings());
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
                DataSet ds = EsriFeatureClient.download(src, bounds, pm);
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
            if (!messages.isEmpty()) {
                Logging.info(String.join("; ", messages));
                if (newLayers.isEmpty()) {
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
