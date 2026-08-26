package com.flusssync.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationConfigLoaderTest {

    private final ApplicationConfigLoader loader = new ApplicationConfigLoader();

    @Test
    void loadsExampleApplicationConfig() {
        ApplicationConfig config = loader.load(Path.of("../../config/apps/sync/application.yaml"));

        assertThat(config.spec.parsing.nullLiteral).isEqualTo("");
        assertThat(config.spec.parsing.timestampFormat).isEqualTo("yyyy-MM-dd'T'HH:mm:ss");
        assertThat(config.spec.retention.enabled).isTrue();
        assertThat(config.spec.retention.days).isEqualTo(15);
        assertThat(config.spec.health.enabled).isTrue();
        assertThat(config.spec.health.port).isEqualTo(8080);
        assertThat(config.spec.lakehouse.enabledByDefault).isFalse();
        assertThat(config.spec.lakehouse.defaultFreshness).isEqualTo("30s");
    }

    @Test
    void fallsBackToDefaultsWhenFileMissing() {
        ApplicationConfig config = loader.load(Path.of("/does/not/exist.yaml"));

        assertThat(config.spec.retention.days).isEqualTo(15);
        assertThat(config.spec.health.port).isEqualTo(8080);
        assertThat(config.spec.lakehouse.enabledByDefault).isFalse();
        assertThat(config.spec.lakehouse.defaultFreshness).isEqualTo("30s");
    }

    @Test
    void rejectsInvalidDefaultFreshness(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad-application.yaml");
        Files.writeString(file, """
                apiVersion: fluss-ice-sync.io/v1
                kind: ApplicationConfig
                spec:
                  lakehouse:
                    defaultFreshness: "30x"
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultFreshness");
    }
}
