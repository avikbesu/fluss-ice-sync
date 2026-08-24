package com.flusssync.health;

import com.flusssync.config.ApplicationConfig;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * Exposes {@code GET <health.path>} reporting whether the app is healthy,
 * per the design doc's Health checks section.
 */
public final class HealthServer {

    private final HttpServer server;

    public HealthServer(ApplicationConfig.Health config, BooleanSupplier healthy) throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.port), 0);
        server.createContext(config.path, exchange -> {
            boolean ok = healthy.getAsBoolean();
            byte[] body = (ok ? "OK" : "UNHEALTHY").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(null);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
