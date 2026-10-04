// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * {@code Json.createX()} looks the JSON provider up through ServiceLoader on every call.
 * Called per downloaded feature it made a view of Columbus, IN take over ten minutes
 * instead of a second. Plugin code must use {@link JsonSupport#JSON}.
 */
class JsonProviderUseTest {
    private static final Pattern DIRECT = Pattern.compile("(?<![\\w.])Json\\.create");

    @Test
    void noDirectJsonFactoryCalls() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readAllLines(p).stream().anyMatch(l -> !l.trim().startsWith("*") && DIRECT.matcher(l).find());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(Path::toString).collect(Collectors.toList());
        }
        assertEquals(List.of(), offenders, "use JsonSupport.JSON instead of Json.create...");
    }
}
