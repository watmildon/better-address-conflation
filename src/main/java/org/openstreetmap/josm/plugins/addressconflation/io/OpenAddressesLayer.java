// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.io.File;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/** A data layer holding OpenAddresses data. Never uploadable: parcels do not belong in OSM. */
public class OpenAddressesLayer extends OsmDataLayer {
    private final OpenAddressesReader.Layer kind;

    public OpenAddressesLayer(DataSet data, String name, File file, OpenAddressesReader.Layer kind) {
        super(data, name, file);
        this.kind = kind;
        setUploadDiscouraged(true);
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
