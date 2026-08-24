package com.flusssync.ledger;

import com.flusssync.config.ApplicationConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes archived/rejected files and their ledger rows once they're older
 * than {@code retention.days}, per the design doc's Retention section.
 */
public final class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    private final ProcessedFileLedger ledger;
    private final ApplicationConfig.Retention retention;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "retention-sweeper");
        t.setDaemon(true);
        return t;
    });

    public RetentionSweeper(ProcessedFileLedger ledger, ApplicationConfig.Retention retention) {
        this.ledger = ledger;
        this.retention = retention;
    }

    public void start(Duration interval) {
        if (!retention.enabled || retention.days <= 0) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::sweepOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void sweepOnce() {
        if (!retention.enabled || retention.days <= 0) {
            return;
        }
        long cutoff = Instant.now().minus(Duration.ofDays(retention.days)).toEpochMilli();
        List<ProcessedFileLedger.LedgerRow> expired = ledger.findExpired(cutoff);
        for (ProcessedFileLedger.LedgerRow row : expired) {
            if (row.finalPath() != null) {
                try {
                    Files.deleteIfExists(row.finalPath());
                } catch (IOException e) {
                    log.warn("Failed to delete expired archived/rejected file {}", row.finalPath(), e);
                    continue;
                }
            }
            ledger.delete(row.sourceName(), row.filePath(), row.contentHash());
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
