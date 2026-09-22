// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.addressconflation.gui.AddressConflationPreferences;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/** File menu action: load an OpenAddresses addresses/parcels/buildings file as a layer. */
public class OpenAddressesImportAction extends JosmAction {
    private static final String PREF_LAST_DIR = "addressconflation.oa.lastdir";

    public OpenAddressesImportAction() {
        super(tr("Open OpenAddresses file..."), "address-conflation", tr("Load an OpenAddresses addresses or parcels file (line-delimited GeoJSON) as a layer"),
                null, true, "addressconflation/openaddresses", false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        JFileChooser fc = new JFileChooser(Config.getPref().get(PREF_LAST_DIR, System.getProperty("user.home")));
        fc.setFileFilter(new FileNameExtensionFilter(tr("OpenAddresses GeoJSON (*.geojson, *.json, *.ndjson)"), "geojson", "json", "ndjson"));
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(MainApplication.getMainFrame()) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Config.getPref().put(PREF_LAST_DIR, fc.getCurrentDirectory().getAbsolutePath());
        for (File f : fc.getSelectedFiles()) {
            load(f);
        }
    }

    private static void load(File file) {
        boolean expand = AddressConflationPreferences.isExpandStreets();
        SwingWorker<DataSet, Void> worker = new SwingWorker<DataSet, Void>() {
            @Override
            protected DataSet doInBackground() throws IOException {
                try (InputStream is = new FileInputStream(file)) {
                    return OpenAddressesReader.read(is, OpenAddressesReader.Layer.AUTO, expand);
                }
            }

            @Override
            protected void done() {
                try {
                    DataSet ds = get();
                    OpenAddressesReader.Layer kind = OpenAddressesReader.isParcelDataSet(ds)
                            ? OpenAddressesReader.Layer.PARCELS : OpenAddressesReader.Layer.ADDRESSES;
                    String name = (kind == OpenAddressesReader.Layer.PARCELS ? tr("OA parcels: {0}", file.getName()) : tr("OA addresses: {0}", file.getName()));
                    MainApplication.getLayerManager().addLayer(new OpenAddressesLayer(ds, name, file, kind));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Logging.error(ex);
                    JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                            tr("Could not read {0}: {1}", file.getName(), ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()),
                            tr("Open OpenAddresses file"), JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}
