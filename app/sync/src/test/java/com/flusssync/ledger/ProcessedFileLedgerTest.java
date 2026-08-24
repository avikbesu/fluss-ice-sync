package com.flusssync.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedFileLedgerTest {

    @Test
    void markProcessedThenIsProcessedTrueForSameHash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        Files.writeString(file, "hello");
        String hash = ProcessedFileLedger.contentHash(file);

        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            assertThat(ledger.isProcessed("src", file, hash)).isFalse();

            ledger.markProcessed("src", file, hash);

            assertThat(ledger.isProcessed("src", file, hash)).isTrue();
        }
    }

    @Test
    void isProcessedFalseForDifferentContentHash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        Files.writeString(file, "hello");
        String hash = ProcessedFileLedger.contentHash(file);

        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            ledger.markProcessed("src", file, hash);

            assertThat(ledger.isProcessed("src", file, "different-hash")).isFalse();
        }
    }

    @Test
    void recordFinalPathIsStoredForRetrieval(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        Files.writeString(file, "hello");
        String hash = ProcessedFileLedger.contentHash(file);
        Path archived = dir.resolve("archived/a.csv");

        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            ledger.markProcessed("src", file, hash);
            ledger.recordFinalPath("src", file, hash, archived);

            var expired = ledger.findExpired(System.currentTimeMillis() + 1);
            assertThat(expired).hasSize(1);
            assertThat(expired.get(0).finalPath()).isEqualTo(archived);
        }
    }

    @Test
    void ledgerSurvivesReopen(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        Files.writeString(file, "hello");
        String hash = ProcessedFileLedger.contentHash(file);
        Path dbFile = dir.resolve("ledger.db");

        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dbFile)) {
            ledger.markProcessed("src", file, hash);
        }

        try (ProcessedFileLedger reopened = new ProcessedFileLedger(dbFile)) {
            assertThat(reopened.isProcessed("src", file, hash)).isTrue();
        }
    }
}
