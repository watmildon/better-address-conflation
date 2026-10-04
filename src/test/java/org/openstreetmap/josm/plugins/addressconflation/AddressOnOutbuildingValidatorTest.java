// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.validation.AddressOnOutbuildingTest;

class AddressOnOutbuildingValidatorTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void flagsBareOutbuildingsOnly() {
        DataSet ds = new DataSet();
        Way garage = Fixtures.rect(ds, 0, 0, 6, 6, Fixtures.concat(Fixtures.addr("12", "West Olive Avenue"), "building=garage"));
        Fixtures.rect(ds, 20, 0, 12, 10, Fixtures.concat(Fixtures.addr("14", "West Olive Avenue"), "building=house"));
        Fixtures.rect(ds, 40, 0, 20, 20, Fixtures.concat(Fixtures.addr("16", "West Olive Avenue"), "building=roof", "amenity=fuel"));
        Fixtures.rect(ds, 60, 0, 6, 6, "building=shed");
        AddressOnOutbuildingTest test = new AddressOnOutbuildingTest();
        test.startTest(null);
        test.visit(ds.allPrimitives());
        test.endTest();
        assertEquals(1, test.getErrors().size(), test.getErrors().toString());
        assertEquals(garage, test.getErrors().get(0).getPrimitives().iterator().next());
    }
}
