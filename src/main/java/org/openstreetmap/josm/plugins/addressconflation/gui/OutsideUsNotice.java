// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Territories;

/**
 * The data sources and matching rules are built around the US. Outside it, say so before a
 * download or an analysis, once per session.
 */
public final class OutsideUsNotice {
    /** The US and its territories, as JOSM's boundary data codes them. */
    static final List<String> US_CODES = Collections.unmodifiableList(Arrays.asList("US", "PR", "GU", "VI", "AS", "MP", "UM"));

    /** Set once the mapper chose to go ahead; JOSM restarts reset it. */
    private static boolean acknowledged;

    private OutsideUsNotice() {
    }

    /** True when the point is in the US or a US territory, and when JOSM cannot tell. */
    static boolean isInUs(LatLon ll) {
        try {
            if (Territories.getKnownIso3166Codes().isEmpty()) {
                return true;
            }
            for (String code : US_CODES) {
                if (Territories.getKnownIso3166Codes().contains(code) && Territories.isIso3166Code(code, ll)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // Boundaries not loaded (JOSM still starting): no reason to nag.
            Logging.trace(e);
            return true;
        }
    }

    /**
     * Ask before working on an area outside the US. Asks at most until the mapper says to go
     * ahead once; after that, for the rest of the session, always true.
     *
     * @param where a point in the area about to be downloaded or analyzed, or null
     * @return true to go ahead
     */
    public static boolean confirm(LatLon where) {
        if (acknowledged || where == null || isInUs(where)) {
            return true;
        }
        ExtendedDialog dlg = new ExtendedDialog(MainApplication.getMainFrame(), tr("Outside the United States"),
                tr("Continue"), tr("Cancel"));
        dlg.setContent("<html><body style=\"width: 380px\">"
                + tr("This area looks to be outside the United States.") + "<br><br>"
                + tr("Better Address Conflation is built around US data: the National Address Database and the "
                        + "Microsoft building footprints it downloads cover the US only, parcel sources come from "
                        + "OpenAddresses'' US listings, and street-name and unit handling follow US conventions. "
                        + "Matching elsewhere may work, but expect more for you to check.") + "<br><br>"
                + tr("Continue anyway? You will not be asked again until JOSM restarts.")
                + "</body></html>");
        dlg.setButtonIcons("ok", "cancel");
        dlg.setIcon(JOptionPane.WARNING_MESSAGE);
        if (dlg.showDialog().getValue() == 1) {
            acknowledged = true;
            return true;
        }
        return false;
    }
}
