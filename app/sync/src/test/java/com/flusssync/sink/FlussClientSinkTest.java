package com.flusssync.sink;

import com.flusssync.config.ApplicationConfig;
import com.flusssync.config.SyncSourceConfig;

import org.apache.fluss.config.ConfigOptions;
import org.apache.fluss.metadata.TableDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link TableDescriptor} {@link FlussClientSink} builds for a
 * new destination table, per the v1 design doc's "Table creation" testing
 * strategy: a {@code SyncSource} with {@code lakehouse.enabled: true} must
 * result in a table created with Fluss's {@code table.datalake.enabled}
 * (and {@code table.datalake.freshness}) properties set. Exercises
 * {@link FlussClientSink#buildDescriptor} directly rather than against a
 * live Fluss cluster — this repo has no Fluss-cluster-backed test
 * infrastructure to reuse (unlike what an earlier draft of the design doc
 * assumed).
 */
class FlussClientSinkTest {

    @Test
    void setsDatalakePropertiesWhenLakehouseEnabled() {
        ApplicationConfig appConfig = ApplicationConfig.defaults();
        SyncSourceConfig.Destination destination = new SyncSourceConfig.Destination();
        destination.database = "sales";
        destination.table = "partner_orders_raw";
        destination.lakehouse = new SyncSourceConfig.Lakehouse();
        destination.lakehouse.enabled = true;
        destination.lakehouse.freshness = "45s";

        TableDescriptor descriptor = FlussClientSink.buildDescriptor(destination, format(), appConfig);

        Map<String, String> properties = descriptor.getProperties();
        assertThat(properties.get(ConfigOptions.TABLE_DATALAKE_ENABLED.key())).isEqualTo("true");
        // Fluss serializes the Duration property in its own "<n> <unit>" form
        // (e.g. "45 s"), not the "45s" shorthand TimeUtils.parseDuration accepts as input.
        assertThat(properties.get(ConfigOptions.TABLE_DATALAKE_FRESHNESS.key())).isEqualTo("45 s");
    }

    @Test
    void omitsDatalakePropertiesWhenLakehouseDisabled() {
        ApplicationConfig appConfig = ApplicationConfig.defaults();
        SyncSourceConfig.Destination destination = new SyncSourceConfig.Destination();
        destination.database = "crm";
        destination.table = "customer_accounts";
        destination.lakehouse = null;

        TableDescriptor descriptor = FlussClientSink.buildDescriptor(destination, format(), appConfig);

        Map<String, String> properties = descriptor.getProperties();
        assertThat(properties).doesNotContainKey(ConfigOptions.TABLE_DATALAKE_ENABLED.key());
        assertThat(properties).doesNotContainKey(ConfigOptions.TABLE_DATALAKE_FRESHNESS.key());
    }

    @Test
    void fallsBackToApplicationConfigDefaultFreshnessWhenSourceOmitsIt() {
        ApplicationConfig appConfig = ApplicationConfig.defaults();
        appConfig.spec.lakehouse.defaultFreshness = "2m";
        SyncSourceConfig.Destination destination = new SyncSourceConfig.Destination();
        destination.database = "sales";
        destination.table = "partner_orders_raw";
        destination.lakehouse = new SyncSourceConfig.Lakehouse();
        destination.lakehouse.enabled = true;
        // freshness intentionally omitted

        TableDescriptor descriptor = FlussClientSink.buildDescriptor(destination, format(), appConfig);

        assertThat(descriptor.getProperties().get(ConfigOptions.TABLE_DATALAKE_FRESHNESS.key())).isEqualTo("2 min");
    }

    @Test
    void setsPrimaryKeyOnSchemaForPrimaryKeyTables() {
        ApplicationConfig appConfig = ApplicationConfig.defaults();
        SyncSourceConfig.Destination destination = new SyncSourceConfig.Destination();
        destination.database = "crm";
        destination.table = "customer_accounts";
        destination.tableType = SyncSourceConfig.TableType.PRIMARY_KEY;
        destination.primaryKey = java.util.List.of("account_id");

        TableDescriptor descriptor = FlussClientSink.buildDescriptor(destination, format(), appConfig);

        assertThat(descriptor.hasPrimaryKey()).isTrue();
        assertThat(descriptor.getSchema().getPrimaryKey()).isPresent();
        assertThat(descriptor.getSchema().getPrimaryKey().get().getColumnNames()).containsExactly("account_id");
    }

    private static SyncSourceConfig.Format format() {
        SyncSourceConfig.Format format = new SyncSourceConfig.Format();
        SyncSourceConfig.Column account = new SyncSourceConfig.Column();
        account.name = "account_id";
        account.type = SyncSourceConfig.ColumnType.STRING;
        format.columns = java.util.List.of(account);
        return format;
    }
}
