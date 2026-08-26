package com.flusssync.process;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;
import com.flusssync.ledger.ProcessedFileLedger;
import com.flusssync.sink.FlussSink;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates one file through the design doc's File Lifecycle state
 * machine: {@code DETECTED -> VALIDATING -> STREAMING -> PROCESSED ->
 * ARCHIVED|DELETED}, plus {@code REJECTED} / {@code FAILED_RETRYING}.
 */
public final class FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(FileProcessor.class);

    private final SyncSourceConfig config;
    private final ApplicationConfig appConfig;
    private final FlussSink sink;
    private final ProcessedFileLedger ledger;
    private final Validator validator = new Validator();
    private final PostProcessor postProcessor = new PostProcessor();

    public FileProcessor(SyncSourceConfig config, ApplicationConfig appConfig, FlussSink sink, ProcessedFileLedger ledger) {
        this.config = config;
        this.appConfig = appConfig;
        this.sink = sink;
        this.ledger = ledger;
    }

    public FileState process(Path file) {
        String sourceName = config.name();
        if (!Files.isRegularFile(file)) {
            return FileState.SKIPPED;
        }

        String hash = ProcessedFileLedger.contentHash(file);
        if (ledger.isProcessed(sourceName, file, hash)) {
            log.info("{}: {} already PROCESSED, applying post-action without re-streaming", sourceName, file);
            return applySuccessPostAction(file, hash);
        }

        log.info("{}: DETECTED {}", sourceName, file);
        ValidationResult result = validator.validate(file, config, appConfig);
        if (!result.isValid()) {
            log.info("{}: REJECTED {} ({})", sourceName, file, result.reason());
            return reject(file, hash);
        }

        log.info("{}: STREAMING {}", sourceName, file);
        SyncSourceConfig.Destination destination = config.spec.destination;
        try {
            sink.createDatabaseAndTableIfMissing(destination, config.spec.format, appConfig);
            if (result.materializedRows() != null) {
                for (Row row : result.materializedRows()) {
                    sink.write(destination, row);
                }
            } else {
                streamFileDirectly(file);
            }
            sink.flush(destination);
        } catch (LineParser.RowParseException e) {
            // SAMPLED mode: a malformed row surfaced mid-STREAMING, past the
            // validated sample — documented accepted risk in the design doc.
            log.warn("{}: row failed to parse mid-stream for {}: {}", sourceName, file, e.getMessage());
            return reject(file, hash);
        } catch (RuntimeException e) {
            log.warn("{}: Fluss table setup or write failed for {}, leaving file in place", sourceName, file, e);
            return FileState.FAILED_RETRYING;
        }

        ledger.markProcessed(sourceName, file, hash);
        log.info("{}: PROCESSED {}", sourceName, file);
        return applySuccessPostAction(file, hash);
    }

    private void streamFileDirectly(Path file) {
        SyncSourceConfig.Format format = config.spec.format;
        LineParser lineParser = new LineParser(format, appConfig.spec.parsing);
        char delimiter = format.delimiter.charAt(0);
        try (Reader reader = Files.newBufferedReader(file)) {
            CsvRecordReader csv = new CsvRecordReader(reader, delimiter, format.quoteChar, format.escapeChar);
            List<String> header = format.hasHeader ? csv.next() : null;
            Map<String, Integer> fieldIndex = LineParser.buildFieldIndex(format, header);
            List<String> record;
            while ((record = csv.next()) != null) {
                Row row = lineParser.toRow(record, fieldIndex);
                sink.write(config.spec.destination, row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileState applySuccessPostAction(Path file, String hash) {
        SyncSourceConfig.OnSuccess onSuccess = config.spec.onSuccess;
        boolean archive = onSuccess.action == SyncSourceConfig.PostAction.ARCHIVE;
        if (!Files.exists(file)) {
            // Already moved in a prior attempt after a crash; ledger already reflects it.
            return archive ? FileState.ARCHIVED : FileState.DELETED;
        }
        try {
            if (archive) {
                Path finalPath = postProcessor.archive(file, onSuccess.archivePath);
                ledger.recordFinalPath(config.name(), file, hash, finalPath);
                return FileState.ARCHIVED;
            } else {
                postProcessor.delete(file);
                ledger.recordFinalPath(config.name(), file, hash, null);
                return FileState.DELETED;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileState reject(Path file, String hash) {
        try {
            Path finalPath = postProcessor.reject(file, config.spec.onFailure.rejectPath);
            ledger.markRejected(config.name(), file, hash, finalPath);
            return FileState.REJECTED;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
