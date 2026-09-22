// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.addressconflation.io.StreetExpander;

class StreetExpanderTest {
    @Test
    void expandsCountyStyleNames() {
        assertEquals("North 56th Drive", StreetExpander.expand("N 56TH DR"));
        assertEquals("West Olive Avenue", StreetExpander.expand("W OLIVE AVE"));
        assertEquals("Saint John Road", StreetExpander.expand("ST JOHN RD"));
        assertEquals("Main Street", StreetExpander.expand("MAIN ST"));
        assertEquals("East Campo Bello Drive", StreetExpander.expand("E CAMPO BELLO DR"));
        assertEquals("North 52nd Avenue", StreetExpander.expand("N 52ND AVE"));
        assertEquals("Camino Del Sur Northeast", StreetExpander.expand("CAMINO DEL SUR NE"));
        assertEquals("McDowell Road", StreetExpander.expand("MCDOWELL RD"));
    }
}
