package com.flino.sink;

import com.flino.config.ApplicationConfig;
import com.flino.config.SyncSourceConfig;
import com.flino.process.Row;

/**
 * Write path abstraction for a destination Fluss table. Kept separate from
 * the pipeline so the whole file lifecycle can be tested without a running
 * Fluss cluster (see {@link InMemoryFlussSink}).
 */
public interface FlussSink extends AutoCloseable {

    void createDatabaseAndTableIfMissing(
            SyncSourceConfig.Destination destination, SyncSourceConfig.Format format, ApplicationConfig appConfig);

    void write(SyncSourceConfig.Destination destination, Row row);

    void flush(SyncSourceConfig.Destination destination);

    @Override
    default void close() {
    }
}
