package com.flusssync.sink;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;
import com.flusssync.process.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test double capturing written rows in memory instead of talking to a real Fluss cluster. */
public final class InMemoryFlussSink implements FlussSink {

    private final Set<String> createdTables = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Row>> writtenRows = new ConcurrentHashMap<>();
    private final Map<String, Integer> flushCounts = new ConcurrentHashMap<>();

    @Override
    public void createDatabaseAndTableIfMissing(
            SyncSourceConfig.Destination destination, SyncSourceConfig.Format format, ApplicationConfig appConfig) {
        createdTables.add(key(destination));
    }

    @Override
    public void write(SyncSourceConfig.Destination destination, Row row) {
        writtenRows.computeIfAbsent(key(destination), k -> new ArrayList<>()).add(row);
    }

    @Override
    public void flush(SyncSourceConfig.Destination destination) {
        flushCounts.merge(key(destination), 1, Integer::sum);
    }

    public List<Row> rowsWrittenTo(SyncSourceConfig.Destination destination) {
        return writtenRows.getOrDefault(key(destination), List.of());
    }

    public boolean tableCreated(SyncSourceConfig.Destination destination) {
        return createdTables.contains(key(destination));
    }

    public int flushCount(SyncSourceConfig.Destination destination) {
        return flushCounts.getOrDefault(key(destination), 0);
    }

    private String key(SyncSourceConfig.Destination destination) {
        return destination.database + "." + destination.table;
    }
}
