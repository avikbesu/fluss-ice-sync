package com.flusssync.watch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches one source's root directory recursively for new/changed files,
 * excluding anything under the source's configured archive/reject paths
 * (see the design doc's "Watch exclusions").
 */
public final class DirectoryWatcher implements AutoCloseable {

    private final String sourceName;
    private final Path root;
    private final List<Path> exclusions;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();

    public DirectoryWatcher(String sourceName, Path root, List<Path> exclusions) {
        this.sourceName = sourceName;
        this.root = root;
        this.exclusions = exclusions;
        try {
            this.watchService = root.getFileSystem().newWatchService();
            registerRecursively(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to watch " + root, e);
        }
    }

    private void registerRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path visited, BasicFileAttributes attrs) throws IOException {
                if (isExcluded(visited) && !visited.equals(root)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = visited.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                keyToDir.put(key, visited);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcluded(Path path) {
        for (Path excluded : exclusions) {
            if (path.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    /** Blocks until at least one relevant file event is available. */
    public List<FileEvent> take() throws InterruptedException {
        WatchKey key = watchService.take();
        Path dir = keyToDir.get(key);
        List<FileEvent> events = new java.util.ArrayList<>();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW || dir == null) {
                continue;
            }
            Path child = dir.resolve((Path) event.context());
            if (isExcluded(child)) {
                continue;
            }
            if (Files.isDirectory(child)) {
                try {
                    registerRecursively(child);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                continue;
            }
            events.add(new FileEvent(sourceName, child));
        }
        boolean valid = key.reset();
        if (!valid) {
            keyToDir.remove(key);
        }
        return events;
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }
}
