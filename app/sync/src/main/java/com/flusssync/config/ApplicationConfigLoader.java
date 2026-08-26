package com.flusssync.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.apache.fluss.utils.TimeUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads {@code config/apps/sync/application.yaml}, falling back to defaults if absent. */
public final class ApplicationConfigLoader {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ApplicationConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            return ApplicationConfig.defaults();
        }
        try {
            ApplicationConfig config = mapper.readValue(file.toFile(), ApplicationConfig.class);
            validate(config, file);
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse application config " + file, e);
        }
    }

    private void validate(ApplicationConfig config, Path file) {
        try {
            TimeUtils.parseDuration(config.spec.lakehouse.defaultFreshness);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "spec.lakehouse.defaultFreshness '" + config.spec.lakehouse.defaultFreshness
                            + "' is not a valid duration in " + file, e);
        }
    }
}
