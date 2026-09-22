// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.openstreetmap.josm.tools.I18n.tr;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.plugins.addressconflation.engine.ConflationSettings;

/**
 * Warns when an address sits on a garage, shed, carport or roof that carries
 * no POI tag. That is the classic conflation mistake this plugin exists to
 * avoid, and it is worth catching after any tool put it there.
 */
public class AddressOnOutbuildingTest extends Test {
    /** Error code, unique within JOSM's validator space. */
    public static final int CODE = 47100;

    public AddressOnOutbuildingTest() {
        super(tr("Address on outbuilding"), tr("Finds addresses on garages, sheds, carports and roofs without a POI tag"));
    }

    @Override
    public void visit(Way w) {
        check(w);
    }

    @Override
    public void visit(Relation r) {
        check(r);
    }

    private void check(OsmPrimitive p) {
        if (!p.isUsable() || !p.hasKey("addr:housenumber") || !p.hasKey("building")) {
            return;
        }
        ConflationSettings s = new ConflationSettings();
        if (s.weightFor(p) >= 1.0) {
            return;
        }
        errors.add(TestError.builder(this, Severity.WARNING, CODE)
                .message(tr("Address on outbuilding"), tr("{0} carries an address but no amenity, shop or office tag", "building=" + p.get("building")))
                .primitives(p)
                .build());
    }
}
