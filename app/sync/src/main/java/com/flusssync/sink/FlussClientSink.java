package com.flusssync.sink;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;
import com.flusssync.process.Row;

import org.apache.fluss.client.Connection;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.writer.AppendWriter;
import org.apache.fluss.client.table.writer.UpsertWriter;
import org.apache.fluss.config.ConfigOptions;
import org.apache.fluss.exception.LakeTableAlreadyExistException;
import org.apache.fluss.metadata.DatabaseDescriptor;
import org.apache.fluss.metadata.Schema;
import org.apache.fluss.metadata.TableDescriptor;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.row.BinaryString;
import org.apache.fluss.row.GenericRow;
import org.apache.fluss.row.TimestampNtz;
import org.apache.fluss.types.DataType;
import org.apache.fluss.types.DataTypes;
import org.apache.fluss.utils.TimeUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Real {@link FlussSink} backed by {@code fluss-client}: auto-creates the
 * destination database/table from {@code format.columns} (per the design
 * doc's Streaming-into-Fluss section) and writes via {@code AppendWriter}
 * (LOG tables) or {@code UpsertWriter} (PRIMARY_KEY tables).
 */
public final class FlussClientSink implements FlussSink {

    private final Connection connection;
    private final Admin admin;
    private final Map<String, TableHandle> tables = new ConcurrentHashMap<>();

    public FlussClientSink(Connection connection) {
        this.connection = connection;
        this.admin = connection.getAdmin();
    }

    @Override
    public void createDatabaseAndTableIfMissing(
            SyncSourceConfig.Destination destination, SyncSourceConfig.Format format, ApplicationConfig appConfig) {
        tables.computeIfAbsent(key(destination), k -> createHandle(destination, format, appConfig));
    }

    private TableHandle createHandle(
            SyncSourceConfig.Destination destination, SyncSourceConfig.Format format, ApplicationConfig appConfig) {
        try {
            if (!admin.databaseExists(destination.database).get()) {
                admin.createDatabase(destination.database, DatabaseDescriptor.builder().build(), true).get();
            }
            TablePath path = TablePath.of(destination.database, destination.table);
            if (!admin.tableExists(path).get()) {
                try {
                    admin.createTable(path, buildDescriptor(destination, format, appConfig), true).get();
                } catch (ExecutionException e) {
                    // Fluss's own metadata (zookeeper) and the Iceberg lake
                    // table it tiers into can drift apart -- e.g. zookeeper
                    // lost its record of the table across a restart while
                    // the already-tiered Iceberg table survived. Fluss
                    // still refuses to recreate it in that case even with
                    // ignoreIfExists=true, but the table is otherwise
                    // usable, so treat this the same as tableExists()
                    // having returned true instead of wedging this file
                    // forever.
                    if (!(e.getCause() instanceof LakeTableAlreadyExistException)) {
                        throw e;
                    }
                }
            }
            Table table = connection.getTable(path);
            if (destination.tableType == SyncSourceConfig.TableType.PRIMARY_KEY) {
                return new TableHandle(table, null, table.newUpsert().createWriter(), format);
            }
            return new TableHandle(table, table.newAppend().createWriter(), null, format);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to prepare Fluss table " + destination.database + "." + destination.table, e.getCause());
        }
    }

    /**
     * Builds the {@link TableDescriptor} a new destination table is created
     * with, including the datalake-tiering properties from
     * {@code destination.lakehouse} (see the v1 design doc's {@code SyncSource}
     * schema addition). Pulled out of {@link #createHandle} so it can be
     * exercised without a live Fluss cluster.
     */
    static TableDescriptor buildDescriptor(
            SyncSourceConfig.Destination destination, SyncSourceConfig.Format format, ApplicationConfig appConfig) {
        Schema.Builder schemaBuilder = Schema.newBuilder();
        for (SyncSourceConfig.Column column : format.columns) {
            schemaBuilder.column(column.name, toDataType(column.type));
        }
        if (destination.tableType == SyncSourceConfig.TableType.PRIMARY_KEY) {
            schemaBuilder.primaryKey(destination.primaryKey);
        }
        TableDescriptor.Builder descriptorBuilder = TableDescriptor.builder().schema(schemaBuilder.build());
        if (destination.isLakehouseEnabled(appConfig)) {
            descriptorBuilder
                    .property(ConfigOptions.TABLE_DATALAKE_ENABLED, true)
                    .property(
                            ConfigOptions.TABLE_DATALAKE_FRESHNESS,
                            TimeUtils.parseDuration(destination.lakehouseFreshness(appConfig)));
        }
        return descriptorBuilder.build();
    }

    @Override
    public void write(SyncSourceConfig.Destination destination, Row row) {
        TableHandle handle = tables.get(key(destination));
        GenericRow internalRow = toInternalRow(row, handle.format);
        if (handle.appendWriter != null) {
            handle.appendWriter.append(internalRow);
        } else {
            handle.upsertWriter.upsert(internalRow);
        }
    }

    @Override
    public void flush(SyncSourceConfig.Destination destination) {
        TableHandle handle = tables.get(key(destination));
        if (handle.appendWriter != null) {
            handle.appendWriter.flush();
        } else {
            handle.upsertWriter.flush();
        }
    }

    @Override
    public void close() {
        for (TableHandle handle : tables.values()) {
            try {
                handle.table.close();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        try {
            admin.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private GenericRow toInternalRow(Row row, SyncSourceConfig.Format format) {
        GenericRow internalRow = new GenericRow(format.columns.size());
        int i = 0;
        for (SyncSourceConfig.Column column : format.columns) {
            internalRow.setField(i++, toInternalValue(row.get(column.name), column.type));
        }
        return internalRow;
    }

    private Object toInternalValue(Object value, SyncSourceConfig.ColumnType type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case STRING -> BinaryString.fromString((String) value);
            case BIGINT, DOUBLE, BOOLEAN -> value;
            case TIMESTAMP -> TimestampNtz.fromLocalDateTime((LocalDateTime) value);
            case DATE -> Math.toIntExact(((LocalDate) value).toEpochDay());
        };
    }

    private static DataType toDataType(SyncSourceConfig.ColumnType type) {
        return switch (type) {
            case STRING -> DataTypes.STRING();
            case BIGINT -> DataTypes.BIGINT();
            case DOUBLE -> DataTypes.DOUBLE();
            case BOOLEAN -> DataTypes.BOOLEAN();
            case TIMESTAMP -> DataTypes.TIMESTAMP();
            case DATE -> DataTypes.DATE();
        };
    }

    private String key(SyncSourceConfig.Destination destination) {
        return destination.database + "." + destination.table;
    }

    private static final class TableHandle {
        final Table table;
        final AppendWriter appendWriter;
        final UpsertWriter upsertWriter;
        final SyncSourceConfig.Format format;

        TableHandle(Table table, AppendWriter appendWriter, UpsertWriter upsertWriter, SyncSourceConfig.Format format) {
            this.table = table;
            this.appendWriter = appendWriter;
            this.upsertWriter = upsertWriter;
            this.format = format;
        }
    }
}
