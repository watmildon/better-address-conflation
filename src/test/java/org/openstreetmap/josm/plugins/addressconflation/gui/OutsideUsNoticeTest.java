// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;
import org.openstreetmap.josm.tools.Territories;

/** The US check behind the "outside the United States" notice, on JOSM's own boundary data. */
class OutsideUsNoticeTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @BeforeAll
    static void boundaries() {
        Territories.initializeInternalData();
    }

    @Test
    void usAndItsTerritoriesCount() {
        assertTrue(OutsideUsNotice.isInUs(new LatLon(40.0424843, -86.9151545)), "Crawfordsville IN");
        assertTrue(OutsideUsNotice.isInUs(new LatLon(61.2181, -149.9003)), "Anchorage AK");
        assertTrue(OutsideUsNotice.isInUs(new LatLon(18.4655, -66.1057)), "San Juan PR");
        assertTrue(OutsideUsNotice.isInUs(new LatLon(13.4443, 144.7937)), "Hagatna GU");
    }

    @Test
    void elsewhereDoesNot() {
        assertFalse(OutsideUsNotice.isInUs(new LatLon(43.6532, -79.3832)), "Toronto");
        assertFalse(OutsideUsNotice.isInUs(new LatLon(42.3149, -83.0364)), "Windsor ON, across from Detroit");
        assertFalse(OutsideUsNotice.isInUs(new LatLon(48.8566, 2.3522)), "Paris");
        assertFalse(OutsideUsNotice.isInUs(new LatLon(19.4326, -99.1332)), "Mexico City");
    }
}
