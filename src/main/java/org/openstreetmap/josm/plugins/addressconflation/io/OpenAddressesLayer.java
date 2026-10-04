// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.io.File;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.addressconflation.license.LicenseAssessment;

/** A data layer holding OpenAddresses data. Never uploadable: parcels do not belong in OSM. */
public class OpenAddressesLayer extends OsmDataLayer {
    private final OpenAddressesReader.Layer kind;
    private final LicenseAssessment license;

    public OpenAddressesLayer(DataSet data, String name, File file, OpenAddressesReader.Layer kind) {
        this(data, name, file, kind, null);
    }

    /** @param license the source's licence verdict, shown with the layer; null when unknown */
    public OpenAddressesLayer(DataSet data, String name, File file, OpenAddressesReader.Layer kind, LicenseAssessment license) {
        super(data, name, file);
        this.kind = kind;
        this.license = license;
        setUploadDiscouraged(true);
    }

    /** The source's licence verdict, or null when it was not assessed. */
    public LicenseAssessment getLicense() {
        return license;
    }

    @Override
    public String getToolTipText() {
        String base = super.getToolTipText();
        if (license == null) {
            return base;
        }
        // Keep the licence with the data after the download dialog is gone.
        return base.replaceFirst("</html>$", "") + "<br><br>" + license.toolTipBody() + "</html>";
    }

    public OpenAddressesReader.Layer getKind() {
        return kind;
    }

    @Override
    public boolean isUploadable() {
        // Address layers are consumed by the conflation dialog; parcel layers must never be uploaded.
        return false;
    }

    @Override
    public boolean requiresUploadToServer() {
        return false;
    }
}
