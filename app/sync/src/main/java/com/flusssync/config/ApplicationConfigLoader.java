package com.flusssync.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
            return mapper.readValue(file.toFile(), ApplicationConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse application config " + file, e);
        }
    }
}
