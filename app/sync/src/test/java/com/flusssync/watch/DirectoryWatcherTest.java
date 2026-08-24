package com.flusssync.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryWatcherTest {

    @Test
    void detectsNewFileButExcludesArchiveSubfolder(@TempDir Path dir) throws Exception {
        Path archiveDir = dir.resolve("_processed");
        Files.createDirectories(archiveDir);

        BlockingQueue<FileEvent> events = new LinkedBlockingQueue<>();
        try (DirectoryWatcher watcher = new DirectoryWatcher("src", dir, List.of(archiveDir))) {
            Thread pump = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        events.addAll(watcher.take());
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            pump.setDaemon(true);
            pump.start();

            Thread.sleep(300); // let watch registration settle
            Files.writeString(dir.resolve("a.csv"), "hello");
            Files.writeString(archiveDir.resolve("b.csv"), "hello");

            // a.csv may fire both a CREATE and a MODIFY event; collect
            // everything that shows up in a window and assert none of it
            // came from the excluded archive subfolder.
            FileEvent first = events.poll(5, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(first.path().getFileName().toString()).isEqualTo("a.csv");

            FileEvent next;
            while ((next = events.poll(500, TimeUnit.MILLISECONDS)) != null) {
                assertThat(next.path().getFileName().toString()).isEqualTo("a.csv");
            }

            pump.interrupt();
        }
    }
}
