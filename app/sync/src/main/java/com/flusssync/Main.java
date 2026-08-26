package com.flusssync;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.ApplicationConfigLoader;
import com.flusssync.config.SyncSourceConfig;
import com.flusssync.config.SyncSourceConfigLoader;
import com.flusssync.health.HealthServer;
import com.flusssync.ledger.ProcessedFileLedger;
import com.flusssync.ledger.RetentionSweeper;
import com.flusssync.process.FileProcessor;
import com.flusssync.sink.FlussClientSink;
import com.flusssync.watch.DirectoryWatcher;
import com.flusssync.watch.FileEvent;
import com.flusssync.watch.FileStabilityChecker;

import org.apache.fluss.client.Connection;
import org.apache.fluss.client.ConnectionFactory;
import org.apache.fluss.config.ConfigOptions;
import org.apache.fluss.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wires the fluss-ice-sync pipeline together and runs it until interrupted. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path resourcesDir = Path.of(System.getenv().getOrDefault("FLUSS_SYNC_CONFIG_DIR", "/config/resources"));
        Path appConfigFile = Path.of(System.getenv().getOrDefault("FLUSS_SYNC_APP_CONFIG", "/config/application.yaml"));
        Path ledgerFile = Path.of(System.getenv().getOrDefault("FLUSS_SYNC_STATE_DIR", "/state"), "ledger.db");
        String bootstrapServers = System.getenv().getOrDefault("FLUSS_BOOTSTRAP_SERVERS", "localhost:9123");

        ApplicationConfig appConfig = new ApplicationConfigLoader().load(appConfigFile);
        List<SyncSourceConfig> sources = new SyncSourceConfigLoader().loadAll(resourcesDir);
        log.info("Loaded {} source config(s) from {}", sources.size(), resourcesDir);

        Configuration flussConf = new Configuration();
        flussConf.set(ConfigOptions.BOOTSTRAP_SERVERS, List.of(bootstrapServers.split(",")));

        try (Connection connection = ConnectionFactory.createConnection(flussConf);
             FlussClientSink sink = new FlussClientSink(connection);
             ProcessedFileLedger ledger = new ProcessedFileLedger(ledgerFile)) {

            RetentionSweeper retentionSweeper = new RetentionSweeper(ledger, appConfig.spec.retention);
            retentionSweeper.start(Duration.ofHours(1));

            AtomicBoolean healthy = new AtomicBoolean(true);
            HealthServer healthServer = null;
            if (appConfig.spec.health.enabled) {
                healthServer = new HealthServer(appConfig.spec.health, () -> healthy.get() && ledger.isHealthy());
                healthServer.start();
                log.info("Health endpoint listening on :{}{}", appConfig.spec.health.port, appConfig.spec.health.path);
            }

            List<Thread> watcherThreads = sources.stream()
                    .map(source -> startWatcherThread(source, appConfig, sink, ledger))
                    .toList();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                watcherThreads.forEach(Thread::interrupt);
                retentionSweeper.stop();
            }));

            for (Thread t : watcherThreads) {
                t.join();
            }
        }
    }

    private static Thread startWatcherThread(
            SyncSourceConfig source, ApplicationConfig appConfig, FlussClientSink sink, ProcessedFileLedger ledger) {
        Thread thread = new Thread(() -> runWatchLoop(source, appConfig, sink, ledger), "watch-" + source.name());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void runWatchLoop(
            SyncSourceConfig source, ApplicationConfig appConfig, FlussClientSink sink, ProcessedFileLedger ledger) {
        Path root = Path.of(source.spec.watch.path);
        Set<Path> exclusions = exclusionsFor(source);
        FileStabilityChecker stabilityChecker = new FileStabilityChecker();
        FileProcessor processor = new FileProcessor(source, appConfig, sink, ledger);

        try (DirectoryWatcher watcher = new DirectoryWatcher(source.name(), root, exclusions.stream().toList())) {
            while (!Thread.currentThread().isInterrupted()) {
                List<FileEvent> events = watcher.take();
                for (FileEvent event : events) {
                    try {
                        boolean ready = stabilityChecker.awaitStable(event.path(), source.spec.watch.stability.quietPeriodMs);
                        if (ready) {
                            processor.process(event.path());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        log.error("{}: failed to process {}", source.name(), event.path(), e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("{}: watcher failed", source.name(), e);
        }
    }

    private static Set<Path> exclusionsFor(SyncSourceConfig source) {
        Set<Path> exclusions = new java.util.HashSet<>();
        if (source.spec.onSuccess != null && source.spec.onSuccess.archivePath != null) {
            exclusions.add(Path.of(stripDateTokens(source.spec.onSuccess.archivePath)));
        }
        if (source.spec.onFailure != null && source.spec.onFailure.rejectPath != null) {
            exclusions.add(Path.of(source.spec.onFailure.rejectPath));
        }
        return exclusions;
    }

    private static String stripDateTokens(String template) {
        int idx = template.indexOf("{");
        return idx == -1 ? template : template.substring(0, idx);
    }
}
