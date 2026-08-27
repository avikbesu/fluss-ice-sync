package com.flusssync.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncSourceConfigLoaderTest {

    private static final Path REPO_RESOURCES = Path.of("../../config/resources/spec");

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
        assertThat(config.spec.destination.lakehouse.enabled).isTrue();
        assertThat(config.spec.destination.lakehouse.freshness).isEqualTo("30s");
    }

    @Test
    void parsesPrimaryKeyTableSource() {
        SyncSourceConfig config = loader.load(REPO_RESOURCES.resolve("customer-accounts.yaml"));

        assertThat(config.spec.destination.tableType).isEqualTo(SyncSourceConfig.TableType.PRIMARY_KEY);
        assertThat(config.spec.destination.primaryKey).containsExactly("account_id");
        assertThat(config.spec.onSuccess.action).isEqualTo(SyncSourceConfig.PostAction.DELETE);
        assertThat(config.spec.destination.lakehouse).isNull();
    }

    @Test
    void resolvesLakehouseEnabledAndFreshnessAgainstApplicationConfigDefaults() {
        ApplicationConfig appConfig = ApplicationConfig.defaults();
        appConfig.spec.lakehouse.enabledByDefault = true;
        appConfig.spec.lakehouse.defaultFreshness = "5m";

        SyncSourceConfig.Destination explicitTrue = new SyncSourceConfig.Destination();
        explicitTrue.lakehouse = new SyncSourceConfig.Lakehouse();
        explicitTrue.lakehouse.enabled = true;
        explicitTrue.lakehouse.freshness = "10s";
        assertThat(explicitTrue.isLakehouseEnabled(appConfig)).isTrue();
        assertThat(explicitTrue.lakehouseFreshness(appConfig)).isEqualTo("10s");

        SyncSourceConfig.Destination explicitFalse = new SyncSourceConfig.Destination();
        explicitFalse.lakehouse = new SyncSourceConfig.Lakehouse();
        explicitFalse.lakehouse.enabled = false;
        assertThat(explicitFalse.isLakehouseEnabled(appConfig)).isFalse();

        SyncSourceConfig.Destination omitted = new SyncSourceConfig.Destination();
        assertThat(omitted.isLakehouseEnabled(appConfig)).isTrue();
        assertThat(omitted.lakehouseFreshness(appConfig)).isEqualTo("5m");
    }

    @Test
    void rejectsPrimaryKeyTableWithoutPrimaryKeyColumns(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.yaml");
        java.nio.file.Files.writeString(file, """
                apiVersion: fluss-ice-sync.io/v1
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

    @Test
    void rejectsInvalidLakehouseFreshness(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad-freshness.yaml");
        java.nio.file.Files.writeString(file, """
                apiVersion: fluss-ice-sync.io/v1
                kind: SyncSource
                metadata:
                  name: bad-freshness-source
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
                    tableType: LOG
                    lakehouse:
                      enabled: true
                      freshness: "30x"
                """);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");
    }
}
