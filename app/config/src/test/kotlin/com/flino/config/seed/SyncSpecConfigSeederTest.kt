package com.flino.config.seed

import com.flino.config.config.ConfigStoreProperties
import com.flino.config.config.SyncSpecSeedProperties
import com.flino.config.filestore.YamlConfigFileStore
import com.flino.config.service.ConfigService
import com.flino.config.service.ConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.DefaultApplicationArguments
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Exercises [SyncSpecConfigSeeder] against a real [ConfigService] (backed
 * by a real temp-directory [YamlConfigFileStore]) and real spec YAML
 * files on disk -- no mocked filesystem, per this module's existing
 * "integration-style tests against a real temp directory" convention (see
 * ConfigServiceIntegrationTest).
 */
class SyncSpecConfigSeederTest {

    private fun configServiceAt(dir: Path): ConfigService {
        val props = ConfigStoreProperties(directory = dir.toString())
        return ConfigService(YamlConfigFileStore(props), ConfigValidator(props))
    }

    private fun writeSpec(dir: Path, filename: String, @Suppress("SameParameterValue") content: String) {
        dir.resolve(filename).writeText(content)
    }

    private val tieredSpec = """
        apiVersion: flino.io/v1
        kind: SyncSource
        metadata:
          name: partner-orders
        spec:
          tag:
            - sales
            - csv
          contact:
            owner: "@avik"
            support:
              - "#data-eng"
          destination:
            database: sales
            table: partner_orders_raw
            lakehouse:
              enabled: true
              freshness: "30s"
          format:
            columns:
              - name: order_id
                type: STRING
              - name: amount_cents
                type: BIGINT
    """.trimIndent()

    private val untieredSpec = """
        apiVersion: flino.io/v1
        kind: SyncSource
        metadata:
          name: customer-accounts
        spec:
          destination:
            database: crm
            table: customer_accounts
          format:
            columns:
              - name: account_id
                type: STRING
    """.trimIndent()

    @Test
    fun `imports a starter config for a lakehouse-enabled source`(@TempDir specDir: Path, @TempDir configDir: Path) {
        writeSpec(specDir, "partner-orders.yaml", tieredSpec)
        val configService = configServiceAt(configDir)

        SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = specDir.toString()), configService).run(DefaultApplicationArguments())

        val summaries = configService.list()
        assertEquals(1, summaries.size)
        assertEquals("partner-orders", summaries[0].name)
        assertEquals("iceberg", summaries[0].destination.catalog)
        assertEquals("sales", summaries[0].destination.schema)
        assertEquals("partner_orders_raw", summaries[0].destination.table)

        val full = configService.get(summaries[0].id)
        assertEquals(listOf("order_id", "amount_cents"), full.columns.map { it.name })
        assertEquals(listOf("STRING", "BIGINT"), full.columns.map { it.inferredType })
        assertTrue(full.businessDescription.contains("partner-orders"))
        assertTrue(full.businessDescription.contains("sales, csv"), "should fold the spec's tags in: ${full.businessDescription}")
        assertTrue(full.businessDescription.contains("@avik, #data-eng"), "should fold owner/support in: ${full.businessDescription}")
        assertTrue(full.businessDescription.contains("30s"), "should fold lakehouse freshness in: ${full.businessDescription}")
    }

    @Test
    fun `omits absent spec details from the description rather than showing empty clauses`(@TempDir specDir: Path, @TempDir configDir: Path) {
        val minimalSpec = """
            apiVersion: flino.io/v1
            kind: SyncSource
            metadata:
              name: minimal-source
            spec:
              destination:
                database: sales
                table: minimal
                lakehouse:
                  enabled: true
              format:
                columns:
                  - name: id
                    type: STRING
        """.trimIndent()
        writeSpec(specDir, "minimal-source.yaml", minimalSpec)
        val configService = configServiceAt(configDir)

        SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = specDir.toString()), configService).run(DefaultApplicationArguments())

        val full = configService.get(configService.list().single().id)
        assertEquals(
            "Auto-imported from the 'minimal-source' SyncSource spec (minimal-source.yaml). " +
                "Edit this description and add example questions to improve NL-to-SQL accuracy.",
            full.businessDescription,
        )
    }

    @Test
    fun `skips a source that isn't lakehouse-enabled -- nothing to query in Trino`(@TempDir specDir: Path, @TempDir configDir: Path) {
        writeSpec(specDir, "customer-accounts.yaml", untieredSpec)
        val configService = configServiceAt(configDir)

        SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = specDir.toString()), configService).run(DefaultApplicationArguments())

        assertTrue(configService.list().isEmpty())
    }

    @Test
    fun `does not overwrite a config that already exists under the same name`(@TempDir specDir: Path, @TempDir configDir: Path) {
        writeSpec(specDir, "partner-orders.yaml", tieredSpec)
        val configService = configServiceAt(configDir)
        val seeder = SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = specDir.toString()), configService)

        seeder.run(DefaultApplicationArguments())
        val firstId = configService.list().single().id

        // Simulate a restart: the seeder runs again against the same spec file.
        seeder.run(DefaultApplicationArguments())

        val summaries = configService.list()
        assertEquals(1, summaries.size, "re-running the seeder must not create a duplicate")
        assertEquals(firstId, summaries[0].id)
    }

    @Test
    fun `skips a YAML file with no metadata name rather than crashing`(@TempDir specDir: Path, @TempDir configDir: Path) {
        writeSpec(specDir, "malformed.yaml", "spec:\n  destination:\n    database: sales\n")
        val configService = configServiceAt(configDir)

        SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = specDir.toString()), configService).run(DefaultApplicationArguments())

        assertTrue(configService.list().isEmpty())
    }

    @Test
    fun `an unset directory is a no-op, not an error`(@TempDir configDir: Path) {
        val configService = configServiceAt(configDir)

        SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = null), configService).run(DefaultApplicationArguments())

        assertTrue(configService.list().isEmpty())
    }

    @Test
    fun `a missing directory is a no-op, not an error`(@TempDir configDir: Path) {
        val configService = configServiceAt(configDir)
        val missing = configDir.resolve("does-not-exist")

        SyncSpecConfigSeeder(SyncSpecSeedProperties(directory = missing.toString()), configService).run(DefaultApplicationArguments())

        assertTrue(configService.list().isEmpty())
    }
}
