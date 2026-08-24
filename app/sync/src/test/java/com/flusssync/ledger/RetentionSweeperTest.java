package com.flusssync.ledger;

import com.flusssync.config.ApplicationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionSweeperTest {

    @Test
    void removesArchivedFileAndLedgerRowOlderThanRetentionDays(@TempDir Path dir) throws Exception {
        Path original = dir.resolve("a.csv");
        Files.writeString(original, "hello");
        String hash = ProcessedFileLedger.contentHash(original);
        Path archived = dir.resolve("archived.csv");
        Files.writeString(archived, "hello");

        Path dbFile = dir.resolve("ledger.db");
        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dbFile)) {
            ledger.markProcessed("src", original, hash);
            ledger.recordFinalPath("src", original, hash, archived);
            backdate(dbFile, "src", original, hash, 20);

            ApplicationConfig.Retention retention = new ApplicationConfig.Retention();
            retention.enabled = true;
            retention.days = 15;

            new RetentionSweeper(ledger, retention).sweepOnce();

            assertThat(ledger.isProcessed("src", original, hash)).isFalse();
            assertThat(Files.exists(archived)).isFalse();
        }
    }

    @Test
    void keepsRowsNewerThanRetentionDays(@TempDir Path dir) throws Exception {
        Path original = dir.resolve("a.csv");
        Files.writeString(original, "hello");
        String hash = ProcessedFileLedger.contentHash(original);
        Path archived = dir.resolve("archived.csv");
        Files.writeString(archived, "hello");

        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            ledger.markProcessed("src", original, hash);
            ledger.recordFinalPath("src", original, hash, archived);

            ApplicationConfig.Retention retention = new ApplicationConfig.Retention();
            retention.enabled = true;
            retention.days = 15;

            new RetentionSweeper(ledger, retention).sweepOnce();

            assertThat(ledger.isProcessed("src", original, hash)).isTrue();
            assertThat(Files.exists(archived)).isTrue();
        }
    }

    @Test
    void disabledRetentionRemovesNothing(@TempDir Path dir) throws Exception {
        Path original = dir.resolve("a.csv");
        Files.writeString(original, "hello");
        String hash = ProcessedFileLedger.contentHash(original);
        Path dbFile = dir.resolve("ledger.db");

        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dbFile)) {
            ledger.markProcessed("src", original, hash);
            backdate(dbFile, "src", original, hash, 100);

            ApplicationConfig.Retention retention = new ApplicationConfig.Retention();
            retention.enabled = false;
            retention.days = 15;

            new RetentionSweeper(ledger, retention).sweepOnce();

            assertThat(ledger.isProcessed("src", original, hash)).isTrue();
        }
    }

    /** Directly rewrites processed_at to simulate an old ledger entry. */
    private void backdate(Path dbFile, String source, Path file, String hash, int daysAgo) throws Exception {
        long backdated = System.currentTimeMillis() - daysAgo * 24L * 60 * 60 * 1000;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var ps = conn.prepareStatement(
                     "UPDATE ledger SET processed_at = ? WHERE source_name = ? AND file_path = ? AND content_hash = ?")) {
            ps.setLong(1, backdated);
            ps.setString(2, source);
            ps.setString(3, file.toString());
            ps.setString(4, hash);
            ps.executeUpdate();
        }
    }
}
