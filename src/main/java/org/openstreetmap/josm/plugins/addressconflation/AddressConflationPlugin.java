// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import org.openstreetmap.josm.data.validation.OsmValidator;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.preferences.PreferenceSetting;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.addressconflation.gui.AddressConflationDialog;
import org.openstreetmap.josm.plugins.addressconflation.gui.AddressConflationPreferences;
import org.openstreetmap.josm.plugins.addressconflation.io.DownloadSourceAction;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesImportAction;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressOnOutbuildingTest;

/**
 * Better Address Conflation plugin for JOSM.
 *
 * Matches address points to buildings by way of parcels (or synthetic Voronoi
 * cells when no parcel layer is available), ranks the buildings inside each
 * cell so the primary structure wins over garages and sheds, and presents the
 * results in review buckets that can be applied one at a time or in bulk.
 */
public class AddressConflationPlugin extends Plugin {

    public AddressConflationPlugin(PluginInformation info) {
        super(info);
        MainMenu.add(MainApplication.getMenu().fileMenu, new OpenAddressesImportAction(), false, 0);
        MainMenu.add(MainApplication.getMenu().fileMenu, new DownloadSourceAction(), false, 1);
        OsmValidator.addTest(AddressOnOutbuildingTest.class);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            newFrame.addToggleDialog(new AddressConflationDialog());
        }
    }

    @Override
    public PreferenceSetting getPreferenceSetting() {
        return new AddressConflationPreferences();
    }
}
