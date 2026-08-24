package com.flusssync.process;

import com.flusssync.config.SyncSourceConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Resolves which {@link SyncSourceConfig} owns a given file path. */
public final class SourceRouter {

    private final List<SyncSourceConfig> configs;

    public SourceRouter(List<SyncSourceConfig> configs) {
        this.configs = configs;
    }

    public Optional<SyncSourceConfig> route(Path path) {
        for (SyncSourceConfig config : configs) {
            Path watchRoot = Path.of(config.spec.watch.path);
            if (path.startsWith(watchRoot)) {
                return Optional.of(config);
            }
        }
        return Optional.empty();
    }
}
