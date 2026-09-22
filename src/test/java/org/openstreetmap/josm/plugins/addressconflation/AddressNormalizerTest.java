// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.addressconflation.engine.AddressNormalizer;
import org.openstreetmap.josm.plugins.addressconflation.model.ExistingKind;

class AddressNormalizerTest {

    private static Map<String, String> addr(String hn, String street, String unit) {
        Map<String, String> m = new HashMap<>();
        m.put("addr:housenumber", hn);
        m.put("addr:street", street);
        if (unit != null) {
            m.put("addr:unit", unit);
        }
        return m;
    }

    @Test
    void normalizesAbbreviations() {
        assertEquals("west olive avenue", AddressNormalizer.normalizeStreet("W Olive Ave"));
        assertEquals("west olive avenue", AddressNormalizer.normalizeStreet("West Olive Avenue"));
        assertEquals("north 56th drive", AddressNormalizer.normalizeStreet("N 56TH DR"));
        assertEquals("saint johns road", AddressNormalizer.normalizeStreet("Saint John's Road"));
    }

    @Test
    void comparesExistingAddresses() {
        assertEquals(ExistingKind.IDENTICAL, AddressNormalizer.compare(addr("5201", "West Olive Avenue", null), addr("5201", "West Olive Avenue", null)));
        assertEquals(ExistingKind.STREET_VARIANT, AddressNormalizer.compare(addr("5201", "West Olive Avenue", null), addr("5201", "W Olive Ave", null)));
        assertEquals(ExistingKind.UNIT_DIFF, AddressNormalizer.compare(addr("5201", "West Olive Avenue", "114"), addr("5201", "West Olive Avenue", null)));
        assertEquals(ExistingKind.STREET_DIFF, AddressNormalizer.compare(addr("5201", "West Olive Avenue", null), addr("5201", "West Butler Drive", null)));
        assertNull(AddressNormalizer.compare(addr("5203", "West Olive Avenue", null), addr("5201", "West Olive Avenue", null)));
    }

    @Test
    void exactKeyIgnoresNonAddrTags() {
        Map<String, String> a = addr("1", "Main Street", null);
        Map<String, String> b = addr("1", "Main Street", null);
        b.put("source", "x");
        assertEquals(AddressNormalizer.exactKey(a), AddressNormalizer.exactKey(b));
    }
}
