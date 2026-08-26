package com.flusssync.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.apache.fluss.utils.TimeUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads every {@code *.yaml} file under a directory as a {@link SyncSourceConfig}. */
public final class SyncSourceConfigLoader {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public List<SyncSourceConfig> loadAll(Path directory) {
        List<SyncSourceConfig> configs = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return configs;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.yaml")) {
            for (Path file : stream) {
                configs.add(load(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list source configs in " + directory, e);
        }
        return configs;
    }

    public SyncSourceConfig load(Path file) {
        try {
            SyncSourceConfig config = mapper.readValue(file.toFile(), SyncSourceConfig.class);
            validate(config, file);
            return config;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse SyncSource config " + file, e);
        }
    }

    private void validate(SyncSourceConfig config, Path file) {
        if (config.metadata == null || config.metadata.name == null || config.metadata.name.isBlank()) {
            throw new IllegalArgumentException("metadata.name is required in " + file);
        }
        if (config.spec == null || config.spec.watch == null || config.spec.watch.path == null) {
            throw new IllegalArgumentException("spec.watch.path is required in " + file);
        }
        if (config.spec.format == null || config.spec.format.columns == null
                || config.spec.format.columns.isEmpty()) {
            throw new IllegalArgumentException("spec.format.columns is required in " + file);
        }
        if (config.spec.destination == null) {
            throw new IllegalArgumentException("spec.destination is required in " + file);
        }
        if (config.spec.destination.tableType == SyncSourceConfig.TableType.PRIMARY_KEY
                && (config.spec.destination.primaryKey == null || config.spec.destination.primaryKey.isEmpty())) {
            throw new IllegalArgumentException(
                    "spec.destination.primaryKey is required when tableType is PRIMARY_KEY in " + file);
        }
        if (config.spec.onFailure != null && config.spec.onFailure.rejectPath == null) {
            throw new IllegalArgumentException("spec.onFailure.rejectPath is required in " + file);
        }
        if (config.spec.onSuccess != null
                && config.spec.onSuccess.action == SyncSourceConfig.PostAction.ARCHIVE
                && config.spec.onSuccess.archivePath == null) {
            throw new IllegalArgumentException(
                    "spec.onSuccess.archivePath is required when action is ARCHIVE in " + file);
        }
        if (config.spec.destination.lakehouse != null && config.spec.destination.lakehouse.freshness != null) {
            try {
                TimeUtils.parseDuration(config.spec.destination.lakehouse.freshness);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "spec.destination.lakehouse.freshness '" + config.spec.destination.lakehouse.freshness
                                + "' is not a valid duration in " + file, e);
            }
        }
    }
}
