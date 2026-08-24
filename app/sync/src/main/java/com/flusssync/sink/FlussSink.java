package com.flusssync.sink;

import com.flusssync.config.SyncSourceConfig;
import com.flusssync.process.Row;

/**
 * Write path abstraction for a destination Fluss table. Kept separate from
 * the pipeline so the whole file lifecycle can be tested without a running
 * Fluss cluster (see {@link InMemoryFlussSink}).
 */
public interface FlussSink extends AutoCloseable {

    void createDatabaseAndTableIfMissing(SyncSourceConfig.Destination destination, SyncSourceConfig.Format format);

    void write(SyncSourceConfig.Destination destination, Row row);

    void flush(SyncSourceConfig.Destination destination);

    @Override
    default void close() {
    }
}
