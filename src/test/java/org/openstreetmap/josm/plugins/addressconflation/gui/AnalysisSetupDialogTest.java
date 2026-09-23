// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesLayer;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesReader;
import org.openstreetmap.josm.spi.preferences.Config;

/** The analysis popup preselects layers the plugin downloaded. */
class AnalysisSetupDialogTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @TempDir
    static Path home;

    /** OsmDataLayer's static init wants a cache directory. */
    @BeforeAll
    static void dirs() {
        System.setProperty("josm.home", home.toString());
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
    }

    private final OsmDataLayer edit = new OsmDataLayer(new DataSet(), "Data Layer 1", null);
    private final OsmDataLayer namedAddr = new OsmDataLayer(new DataSet(), "county addresses", null);
    private final OsmDataLayer nad = new OpenAddressesLayer(new DataSet(), "National Address Database", null, OpenAddressesReader.Layer.ADDRESSES);
    private final OsmDataLayer ms = new OpenAddressesLayer(new DataSet(), "Microsoft building footprints", null, OpenAddressesReader.Layer.BUILDINGS);
    private final OsmDataLayer mapWithAi = new OsmDataLayer(new DataSet(), "MapWithAI", null);

    @Test
    void downloadedAddressLayerWins() {
        assertSame(nad, AnalysisSetupDialog.defaultAddressLayer(List.of(edit, namedAddr, ms, nad), edit));
    }

    @Test
    void addressFallsBackToNameThenAnyLayerThenEdit() {
        assertSame(namedAddr, AnalysisSetupDialog.defaultAddressLayer(List.of(mapWithAi, edit, namedAddr), edit));
        assertSame(mapWithAi, AnalysisSetupDialog.defaultAddressLayer(List.of(edit, mapWithAi), edit));
        assertSame(edit, AnalysisSetupDialog.defaultAddressLayer(List.of(edit), edit));
    }

    @Test
    void downloadedFootprintsAreTheDefaultHints() {
        assertSame(ms, AnalysisSetupDialog.defaultHintLayer(List.of(mapWithAi, edit, ms, nad), edit));
        assertSame(mapWithAi, AnalysisSetupDialog.defaultHintLayer(List.of(edit, mapWithAi, nad), edit));
        assertNull(AnalysisSetupDialog.defaultHintLayer(List.of(edit, nad), edit));
    }

    @Test
    void noParcelLayerMeansVoronoi() {
        assertNull(AnalysisSetupDialog.defaultParcelLayer(List.of(edit, nad, ms)));
    }
}
