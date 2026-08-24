package com.flusssync.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileStabilityCheckerTest {

    private final FileStabilityChecker checker = new FileStabilityChecker(10);

    @Test
    void returnsTrueForStableNonEmptyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        Files.writeString(file, "hello");

        assertThat(checker.awaitStable(file, 20)).isTrue();
    }

    @Test
    void returnsFalseForStableEmptyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.csv");
        Files.writeString(file, "");

        assertThat(checker.awaitStable(file, 20)).isFalse();
    }

    @Test
    void returnsFalseWhenFileDoesNotExist(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("missing.csv");

        assertThat(checker.awaitStable(file, 20)).isFalse();
    }
}
