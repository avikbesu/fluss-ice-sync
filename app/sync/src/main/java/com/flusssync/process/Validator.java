package com.flusssync.process;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a candidate file against its {@link SyncSourceConfig} before any
 * row is written to Fluss, per the design doc's Validation section.
 */
public final class Validator {

    public ValidationResult validate(Path file, SyncSourceConfig config, ApplicationConfig appConfig) {
        SyncSourceConfig.Spec spec = config.spec;

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + spec.watch.filePattern);
        if (!matcher.matches(file.getFileName())) {
            return ValidationResult.rejected(
                    "file name '" + file.getFileName() + "' does not match pattern '" + spec.watch.filePattern + "'");
        }

        LineParser lineParser = new LineParser(spec.format, appConfig.spec.parsing);
        char delimiter = spec.format.delimiter.charAt(0);

        try (Reader reader = Files.newBufferedReader(file)) {
            CsvRecordReader csv = new CsvRecordReader(reader, delimiter, spec.format.quoteChar, spec.format.escapeChar);

            List<String> headerRecord = spec.format.hasHeader ? csv.next() : null;
            Map<String, Integer> fieldIndex = LineParser.buildFieldIndex(spec.format, headerRecord);
            if (fieldIndex == null) {
                return ValidationResult.rejected("header does not contain all configured columns");
            }

            boolean sampled = spec.validation.mode == SyncSourceConfig.ValidationMode.SAMPLED;
            int limit = sampled ? spec.validation.sampleSize : Integer.MAX_VALUE;

            List<Row> rows = sampled ? null : new ArrayList<>();
            int count = 0;
            List<String> record;
            while (count < limit && (record = csv.next()) != null) {
                try {
                    Row row = lineParser.toRow(record, fieldIndex);
                    if (rows != null) {
                        rows.add(row);
                    }
                } catch (LineParser.RowParseException e) {
                    return ValidationResult.rejected("row " + (count + 1) + ": " + e.getMessage());
                }
                count++;
            }
            return ValidationResult.valid(rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
