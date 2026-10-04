// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.license;

import javax.swing.JLabel;

/**
 * A small label showing a source's licence status: a coloured dot and the status word,
 * never colour alone, with the details on hover.
 */
public class LicenseBadge extends JLabel {

    public LicenseBadge(LicenseAssessment assessment) {
        setAssessment(assessment);
    }

    /** Show this assessment; null hides the badge. */
    public final void setAssessment(LicenseAssessment assessment) {
        if (assessment == null) {
            setText("");
            setToolTipText(null);
            setVisible(false);
            return;
        }
        setText(html(assessment));
        setToolTipText(assessment.toolTipHtml());
        setVisible(true);
    }

    /** Badge markup for use inside other labels and list cells. */
    public static String html(LicenseAssessment a) {
        return "<html>" + inline(a) + "</html>";
    }

    /** Badge markup without the html element. */
    public static String inline(LicenseAssessment a) {
        return String.format("<font color=\"#%06x\">●</font> %s", a.getStatus().color().getRGB() & 0xFFFFFF, a.getStatus().label());
    }
}
