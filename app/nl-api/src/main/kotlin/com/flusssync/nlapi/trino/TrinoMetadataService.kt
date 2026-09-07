package com.flusssync.nlapi.trino

import com.flusssync.nlapi.config.TrinoProperties
import com.flusssync.nlapi.exception.CatalogNotFoundException
import com.flusssync.nlapi.exception.InvalidIdentifierException
import com.flusssync.nlapi.exception.QueryTimeoutException
import com.flusssync.nlapi.exception.SchemaNotFoundException
import com.flusssync.nlapi.exception.TableNotFoundException
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Every metadata/browsing endpoint (catalogs, schemas, tables, describe,
 * ddl) plus the entry point `/ask` uses to resolve what a question is
 * actually asking about. Listing calls go through `DatabaseMetaData`
 * directly (no SQL text at all -- see [PatternEscaper] for why its
 * catalog/schema/table arguments still need escaping); `ddl` is the one
 * method here that builds and runs actual SQL (`SHOW CREATE TABLE`),
 * through [TrinoSqlValidator] like everything else that does.
 *
 * **Every existence check here is the "not found vs. temporarily
 * unavailable" boundary** the build prompt calls out: a catalog/schema/
 * table absent from Trino's own metadata is a clean 404
 * ([CatalogNotFoundException]/[SchemaNotFoundException]/
 * [TableNotFoundException]); a `DatabaseMetaData` call itself throwing
 * (the coordinator unreachable, a connector down) is a 503 via
 * [TrinoExceptionTranslator] instead -- callers can tell "this doesn't
 * exist" from "this exists but Trino can't reach it right now" apart.
 */
