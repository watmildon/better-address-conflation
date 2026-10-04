// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.license;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Objects;

import org.openstreetmap.josm.tools.Utils;

/** A source's licence status and why: the basis, what the source declares, and where to read more. */
public final class LicenseAssessment {
    /** OSM wiki guide to asking a data owner for permission, with letter templates. */
    public static final String GETTING_PERMISSION = "https://wiki.openstreetmap.org/wiki/Import/Getting_permission";

    private final LicenseStatus status;
    private final String basis;
    private final String declared;
    private final String link;

    /**
     * @param status   the verdict
     * @param basis    why, in a few words ("Declared: CC0 1.0", "Maryland law ...")
     * @param declared what the source itself declares, or null
     * @param link     licence, terms or permission page, or null
     */
    public LicenseAssessment(LicenseStatus status, String basis, String declared, String link) {
        this.status = Objects.requireNonNull(status);
        this.basis = basis;
        this.declared = declared;
        this.link = link;
    }

    public LicenseStatus getStatus() {
        return status;
    }

    public String getBasis() {
        return basis;
    }

    public String getDeclared() {
        return declared;
    }

    public String getLink() {
        return link;
    }

    /** Hover text: status, meaning, basis, declared licence, link. */
    public String toolTipHtml() {
        return "<html>" + toolTipBody() + "</html>";
    }

    /** {@link #toolTipHtml()} without the html element, to append to another tooltip. */
    public String toolTipBody() {
        StringBuilder sb = new StringBuilder("<b>").append(Utils.escapeReservedCharactersHTML(tr("Licence: {0}", status.label()))).append("</b><br>")
                .append(Utils.escapeReservedCharactersHTML(status.explanation()));
        if (basis != null) {
            sb.append("<br>").append(Utils.escapeReservedCharactersHTML(basis));
        }
        if (declared != null && !declared.equals(basis)) {
            sb.append("<br>").append(Utils.escapeReservedCharactersHTML(tr("The source declares: {0}", declared)));
        }
        if (link != null) {
            sb.append("<br>").append(Utils.escapeReservedCharactersHTML(link));
        }
        if (status == LicenseStatus.NEEDS_WAIVER || status == LicenseStatus.NOT_COMPATIBLE || status == LicenseStatus.UNKNOWN) {
            sb.append("<br>").append(Utils.escapeReservedCharactersHTML(tr("Asking for permission: {0}", GETTING_PERMISSION)));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return status + (basis != null ? " (" + basis + ")" : "");
    }
}
