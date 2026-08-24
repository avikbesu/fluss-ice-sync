package com.flusssync.process;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;
import com.flusssync.ledger.ProcessedFileLedger;
import com.flusssync.sink.InMemoryFlussSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileProcessorTest {

    private final ApplicationConfig appConfig = ApplicationConfig.defaults();

    private SyncSourceConfig config(Path watchDir) {
        SyncSourceConfig config = new SyncSourceConfig();
        config.metadata = new SyncSourceConfig.Metadata();
        config.metadata.name = "test-source";
        config.spec = new SyncSourceConfig.Spec();

        config.spec.watch = new SyncSourceConfig.Watch();
        config.spec.watch.path = watchDir.toString();
        config.spec.watch.filePattern = "*.csv";
        config.spec.watch.stability = new SyncSourceConfig.Stability();

        config.spec.format = new SyncSourceConfig.Format();
        SyncSourceConfig.Column id = new SyncSourceConfig.Column();
        id.name = "id";
        id.type = SyncSourceConfig.ColumnType.STRING;
        SyncSourceConfig.Column amount = new SyncSourceConfig.Column();
        amount.name = "amount";
        amount.type = SyncSourceConfig.ColumnType.BIGINT;
        config.spec.format.columns = List.of(id, amount);

        config.spec.validation = new SyncSourceConfig.Validation();

        config.spec.destination = new SyncSourceConfig.Destination();
        config.spec.destination.database = "db";
        config.spec.destination.table = "t";
        config.spec.destination.tableType = SyncSourceConfig.TableType.LOG;
        config.spec.destination.primaryKey = List.of();

        config.spec.onSuccess = new SyncSourceConfig.OnSuccess();
        config.spec.onSuccess.action = SyncSourceConfig.PostAction.ARCHIVE;
        config.spec.onSuccess.archivePath = watchDir.resolve("_processed/{yyyy}/{MM}/{dd}").toString();

        config.spec.onFailure = new SyncSourceConfig.OnFailure();
        config.spec.onFailure.rejectPath = watchDir.resolve("_rejected").toString();

        return config;
    }

    @Test
    void happyPathWritesRowsAndArchivesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "id,amount\nA1,10\nA2,20\n");

        InMemoryFlussSink sink = new InMemoryFlussSink();
        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            SyncSourceConfig config = config(dir);
            FileProcessor processor = new FileProcessor(config, appConfig, sink, ledger);

            FileState state = processor.process(file);

            assertThat(state).isEqualTo(FileState.ARCHIVED);
            assertThat(sink.rowsWrittenTo(config.spec.destination)).hasSize(2);
            assertThat(sink.tableCreated(config.spec.destination)).isTrue();
            assertThat(Files.exists(file)).isFalse();

            String hash = ProcessedFileLedger.contentHash(archivedCopy(dir));
            assertThat(ledger.isProcessed("test-source", file, hash)).isTrue();
        }
    }

    @Test
    void validationFailureRejectsFileWithoutWritingToSink(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "id,amount\nA1,not-a-number\n");

        InMemoryFlussSink sink = new InMemoryFlussSink();
        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            SyncSourceConfig config = config(dir);
            FileProcessor processor = new FileProcessor(config, appConfig, sink, ledger);

            FileState state = processor.process(file);

            assertThat(state).isEqualTo(FileState.REJECTED);
            assertThat(sink.rowsWrittenTo(config.spec.destination)).isEmpty();
            assertThat(Files.exists(dir.resolve("_rejected/data.csv"))).isTrue();
        }
    }

    @Test
    void restartSafetySkipsReStreamingAlreadyProcessedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "id,amount\nA1,10\n");
        String hash = ProcessedFileLedger.contentHash(file);

        InMemoryFlussSink sink = new InMemoryFlussSink();
        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            ledger.markProcessed("test-source", file, hash);

            SyncSourceConfig config = config(dir);
            FileProcessor processor = new FileProcessor(config, appConfig, sink, ledger);

            FileState state = processor.process(file);

            assertThat(state).isEqualTo(FileState.ARCHIVED);
            assertThat(sink.rowsWrittenTo(config.spec.destination)).isEmpty();
        }
    }

    @Test
    void deleteActionRemovesFileWithoutArchiving(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "id,amount\nA1,10\n");

        InMemoryFlussSink sink = new InMemoryFlussSink();
        try (ProcessedFileLedger ledger = new ProcessedFileLedger(dir.resolve("ledger.db"))) {
            SyncSourceConfig config = config(dir);
            config.spec.onSuccess.action = SyncSourceConfig.PostAction.DELETE;
            config.spec.onSuccess.archivePath = null;
            FileProcessor processor = new FileProcessor(config, appConfig, sink, ledger);

            FileState state = processor.process(file);

            assertThat(state).isEqualTo(FileState.DELETED);
            assertThat(Files.exists(file)).isFalse();
        }
    }

    private Path archivedCopy(Path watchDir) throws Exception {
        Path processedRoot = watchDir.resolve("_processed");
        try (Stream<Path> walk = Files.walk(processedRoot)) {
            return walk.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }
}
