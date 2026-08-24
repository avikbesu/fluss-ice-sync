package com.flusssync.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Moves a processed file to its archive or reject destination. */
public final class PostProcessor {

    /** Archives {@code file} under {@code archivePathTemplate}, resolving {yyyy}/{MM}/{dd} in UTC. */
    public Path archive(Path file, String archivePathTemplate) throws IOException {
        return move(file, resolveTokens(archivePathTemplate, Instant.now()));
    }

    public void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    public Path reject(Path file, String rejectPath) throws IOException {
        return move(file, rejectPath);
    }

    private Path move(Path file, String targetDir) throws IOException {
        Path dir = Path.of(targetDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(file.getFileName());
        return Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    static String resolveTokens(String template, Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        return template
                .replace("{yyyy}", String.format("%04d", utc.getYear()))
                .replace("{MM}", String.format("%02d", utc.getMonthValue()))
                .replace("{dd}", String.format("%02d", utc.getDayOfMonth()));
    }
}
