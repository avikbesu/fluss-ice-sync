package com.flusssync.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Restart-safe record of processed files, keyed by
 * {@code (sourceName, filePath, contentHash)}, per the design doc's
 * "Processed-file ledger and restart safety" section.
 */
public final class ProcessedFileLedger implements AutoCloseable {

    private final Connection connection;

    public ProcessedFileLedger(Path dbFile) {
        try {
            if (dbFile.getParent() != null) {
                Files.createDirectories(dbFile.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (var stmt = connection.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS ledger (
                            source_name TEXT NOT NULL,
                            file_path TEXT NOT NULL,
                            content_hash TEXT NOT NULL,
                            status TEXT NOT NULL,
                            processed_at INTEGER NOT NULL,
                            final_path TEXT,
                            PRIMARY KEY (source_name, file_path, content_hash)
                        )
                        """);
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to open ledger " + dbFile, e);
        }
    }

    public boolean isProcessed(String sourceName, Path filePath, String contentHash) {
        String sql = "SELECT 1 FROM ledger WHERE source_name = ? AND file_path = ? AND content_hash = ? AND status = 'PROCESSED'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sourceName);
            ps.setString(2, filePath.toString());
            ps.setString(3, contentHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void markProcessed(String sourceName, Path filePath, String contentHash) {
        String sql = """
                INSERT INTO ledger (source_name, file_path, content_hash, status, processed_at, final_path)
                VALUES (?, ?, ?, 'PROCESSED', ?, NULL)
                ON CONFLICT (source_name, file_path, content_hash)
                DO UPDATE SET status = 'PROCESSED', processed_at = excluded.processed_at
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sourceName);
            ps.setString(2, filePath.toString());
            ps.setString(3, contentHash);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void markRejected(String sourceName, Path filePath, String contentHash, Path finalPath) {
        String sql = """
                INSERT INTO ledger (source_name, file_path, content_hash, status, processed_at, final_path)
                VALUES (?, ?, ?, 'REJECTED', ?, ?)
                ON CONFLICT (source_name, file_path, content_hash)
                DO UPDATE SET status = 'REJECTED', processed_at = excluded.processed_at, final_path = excluded.final_path
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sourceName);
            ps.setString(2, filePath.toString());
            ps.setString(3, contentHash);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, finalPath == null ? null : finalPath.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void recordFinalPath(String sourceName, Path filePath, String contentHash, Path finalPath) {
        String sql = "UPDATE ledger SET final_path = ? WHERE source_name = ? AND file_path = ? AND content_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, finalPath == null ? null : finalPath.toString());
            ps.setString(2, sourceName);
            ps.setString(3, filePath.toString());
            ps.setString(4, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<LedgerRow> findExpired(long cutoffEpochMillis) {
        String sql = "SELECT source_name, file_path, content_hash, final_path FROM ledger "
                + "WHERE status IN ('PROCESSED', 'REJECTED') AND processed_at < ?";
        List<LedgerRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffEpochMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String finalPath = rs.getString("final_path");
                    rows.add(new LedgerRow(
                            rs.getString("source_name"),
                            rs.getString("file_path"),
                            rs.getString("content_hash"),
                            finalPath == null ? null : Path.of(finalPath)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    public void delete(String sourceName, String filePath, String contentHash) {
        String sql = "DELETE FROM ledger WHERE source_name = ? AND file_path = ? AND content_hash = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sourceName);
            ps.setString(2, filePath);
            ps.setString(3, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True if the underlying store is reachable — used by the health endpoint. */
    public boolean isHealthy() {
        try {
            return connection.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    public static String contentHash(Path file) {
        try (var in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public record LedgerRow(String sourceName, String filePath, String contentHash, Path finalPath) {
    }
}
