// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.tools.Http1Client;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Minimal JOSM subsystem initialization for unit tests.
 *
 * Usage:
 *   {@code @RegisterExtension}
 *   static JosmTestSetup josm = new JosmTestSetup();
 */
public class JosmTestSetup implements BeforeAllCallback {

    private static boolean initialized;

    @Override
    public void beforeAll(ExtensionContext context) {
        init();
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        Config.setPreferencesInstance(new MemoryPreferences());
        // PlatformHookWindows resolves the user data dir through Config.getDirs()
        // when ImageProvider searches for icons; Unix doesn't, so CI never noticed.
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
        // JOSM's HttpClient needs the factory MainApplication normally installs.
        HttpClient.setFactory(Http1Client::new);
        initialized = true;
    }

    /** Load a .osm file from the test classpath (test-data/ is a resource root). */
    public static DataSet loadDataSet(String resourceName) {
        try (InputStream is = JosmTestSetup.class.getResourceAsStream("/" + resourceName)) {
            if (is == null) {
                throw new IllegalStateException("Test resource not found on classpath: " + resourceName);
            }
            return OsmReader.parseDataSet(is, null);
        } catch (IllegalDataException | IOException e) {
            throw new IllegalStateException("Failed to load test data: " + resourceName, e);
        }
    }

    public static InputStream resource(String resourceName) {
        InputStream is = JosmTestSetup.class.getResourceAsStream("/" + resourceName);
        if (is == null) {
            throw new IllegalStateException("Test resource not found on classpath: " + resourceName);
        }
        return is;
    }
}
