// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.addressconflation;

import jakarta.json.spi.JsonProvider;

/**
 * The JSON provider, looked up once. Every {@code jakarta.json.Json.createX()} call looks
 * the provider up again through {@link java.util.ServiceLoader}, which scans (and verifies)
 * every jar on the class path; in JOSM, with many plugins installed, converting a few
 * thousand downloaded features that way took minutes. Use {@link #JSON} instead.
 */
public final class JsonSupport {
    public static final JsonProvider JSON = JsonProvider.provider();

    private JsonSupport() {
    }
}
