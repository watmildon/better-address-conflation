// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
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
 * Download address points (and parcels) for the current map view from the
 * National Address Database or from any OpenAddresses source whose layers are
 * ESRI services. Results land in non-uploadable layers the conflation panel
 * picks up automatically.
 */
public class DownloadSourceAction extends JosmAction {
    public static final String NAD_CHOICE = "National Address Database (NAD)";
    private static final String PREF_RECENT = "addressconflation.download.recentSources";
    private static final int MAX_RECENT = 10;

    public DownloadSourceAction() {
        super(tr("Download addresses/parcels for view..."), "address-conflation",
                tr("Download NAD address points, or an OpenAddresses source's addresses and parcels, for the current view"),
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

        List<String> recent = new ArrayList<>(Config.getPref().getList(PREF_RECENT));
        JComboBox<String> sourceBox = new JComboBox<>();
        sourceBox.setEditable(true);
        sourceBox.addItem(NAD_CHOICE);
        for (String r : recent) {
            sourceBox.addItem(r);
        }
        JCheckBox addresses = new JCheckBox(tr("Addresses"), true);
        JCheckBox parcels = new JCheckBox(tr("Parcels (OpenAddresses sources only)"), true);
        JCheckBox buildings = new JCheckBox(tr("Building footprints, as hints"), true);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        panel.add(new JLabel(tr("Source: NAD, an OpenAddresses source id such as us/az/maricopa, a URL, or a local source file")), gc);
        gc.gridy++;
        gc.weightx = 1;
        panel.add(sourceBox, gc);
        gc.gridy++;
        gc.gridwidth = 1;
        panel.add(addresses, gc);
        gc.gridx = 1;
        panel.add(parcels, gc);
        gc.gridx = 0;
        gc.gridy++;
        gc.gridwidth = 2;
        panel.add(buildings, gc);

        ExtendedDialog dlg = new ExtendedDialog(MainApplication.getMainFrame(), tr("Download addresses/parcels for view"),
                tr("Download"), tr("Cancel"));
        dlg.setContent(panel);
        dlg.setButtonIcons("download", "cancel");
        if (dlg.showDialog().getValue() != 1) {
            return;
        }
        Object choice = sourceBox.getEditor().getItem();
        String ref = choice == null ? "" : choice.toString().trim();
        if (ref.isEmpty()) {
            return;
        }
        if (!NAD_CHOICE.equals(ref)) {
            recent.remove(ref);
            recent.add(0, ref);
            while (recent.size() > MAX_RECENT) {
                recent.remove(recent.size() - 1);
            }
            Config.getPref().putList(PREF_RECENT, recent);
        }
        MainApplication.worker.submit(new DownloadTask(ref, bounds, addresses.isSelected(), parcels.isSelected(), buildings.isSelected()));
    }

    /** Resolves the source reference, downloads each wanted layer, adds or merges layers. */
    static final class DownloadTask extends PleaseWaitRunnable {
        private final String ref;
        private final Bounds bounds;
        private final boolean wantAddresses;
        private final boolean wantParcels;
        private final boolean wantBuildings;
        private final List<OpenAddressesLayer> newLayers = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private boolean cancelled;

        DownloadTask(String ref, Bounds bounds, boolean wantAddresses, boolean wantParcels, boolean wantBuildings) {
            super(tr("Downloading addresses"));
            this.ref = ref;
            this.bounds = bounds;
            this.wantAddresses = wantAddresses;
            this.wantParcels = wantParcels;
            this.wantBuildings = wantBuildings;
        }

        @Override
        protected void realRun() throws SAXException, IOException, OsmTransferException {
            ProgressMonitor pm = getProgressMonitor();
            pm.indeterminateSubTask(tr("Resolving source {0}", ref));
            List<EsriFeatureSource> sources = new ArrayList<>();
            if (NAD_CHOICE.equals(ref)) {
                sources.add(EsriFeatureSource.nad());
            } else {
                sources.addAll(OpenAddressesSourceReader.load(OpenAddressesSourceReader.resolve(ref), AddressConflationPreferences.isExpandStreets()));
            }
            sources.removeIf(s -> (s.getKind() == EsriFeatureSource.Kind.ADDRESSES && !wantAddresses)
                    || (s.getKind() == EsriFeatureSource.Kind.PARCELS && !wantParcels)
                    || (s.getKind() == EsriFeatureSource.Kind.BUILDINGS && !wantBuildings));
            if (sources.isEmpty()) {
                messages.add(tr("{0} has no ESRI address, parcel or building layer that can be downloaded.", ref));
                return;
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
