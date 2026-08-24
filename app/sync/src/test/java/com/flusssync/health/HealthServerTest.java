package com.flusssync.health;

import com.flusssync.config.ApplicationConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HealthServerTest {

    @Test
    void reportsHealthyOrUnhealthyBasedOnSupplier() throws Exception {
        int port = freePort();
        ApplicationConfig.Health config = new ApplicationConfig.Health();
        config.port = port;
        config.path = "/healthz";

        AtomicBoolean healthy = new AtomicBoolean(true);
        HealthServer server = new HealthServer(config, healthy::get);
        server.start();
        try {
            assertThat(statusCode(port)).isEqualTo(200);

            healthy.set(false);
            assertThat(statusCode(port)).isEqualTo(503);
        } finally {
            server.stop();
        }
    }

    private int statusCode(int port) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create("http://localhost:" + port + "/healthz").toURL().openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
