// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.swing.Action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.plugins.addressconflation.io.DownloadSourceAction;
import org.openstreetmap.josm.plugins.addressconflation.io.OpenAddressesImportAction;

/** SideButton rejects actions without an icon, which crashes the dialog at map-frame init. */
class ActionIconTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void downloadActionHasIcon() {
        assertNotNull(new DownloadSourceAction().getValue(Action.SMALL_ICON));
    }

    @Test
    void openAddressesActionHasIcon() {
        assertNotNull(new OpenAddressesImportAction().getValue(Action.SMALL_ICON));
    }
}
