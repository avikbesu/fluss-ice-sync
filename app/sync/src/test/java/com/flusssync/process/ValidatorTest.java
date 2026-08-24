package com.flusssync.process;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorTest {

    private final Validator validator = new Validator();
    private final ApplicationConfig appConfig = ApplicationConfig.defaults();

    private SyncSourceConfig config(SyncSourceConfig.ValidationMode mode, int sampleSize) {
        SyncSourceConfig config = new SyncSourceConfig();
        config.metadata = new SyncSourceConfig.Metadata();
        config.metadata.name = "test-source";
        config.spec = new SyncSourceConfig.Spec();
        config.spec.watch = new SyncSourceConfig.Watch();
        config.spec.watch.filePattern = "*.csv";
        config.spec.format = new SyncSourceConfig.Format();
        SyncSourceConfig.Column id = new SyncSourceConfig.Column();
        id.name = "id";
        id.type = SyncSourceConfig.ColumnType.STRING;
        SyncSourceConfig.Column amount = new SyncSourceConfig.Column();
        amount.name = "amount";
        amount.type = SyncSourceConfig.ColumnType.BIGINT;
        config.spec.format.columns = List.of(id, amount);
        config.spec.validation = new SyncSourceConfig.Validation();
        config.spec.validation.mode = mode;
        config.spec.validation.sampleSize = sampleSize;
        return config;
    }

    @Test
    void rejectsFileNotMatchingPattern(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.txt");
        Files.writeString(file, "id,amount\nA1,10\n");

        ValidationResult result = validator.validate(file, config(SyncSourceConfig.ValidationMode.FULL, 10), appConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).contains("does not match pattern");
    }

    @Test
    void fullModeValidatesEveryRowAndMaterializesThem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "id,amount\nA1,10\nA2,20\nA3,30\n");

        ValidationResult result = validator.validate(file, config(SyncSourceConfig.ValidationMode.FULL, 10), appConfig);

        assertThat(result.isValid()).isTrue();
        assertThat(result.materializedRows()).hasSize(3);
    }

    @Test
    void fullModeRejectsFileWithBadRowAnywhere(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "id,amount\nA1,10\nA2,not-a-number\n");

        ValidationResult result = validator.validate(file, config(SyncSourceConfig.ValidationMode.FULL, 10), appConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.reason()).contains("row 2");
    }

    @Test
    void sampledModeOnlyChecksSampleAndDoesNotMaterializeRows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.csv");
        // Bad row is past the 2-row sample, so SAMPLED mode does not catch it here.
        Files.writeString(file, "id,amount\nA1,10\nA2,20\nA3,not-a-number\n");

        ValidationResult result = validator.validate(file, config(SyncSourceConfig.ValidationMode.SAMPLED, 2), appConfig);

        assertThat(result.isValid()).isTrue();
        assertThat(result.materializedRows()).isNull();
    }
}
