// SPDX-License-Identifier: MIT
package org.openstreetmap.josm.plugins.addressconflation.io;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/**
 * A local web server for service tests: a function from the request's path and query
 * ({@code /collections/x/items?bbox=...}) to {status, content type, body}.
 */
final class FakeServer implements AutoCloseable {
    private final HttpServer server;
    /** Every request's path and query, in order. */
    final List<String> requests = new CopyOnWriteArrayList<>();

    FakeServer(Function<String, Object[]> handler) throws IOException {
        this((request, accept) -> handler.apply(request));
    }

    /** A server whose answer also depends on the request's Accept header. */
    FakeServer(BiFunction<String, String, Object[]> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String request = ex.getRequestURI().getPath() + (q == null ? "" : "?" + q);
            requests.add(request);
            Object[] r = handler.apply(request, String.valueOf(ex.getRequestHeaders().getFirst("Accept")));
            if (r == null) {
                r = new Object[] {404, "application/json", "{\"code\":\"NotFound\"}"};
            }
            byte[] body = ((String) r[2]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", (String) r[1]);
            ex.sendResponseHeaders((Integer) r[0], body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    /** The server's base URL plus a path. */
    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    static Object[] json(String body) {
        return new Object[] {200, "application/json", body};
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
