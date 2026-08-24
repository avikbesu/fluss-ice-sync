package com.flusssync.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncSourceConfigLoaderTest {

    private static final Path REPO_RESOURCES = Path.of("../../config/resources");

    private final SyncSourceConfigLoader loader = new SyncSourceConfigLoader();

    @Test
    void loadsExampleSourceConfigs() {
        List<SyncSourceConfig> configs = loader.loadAll(REPO_RESOURCES);

        assertThat(configs).hasSize(2);
        assertThat(configs).extracting(SyncSourceConfig::name)
                .containsExactlyInAnyOrder("partner-orders", "customer-accounts");
    }

    @Test
    void parsesLogTableSource() {
        SyncSourceConfig config = loader.load(REPO_RESOURCES.resolve("partner-orders.yaml"));

        assertThat(config.spec.destination.tableType).isEqualTo(SyncSourceConfig.TableType.LOG);
        assertThat(config.spec.destination.database).isEqualTo("sales");
        assertThat(config.spec.destination.table).isEqualTo("partner_orders_raw");
        assertThat(config.spec.format.columns).hasSize(4);
        assertThat(config.spec.validation.mode).isEqualTo(SyncSourceConfig.ValidationMode.FULL);
        assertThat(config.spec.onSuccess.action).isEqualTo(SyncSourceConfig.PostAction.ARCHIVE);
    }

    @Test
    void parsesPrimaryKeyTableSource() {
        SyncSourceConfig config = loader.load(REPO_RESOURCES.resolve("customer-accounts.yaml"));

        assertThat(config.spec.destination.tableType).isEqualTo(SyncSourceConfig.TableType.PRIMARY_KEY);
        assertThat(config.spec.destination.primaryKey).containsExactly("account_id");
        assertThat(config.spec.onSuccess.action).isEqualTo(SyncSourceConfig.PostAction.DELETE);
    }

    @Test
    void rejectsPrimaryKeyTableWithoutPrimaryKeyColumns(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.yaml");
        java.nio.file.Files.writeString(file, """
                apiVersion: fluss-sync.io/v1
                kind: SyncSource
                metadata:
                  name: bad-source
                spec:
                  watch:
                    path: /watch/bad
                    filePattern: "*.csv"
                  format:
                    columns:
                      - name: id
                        type: STRING
                  destination:
                    database: db
                    table: t
                    tableType: PRIMARY_KEY
                    primaryKey: []
                """);

        assertThatThrownBy(() -> loader.load(file)).isInstanceOf(IllegalArgumentException.class);
    }
}
