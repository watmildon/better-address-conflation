// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.addressconflation.Fixtures;
import org.openstreetmap.josm.plugins.addressconflation.JosmTestSetup;

class StreetNamesTest {
    @RegisterExtension
    static JosmTestSetup josm = new JosmTestSetup();

    @Test
    void splitValuesTrimsAndDropsEmptiesAndDuplicates() {
        assertEquals(List.of("Elm Street", "Oak Street"), StreetNames.splitValues(" Elm  Street ;; Oak Street;Elm Street"));
        assertTrue(StreetNames.splitValues(null).isEmpty());
        assertTrue(StreetNames.splitValues(" ; ").isEmpty());
    }

    @Test
    void canonicalFoldAndExpand() {
        assertEquals("west olive avenue", StreetNames.canonical("  West  OLIVE Avenue "));
        assertEquals("calle pena", StreetNames.fold("calle peña"));
        assertNull(StreetNames.expand("улица ленина"));
        assertEquals(StreetNames.expand("west olive avenue"), StreetNames.expand("w olive ave"));
    }

    @Test
    void keySetsSpellingAsideButKeepsDifferentNamesApart() {
        assertEquals(StreetNames.key("West Olive Avenue"), StreetNames.key("W Olive Ave"));
        assertEquals(StreetNames.key("West Olive Avenue"), StreetNames.key("west  olive avenue"));
        assertEquals(StreetNames.key("Calle Peña"), StreetNames.key("Calle Pena"));
        assertNotEquals(StreetNames.key("North Olive Avenue"), StreetNames.key("South Olive Avenue"));
        assertNotEquals(StreetNames.key("улица Ленина"), StreetNames.key("улица Пушкина"));
    }

    @Test
    void streetKeyNeedsOneValue() {
        DataSet ds = new DataSet();
        Node one = Fixtures.node(ds, 0, 0, "addr:street=Elm St");
        Node two = Fixtures.node(ds, 0, 0, "addr:street=Elm Street;Oak Street");
        Node blank = Fixtures.node(ds, 0, 0, "addr:street= ");
        Node none = Fixtures.node(ds, 0, 0);
        assertEquals(StreetNames.key("Elm Street"), StreetNames.streetKey(one));
        assertNull(StreetNames.streetKey(two));
        assertNull(StreetNames.streetKey(blank));
        assertNull(StreetNames.streetKey(none));
        assertEquals("Elm Street", StreetNames.street(two));
        assertEquals("", StreetNames.street(none));
    }

    @Test
    void roadNamesIncludeAlternativesAndStreetRelations() {
        DataSet ds = new DataSet();
        Way w = Fixtures.way(ds, new String[] {"highway=residential", "name=West Olive Avenue", "alt_name=Olive Avenue",
            "name:es=Avenida Olivo", "name:etymology=an olive tree", "not:name=Olive Street"}, Fixtures.at(0, 0), Fixtures.at(100, 0));
        Relation r = new Relation();
        r.addMember(new RelationMember("street", w));
        ds.addPrimitive(r);
        Fixtures.tag(r, "type=associatedStreet", "name=Old Olive Road");
        List<String> names = StreetNames.roadNames(w);
        assertTrue(names.containsAll(List.of("West Olive Avenue", "Olive Avenue", "Avenida Olivo", "Old Olive Road")), names.toString());
        assertFalse(names.contains("an olive tree"));
        assertFalse(names.contains("Olive Street"));
        Set<String> keys = StreetNames.roadKeys(w);
        assertTrue(keys.contains(StreetNames.key("W Olive Ave")));
    }

    @Test
    void onlyStreetsAreRoads() {
        DataSet ds = new DataSet();
        assertTrue(StreetNames.isRoad(Fixtures.way(ds, new String[] {"highway=residential"}, Fixtures.at(0, 0), Fixtures.at(1, 0))));
        assertTrue(StreetNames.isRoad(Fixtures.way(ds, new String[] {"highway=service"}, Fixtures.at(0, 0), Fixtures.at(1, 0))));
        assertFalse(StreetNames.isRoad(Fixtures.way(ds, new String[] {"highway=footway"}, Fixtures.at(0, 0), Fixtures.at(1, 0))));
        assertFalse(StreetNames.isRoad(Fixtures.way(ds, new String[] {"building=yes"}, Fixtures.at(0, 0), Fixtures.at(1, 0))));
        assertTrue(StreetNames.isNameKey("name:en"));
        assertFalse(StreetNames.isNameKey("name:wikipedia"));
        assertFalse(StreetNames.isNameKey("not:name"));
    }
}
