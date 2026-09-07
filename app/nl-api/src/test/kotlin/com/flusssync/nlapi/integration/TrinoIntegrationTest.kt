package com.flusssync.nlapi.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.flusssync.nlapi.config.TrinoProperties
import com.flusssync.nlapi.exception.CatalogNotFoundException
import com.flusssync.nlapi.exception.TableNotFoundException
import com.flusssync.nlapi.trino.ActiveStatementRegistry
import com.flusssync.nlapi.trino.PreviewStreamer
import com.flusssync.nlapi.trino.PseudoTableName
import com.flusssync.nlapi.trino.TrinoDataSourceConfig
import com.flusssync.nlapi.trino.TrinoExceptionTranslator
import com.flusssync.nlapi.trino.TrinoMetadataService
import com.flusssync.nlapi.trino.TrinoQueryExecutor
import com.flusssync.nlapi.trino.TrinoSqlValidator
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.TrinoContainer
import org.testcontainers.utility.MountableFile
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration

/**
 * Boots a real Trino coordinator plus a Postgres-backed Iceberg JDBC
 * catalog -- the same catalog type (`iceberg.catalog.type=jdbc`) and
 * connector wiring as this repo's actual deployment
 * (config/trino/etc/catalog/iceberg.properties, see
 * doc/design/v1-trino-integration-design.md) -- and exercises this
 * module's Trino-facing classes directly against it (no Spring context;
 * these are plain constructor-injected classes, so wiring them by hand
 * here is both simpler and faster than a full `@SpringBootTest`).
 *
 * Requires a Docker daemon. Tagged "integration" and excluded from the
 * default `test` task (see build.gradle) -- run explicitly via
 * `./gradlew :app:nl-api:integrationTest`. **Not executed during this
 * module's own development** (no Docker daemon was available in that
 * environment) -- see the README's "edge cases not handled" for this
 * caveat; the Iceberg JDBC catalog wiring below mirrors an already
 * verified-working configuration from this same repo, but this specific
 * test file itself has not been run end to end.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrinoIntegrationTest {

    private lateinit var network: Network
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var trino: TrinoContainer

    private lateinit var dataSource: HikariDataSource
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var metadataService: TrinoMetadataService
    private lateinit var queryExecutor: TrinoQueryExecutor
    private lateinit var previewStreamer: PreviewStreamer
    private lateinit var trinoProperties: TrinoProperties

    @BeforeAll
    fun startCluster() {
        network = Network.newNetwork()

        postgres = PostgreSQLContainer("postgres:16-alpine")
            .withNetwork(network)
            .withNetworkAliases("iceberg-catalog-db")
            .withDatabaseName("iceberg_catalog")
            .withUsername("iceberg")
            .withPassword("iceberg")
        postgres.start()

        val warehouseDir = Files.createTempDirectory("nl-api-it-warehouse")
        val icebergProperties = """
            connector.name=iceberg
            iceberg.catalog.type=jdbc
            iceberg.jdbc-catalog.catalog-name=nl-api-it-catalog
            iceberg.jdbc-catalog.driver-class=org.postgresql.Driver
            iceberg.jdbc-catalog.connection-url=jdbc:postgresql://iceberg-catalog-db:5432/iceberg_catalog
            iceberg.jdbc-catalog.connection-user=iceberg
            iceberg.jdbc-catalog.connection-password=iceberg
            iceberg.jdbc-catalog.default-warehouse-dir=file:///lakehouse/warehouse
            fs.hadoop.enabled=true
        """.trimIndent()
        val propertiesFile = Files.createTempFile("iceberg", ".properties")
        Files.writeString(propertiesFile, icebergProperties)

        trino = TrinoContainer("trinodb/trino:470")
            .withNetwork(network)
            .withCopyFileToContainer(MountableFile.forHostPath(propertiesFile), "/etc/trino/catalog/iceberg.properties")
            .withStartupTimeout(Duration.ofMinutes(3))
        trino.start()

        val jdbcUrl = "jdbc:trino://${trino.host}:${trino.getMappedPort(8080)}"
        trinoProperties = TrinoProperties(
            jdbcUrl = jdbcUrl,
            user = "nl-api-it",
            pool = TrinoProperties.Pool(maximumPoolSize = 4, minimumIdle = 1),
            query = TrinoProperties.Query(defaultTimeoutSeconds = 30, maxTimeoutSeconds = 60, executorPoolSize = 4),
        )

        dataSource = TrinoDataSourceConfig().trinoDataSource(trinoProperties)
        executor = TrinoDataSourceConfig().trinoQueryExecutorService(trinoProperties)
        val exceptionTranslator = TrinoExceptionTranslator()
        val activeStatements = ActiveStatementRegistry()
        queryExecutor = TrinoQueryExecutor(dataSource, executor, trinoProperties, exceptionTranslator, activeStatements)
        val sqlValidator = TrinoSqlValidator()
        metadataService = TrinoMetadataService(dataSource, executor, trinoProperties, exceptionTranslator, queryExecutor, sqlValidator)
        previewStreamer = PreviewStreamer(dataSource, trinoProperties, activeStatements, ObjectMapper().registerKotlinModule())

        seedFixtures(jdbcUrl)
    }

    @AfterAll
    fun stopCluster() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::executor.isInitialized) executor.shutdownNow()
        if (::trino.isInitialized) trino.stop()
        if (::postgres.isInitialized) postgres.stop()
        if (::network.isInitialized) network.close()
    }

    /** Fixture setup uses a raw admin JDBC connection -- separate from this service's own read-only pool, matching a real deployment's split between a setup/admin identity and the dedicated read-only role under test. */
    private fun seedFixtures(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "admin", "").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE SCHEMA iceberg.sales")
                st.execute("CREATE TABLE iceberg.sales.orders (id bigint, amount decimal(10,2), name varchar)")
                st.execute("INSERT INTO iceberg.sales.orders VALUES (1, DECIMAL '10.50', 'first')")
                st.execute("INSERT INTO iceberg.sales.orders VALUES (2, DECIMAL '20.00', 'second')")

                st.execute("CREATE SCHEMA iceberg.\"Odd Schema\"")
                st.execute(
                    "CREATE TABLE iceberg.\"Odd Schema\".\"Weird Table\" (\"Col With Space\" varchar, \"col-dash\" bigint)",
                )
                st.execute("INSERT INTO iceberg.\"Odd Schema\".\"Weird Table\" VALUES ('hello world', 42)")

                val wideColumns = (0 until 60).joinToString(", ") { "c$it bigint" }
                st.execute("CREATE TABLE iceberg.sales.wide_table ($wideColumns)")
                val wideValues = (0 until 60).joinToString(", ") { it.toString() }
                st.execute("INSERT INTO iceberg.sales.wide_table VALUES ($wideValues)")
            }
        }
    }

    @Test
    fun `lists every catalog visible to the connection, not just one`() {
        val catalogs = metadataService.listCatalogs()
        assertTrue(catalogs.contains("iceberg"), "expected iceberg among $catalogs")
        assertTrue(catalogs.contains("tpch"), "expected the base image's built-in tpch catalog among $catalogs")
    }

    @Test
    fun `lists schemas and tables reflecting real catalog metadata`() {
        val schemas = metadataService.listSchemas("iceberg")
        assertTrue(schemas.contains("sales"))

        val tables = metadataService.listTables("iceberg", "sales").map { it.name }
        assertTrue(tables.contains("orders"))
        assertTrue(tables.contains("wide_table"))
    }

    @Test
    fun `describes a table's columns with real types and nullability`() {
        val columns = metadataService.describeTable("iceberg", "sales", "orders")
        val byName = columns.associateBy { it.name }
        assertTrue(byName.containsKey("id"))
        assertTrue(byName.containsKey("amount"))
        assertTrue(byName["amount"]!!.type.contains("decimal"))
    }

    @Test
    fun `quoted schema, table, and column names with spaces and dashes round-trip correctly`() {
        val columns = metadataService.describeTable("iceberg", "Odd Schema", "Weird Table")
        val names = columns.map { it.name }
        assertTrue(names.contains("Col With Space"))
        assertTrue(names.contains("col-dash"))

        val out = ByteArrayOutputStream()
        val pseudoTable = metadataService.resolvePreviewTable("iceberg", "Odd Schema", "Weird Table")
        previewStreamer.stream("iceberg", "Odd Schema", pseudoTable, 10, 30, out)

        val json = ObjectMapper().readTree(out.toByteArray())
        assertEquals(1, json.path("rowCount").asInt())
        assertEquals("hello world", json.path("rows")[0][0].asText())
    }

    @Test
    fun `a wide table describes and previews all of its columns`() {
        val columns = metadataService.describeTable("iceberg", "sales", "wide_table")
        assertEquals(60, columns.size)

        val out = ByteArrayOutputStream()
        val pseudoTable = metadataService.resolvePreviewTable("iceberg", "sales", "wide_table")
        previewStreamer.stream("iceberg", "sales", pseudoTable, 10, 30, out)
        val json = ObjectMapper().readTree(out.toByteArray())
        assertEquals(60, json.path("columns").size())
        assertEquals(60, json.path("rows")[0].size())
    }

    @Test
    fun `an Iceberg table's $snapshots pseudo-table reflects one snapshot per commit`() {
        val out = ByteArrayOutputStream()
        val pseudoTable = PseudoTableName.parse("orders\$snapshots", trinoProperties.pseudoTables)
        metadataService.requireTableExists("iceberg", "sales", pseudoTable.baseTable)
        previewStreamer.stream("iceberg", "sales", pseudoTable, 100, 30, out)

        val json = ObjectMapper().readTree(out.toByteArray())
        // Two INSERTs in seedFixtures -> two Iceberg snapshots.
        assertEquals(2, json.path("rowCount").asInt())
        val columnNames = json.path("columns").map { it.path("name").asText() }
        assertTrue(columnNames.contains("snapshot_id"))
    }

    @Test
    fun `ddl returns the table's real CREATE TABLE text`() {
        val ddl = metadataService.tableDdl("iceberg", "sales", "orders")
        assertTrue(ddl.contains("CREATE TABLE"))
        assertTrue(ddl.contains("orders"))
    }

    @Test
    fun `preview enforces the server-side row cap regardless of a larger client-requested limit`() {
        val cappedProperties = trinoProperties.copy(preview = TrinoProperties.Preview(defaultRowLimit = 1, maxRowLimit = 1))
        val cappedStreamer = PreviewStreamer(dataSource, cappedProperties, ActiveStatementRegistry(), ObjectMapper().registerKotlinModule())

        val out = ByteArrayOutputStream()
        val pseudoTable = metadataService.resolvePreviewTable("iceberg", "sales", "orders")
        cappedStreamer.stream("iceberg", "sales", pseudoTable, 1000, 30, out)

        val json = ObjectMapper().readTree(out.toByteArray())
        assertEquals(1, json.path("limitApplied").asInt())
        assertEquals(1, json.path("rows").size())
    }

    @Test
    fun `a genuinely nonexistent catalog is a clean not-found, not a raw Trino error`() {
        assertThrows<CatalogNotFoundException> { metadataService.requireCatalogExists("does_not_exist_at_all") }
    }

    @Test
    fun `a genuinely nonexistent table is a clean not-found`() {
        assertThrows<TableNotFoundException> { metadataService.describeTable("iceberg", "sales", "no_such_table") }
    }
}
