package com.flusssync.config;

import java.util.List;

/** Parsed form of a {@code config/resources/spec/*.yaml} SyncSource file. */
public class SyncSourceConfig {

    public String apiVersion;
    public String kind;
    public Metadata metadata;
    public Spec spec;

    public String name() {
        return metadata.name;
    }

    public static class Metadata {
        public String name;
    }

    public static class Spec {
        public Observability observability;
        public Security security;
        public List<String> tag;
        public Contact contact;
        public Watch watch;
        public Format format;
        public Validation validation = new Validation();
        public Destination destination;
        public OnSuccess onSuccess;
        public OnFailure onFailure;
    }

    public static class Observability {
        public boolean lineage;
        public boolean metrics;
        public boolean log;
        public boolean validation;
    }

    public static class Security {
        public List<String> roles;
    }

    public static class Contact {
        public String owner;
        public List<String> support;
    }

    public static class Watch {
        public String path;
        public String filePattern;
        public Stability stability = new Stability();
    }

    public static class Stability {
        public long quietPeriodMs = 5000;
    }

    public static class Format {
        public String type = "delimited";
        public String delimiter = ",";
        public String quoteChar = "\"";
        public String escapeChar = "\"";
        public boolean hasHeader = true;
        public List<Column> columns;
    }

    public static class Column {
        public String name;
        public ColumnType type;
    }

    public enum ColumnType {
        STRING, BIGINT, TIMESTAMP, DATE, DOUBLE, BOOLEAN
    }

    public enum ValidationMode {
        FULL, SAMPLED
    }

    public static class Validation {
        public ValidationMode mode = ValidationMode.FULL;
        public int sampleSize = 1000;
    }

    public enum TableType {
        LOG, PRIMARY_KEY
    }

    public static class Destination {
        public String database;
        public String table;
        public TableType tableType = TableType.LOG;
        public List<String> primaryKey = List.of();
        public Lakehouse lakehouse;

        /** Per-source override, falling back to {@code appConfig}'s cluster-wide default. */
        public boolean isLakehouseEnabled(ApplicationConfig appConfig) {
            if (lakehouse != null && lakehouse.enabled != null) {
                return lakehouse.enabled;
            }
            return appConfig.spec.lakehouse.enabledByDefault;
        }

        /** Per-source override, falling back to {@code appConfig}'s cluster-wide default. */
        public String lakehouseFreshness(ApplicationConfig appConfig) {
            if (lakehouse != null && lakehouse.freshness != null) {
                return lakehouse.freshness;
            }
            return appConfig.spec.lakehouse.defaultFreshness;
        }
    }

    public static class Lakehouse {
        public Boolean enabled;
        public String freshness;
    }

    public enum PostAction {
        ARCHIVE, DELETE
    }

    public static class OnSuccess {
        public PostAction action = PostAction.ARCHIVE;
        public String archivePath;
    }

    public enum FailureAction {
        REJECT
    }

    public static class OnFailure {
        public FailureAction action = FailureAction.REJECT;
        public String rejectPath;
    }
}