@Service
class TrinoMetadataService(
    private val dataSource: DataSource,
    private val executor: ExecutorService,
    private val trinoProperties: TrinoProperties,
    private val exceptionTranslator: TrinoExceptionTranslator,
    private val queryExecutor: TrinoQueryExecutor,
    private val sqlValidator: TrinoSqlValidator,
) {
    fun listCatalogs(): List<String> = runMeta(null) { connection ->
        connection.metaData.catalogs.use { rs -> collectColumn(rs, "TABLE_CAT") }
    }.sorted()

    fun listSchemas(catalog: String): List<String> {
        requireCatalogExists(catalog)
        return runMeta(catalog) { connection ->
            val escapedCatalog = PatternEscaper.escape(connection.metaData, catalog)
            connection.metaData.getSchemas(escapedCatalog, null).use { rs -> collectColumn(rs, "TABLE_SCHEM") }
        }
            // Trino's own information_schema is present in every catalog and is
            // rarely what a browsing client wants alongside real business
            // schemas -- filtered out here for the same reason app/ui/bff's
            // schema dropdown already does (see doc/design/v2-web-ui-design.md).
            .filterNot { it.equals("information_schema", ignoreCase = true) }
            .sorted()
    }

    fun listTables(catalog: String, schema: String): List<TableSummary> {
        requireSchemaExists(catalog, schema)
        return runMeta(catalog) { connection ->
            val meta = connection.metaData
            val escapedCatalog = PatternEscaper.escape(meta, catalog)
            val escapedSchema = PatternEscaper.escape(meta, schema)
            meta.getTables(escapedCatalog, escapedSchema, null, arrayOf("TABLE", "VIEW")).use { rs ->
                val out = mutableListOf<TableSummary>()
                while (rs.next()) {
                    out += TableSummary(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE") ?: "TABLE")
                }
                out
            }
        }.sortedBy { it.name }
    }

    fun describeTable(catalog: String, schema: String, rawTable: String): List<ColumnDescription> {
        val pseudoTable = PseudoTableName.parse(rawTable, trinoProperties.pseudoTables)
        requireTableExists(catalog, schema, pseudoTable.baseTable)

        return if (pseudoTable.suffix == null) {
            describeRealTableColumns(catalog, schema, pseudoTable.baseTable)
        } else {
            describePseudoTableColumns(catalog, schema, pseudoTable)
        }
    }

    /** `SHOW CREATE TABLE` -- routed through the same validator/executor as everything else, not a bespoke path. */
    fun tableDdl(catalog: String, schema: String, rawTable: String): String {
        val pseudoTable = PseudoTableName.parse(rawTable, trinoProperties.pseudoTables)
        if (pseudoTable.suffix != null) {
            throw InvalidIdentifierException("DDL is only available for real tables, not metadata pseudo-tables like '\$${pseudoTable.suffix}'.")
        }
        requireTableExists(catalog, schema, pseudoTable.baseTable)

        val qualified = IdentifierQuoting.quoteQualified(listOf(catalog, schema, pseudoTable.baseTable))
        val sql = "SHOW CREATE TABLE $qualified"
        sqlValidator.assertReadOnly(sql)

        val result = queryExecutor.executeMaterialized(catalog, schema, sql, maxRows = 1)
        return result.rows.firstOrNull()?.firstOrNull()?.toString()
            ?: throw TableNotFoundException(catalog, schema, pseudoTable.baseTable)
    }

    fun resolvePreviewTable(catalog: String, schema: String, rawTable: String): PseudoTableName {
        val pseudoTable = PseudoTableName.parse(rawTable, trinoProperties.pseudoTables)
        requireTableExists(catalog, schema, pseudoTable.baseTable)
        return pseudoTable
    }

    fun requireCatalogExists(catalog: String) {
        IdentifierQuoting.requireValidIdentifierSyntax(catalog, "Catalog")
        if (!listCatalogs().any { it.equals(catalog, ignoreCase = false) }) {
            throw CatalogNotFoundException(catalog)
        }
    }

    fun requireSchemaExists(catalog: String, schema: String) {
        IdentifierQuoting.requireValidIdentifierSyntax(schema, "Schema")
        requireCatalogExists(catalog)
        val schemas = runMeta(catalog) { connection ->
            val escapedCatalog = PatternEscaper.escape(connection.metaData, catalog)
            connection.metaData.getSchemas(escapedCatalog, null).use { rs -> collectColumn(rs, "TABLE_SCHEM") }
        }
        if (schemas.none { it == schema }) {
            throw SchemaNotFoundException(catalog, schema)
        }
    }

    fun requireTableExists(catalog: String, schema: String, table: String) {
        IdentifierQuoting.requireValidIdentifierSyntax(table, "Table")
        requireSchemaExists(catalog, schema)
        val exists = runMeta(catalog) { connection ->
            val meta = connection.metaData
            val escapedCatalog = PatternEscaper.escape(meta, catalog)
            val escapedSchema = PatternEscaper.escape(meta, schema)
            val escapedTable = PatternEscaper.escape(meta, table)
            meta.getTables(escapedCatalog, escapedSchema, escapedTable, arrayOf("TABLE", "VIEW")).use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("TABLE_NAME") == table) found = true
                }
                found
            }
        }
        if (!exists) throw TableNotFoundException(catalog, schema, table)
    }

    private fun describeRealTableColumns(catalog: String, schema: String, table: String): List<ColumnDescription> =
        runMeta(catalog) { connection ->
            val meta = connection.metaData
            val escapedCatalog = PatternEscaper.escape(meta, catalog)
            val escapedSchema = PatternEscaper.escape(meta, schema)
            val escapedTable = PatternEscaper.escape(meta, table)
            meta.getColumns(escapedCatalog, escapedSchema, escapedTable, null).use { rs ->
                val out = mutableListOf<ColumnDescription>()
                while (rs.next()) {
                    if (rs.getString("TABLE_NAME") != table) continue
                    out += ColumnDescription(
                        name = rs.getString("COLUMN_NAME"),
                        type = rs.getString("TYPE_NAME"),
                        nullable = rs.getString("IS_NULLABLE")?.equals("YES", ignoreCase = true) ?: true,
                        comment = rs.getString("REMARKS")?.takeIf { it.isNotBlank() },
                        ordinalPosition = rs.getInt("ORDINAL_POSITION"),
                    )
                }
                out.sortedBy { it.ordinalPosition }
            }
        }

    /**
     * Pseudo-tables (`$snapshots` etc.) aren't visible to `DatabaseMetaData.getColumns` --
     * they're only reachable by actually selecting from them. `LIMIT 0` gets column
     * name/type from `ResultSetMetaData` without scanning any data; nullability and
     * comments aren't available this way, so both report as unknown (`nullable = true`,
     * `comment = null`) rather than guessed.
     */
    private fun describePseudoTableColumns(catalog: String, schema: String, pseudoTable: PseudoTableName): List<ColumnDescription> {
        val qualified = IdentifierQuoting.quoteQualified(listOf(catalog, schema, pseudoTable.qualifiedIdentifier))
        val sql = "SELECT * FROM $qualified LIMIT 0"
        sqlValidator.assertReadOnly(sql)
        val result = queryExecutor.executeMaterialized(catalog, schema, sql, maxRows = 0)
        return result.columns.mapIndexed { index, col ->
            ColumnDescription(name = col.name, type = col.type, nullable = true, comment = null, ordinalPosition = index + 1)
        }
    }

    private fun collectColumn(rs: ResultSet, columnLabel: String): List<String> {
        val out = mutableListOf<String>()
        while (rs.next()) {
            rs.getString(columnLabel)?.let { out += it }
        }
        return out
    }

    /**
     * Bounds how long a `DatabaseMetaData` call is waited on -- unlike
     * [TrinoQueryExecutor], there's no `Statement` to call `cancel()` on
     * for these (JDBC's metadata API exposes no cancellation handle), so a
     * timeout here lets the caller give up promptly but doesn't guarantee
     * the underlying call stops server-side. Acceptable for lightweight
     * catalog introspection; see the README's "edge cases not handled".
     */
    private fun <T> runMeta(catalog: String?, block: (Connection) -> T): T {
        val timeoutSeconds = trinoProperties.query.defaultTimeoutSeconds
        val future = executor.submit(
            Callable {
                dataSource.connection.use { connection ->
                    connection.isReadOnly = true
                    block(connection)
                }
            },
        )
        return try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw exceptionTranslator.translate(
                QueryTimeoutException("Metadata lookup exceeded ${timeoutSeconds}s."),
                catalog,
            )
        } catch (e: java.util.concurrent.ExecutionException) {
            throw exceptionTranslator.translate(e.cause ?: e, catalog)
        }
    }
}
