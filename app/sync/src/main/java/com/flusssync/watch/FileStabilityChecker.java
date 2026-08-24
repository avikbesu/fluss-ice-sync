package com.flusssync.watch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Polls a file's size until it has been unchanged for a configured quiet
 * period, per the design doc's "File detection and stability" section.
 */
public final class FileStabilityChecker {

    private final long pollIntervalMs;

    public FileStabilityChecker() {
        this(1000);
    }

    FileStabilityChecker(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Blocks until the file's size has been stable for {@code quietPeriodMs}.
     *
     * @return {@code true} if the file is stable and non-empty and ready for
     *         processing; {@code false} if the file was empty once stable
     *         (should be skipped) or disappeared while waiting.
     */
    public boolean awaitStable(Path file, long quietPeriodMs) throws InterruptedException {
        long lastSize = -1;
        long stableSince = 0;
        while (true) {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            long size = sizeOf(file);
            long now = System.currentTimeMillis();
            if (size != lastSize) {
                lastSize = size;
                stableSince = now;
            } else if (now - stableSince >= quietPeriodMs) {
                return size > 0;
            }
            Thread.sleep(pollIntervalMs);
        }
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
