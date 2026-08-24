package com.flusssync.process;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts raw CSV records into typed {@link Row}s per a source's
 * {@code format.columns}, using the application-wide parsing conventions
 * (null literal, timestamp/date patterns) from {@link ApplicationConfig}.
 */
public final class LineParser {

    private final SyncSourceConfig.Format format;
    private final ApplicationConfig.Parsing parsing;
    private final DateTimeFormatter timestampFormatter;
    private final DateTimeFormatter dateFormatter;

    public LineParser(SyncSourceConfig.Format format, ApplicationConfig.Parsing parsing) {
        this.format = format;
        this.parsing = parsing;
        this.timestampFormatter = DateTimeFormatter.ofPattern(parsing.timestampFormat);
        this.dateFormatter = DateTimeFormatter.ofPattern(parsing.dateFormat);
    }

    /**
     * Builds a typed {@link Row} from one raw CSV record.
     *
     * @param rawFields   the record's raw string fields
     * @param fieldIndex  maps configured column name to its position in
     *                    {@code rawFields} (positional order when the file
     *                    has no header, header-name order otherwise)
     * @throws RowParseException if a field can't be parsed as its configured type
     */
    /**
     * @return index of each configured column's position in a raw record,
     *         or {@code null} if a header was expected but didn't contain
     *         every configured column.
     */
    public static Map<String, Integer> buildFieldIndex(SyncSourceConfig.Format format, List<String> headerRecord) {
        Map<String, Integer> index = new HashMap<>();
        if (headerRecord == null) {
            for (int i = 0; i < format.columns.size(); i++) {
                index.put(format.columns.get(i).name, i);
            }
            return index;
        }
        Map<String, Integer> headerPositions = new HashMap<>();
        for (int i = 0; i < headerRecord.size(); i++) {
            headerPositions.put(headerRecord.get(i), i);
        }
        for (SyncSourceConfig.Column column : format.columns) {
            Integer pos = headerPositions.get(column.name);
            if (pos == null) {
                return null;
            }
            index.put(column.name, pos);
        }
        return index;
    }

    public Row toRow(List<String> rawFields, Map<String, Integer> fieldIndex) {
        Row row = new Row();
        for (SyncSourceConfig.Column column : format.columns) {
            Integer index = fieldIndex.get(column.name);
            if (index == null || index >= rawFields.size()) {
                throw new RowParseException("Missing value for column '" + column.name + "'");
            }
            String raw = rawFields.get(index);
            row.put(column.name, parseValue(column.name, raw, column.type));
        }
        return row;
    }

    private Object parseValue(String columnName, String raw, SyncSourceConfig.ColumnType type) {
        if (raw == null || raw.equals(parsing.nullLiteral)) {
            return null;
        }
        try {
            return switch (type) {
                case STRING -> raw;
                case BIGINT -> Long.parseLong(raw);
                case DOUBLE -> Double.parseDouble(raw);
                case BOOLEAN -> Boolean.parseBoolean(raw);
                case TIMESTAMP -> LocalDateTime.parse(raw, timestampFormatter);
                case DATE -> LocalDate.parse(raw, dateFormatter);
            };
        } catch (RuntimeException e) {
            throw new RowParseException(
                    "Column '" + columnName + "' value '" + raw + "' is not a valid " + type, e);
        }
    }

    public static final class RowParseException extends RuntimeException {
        public RowParseException(String message) {
            super(message);
        }

        public RowParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
