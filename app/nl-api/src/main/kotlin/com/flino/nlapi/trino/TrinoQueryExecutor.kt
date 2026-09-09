package com.flino.nlapi.trino

import com.flino.nlapi.config.TrinoProperties
import com.flino.nlapi.exception.QueryTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * Runs every read-only statement this service issues against Trino --
 * `SHOW`/`DESCRIBE`/preview `SELECT`s the metadata endpoints build, and
 * validated `/ask`-generated SQL alike (same executor, same pipeline).
 *
 * Query timeouts and cancellation: [Statement.setQueryTimeout] asks the
 * Trino JDBC driver itself to cancel the query server-side once the limit
 * elapses, which is the primary mechanism. On top of that, the calling
 * thread races a `Future.get(timeout)` against a dedicated bounded pool
 * (see [TrinoDataSourceConfig.trinoQueryExecutorService]) and, on timeout,
 * calls `Statement.cancel()` itself via an [AtomicReference] the worker
 * publishes as soon as the statement exists -- `Statement.cancel()` is
 * specifically designed to be called from another thread while a query is
 * running, and this is the second, independent path that ensures a hung
 * query gets cancelled even if the driver's own timeout mechanism doesn't
 * fire (e.g. a network stall before the query ever starts).
 *
 * Client-disconnect cancellation for the (large, potentially long-running)
 * `preview` endpoint specifically is handled by [PreviewStreamer], which
 * keeps its own `Statement` reference and cancels it the moment a write to
 * the HTTP response fails. Materialized calls here are metadata-sized and
 * normally sub-second, so disconnect-triggered cancellation isn't wired
 * for them individually -- see the README's "edge cases not handled".
 */
@Component
class TrinoQueryExecutor(
    private val dataSource: DataSource,
    private val executor: ExecutorService,
    private val trinoProperties: TrinoProperties,
    private val exceptionTranslator: TrinoExceptionTranslator,
    private val activeStatements: ActiveStatementRegistry,
) {
    private val log = LoggerFactory.getLogger(TrinoQueryExecutor::class.java)

    /** Grace period added on top of the statement's own query timeout before this side gives up waiting and force-cancels. */
    private val cancelGraceSeconds = 5L

    fun executeMaterialized(
        catalog: String?,
        schema: String?,
        sql: String,
        timeoutSeconds: Int = trinoProperties.query.defaultTimeoutSeconds,
        maxRows: Int = trinoProperties.listing.maxPageSize,
    ): QueryResult {
        val effectiveTimeout = timeoutSeconds.coerceAtMost(trinoProperties.query.maxTimeoutSeconds)
        val statementRef = AtomicReference<Statement?>()

        val future = executor.submit(
            Callable {
                runQuery(catalog, schema, sql, effectiveTimeout, maxRows, statementRef)
            },
        )

        return try {
            future.get(effectiveTimeout + cancelGraceSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            cancelQuietly(statementRef.get())
            future.cancel(true)
            throw QueryTimeoutException("Query exceeded ${effectiveTimeout}s and was cancelled.")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw exceptionTranslator.translate(e.cause ?: e, catalog)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelQuietly(statementRef.get())
            throw exceptionTranslator.translate(e, catalog)
        }
    }

    private fun runQuery(
        catalog: String?,
        schema: String?,
        sql: String,
        timeoutSeconds: Int,
        maxRows: Int,
        statementRef: AtomicReference<Statement?>,
    ): QueryResult {
        val started = System.nanoTime()
        openConnection(catalog, schema).use { connection ->
            connection.createStatement().use { statement ->
                statementRef.set(statement)
                activeStatements.register(statement)
                statement.queryTimeout = timeoutSeconds
                try {
                    statement.executeQuery(sql).use { rs ->
                        val meta = rs.metaData
                        val columns = (1..meta.columnCount).map {
                            ColumnMeta(meta.getColumnLabel(it), meta.getColumnTypeName(it))
                        }
                        val rows = mutableListOf<List<Any?>>()
                        var truncated = false
                        while (rs.next()) {
                            if (rows.size >= maxRows) {
                                truncated = true
                                break
                            }
                            rows += (1..meta.columnCount).map { TrinoValueMapper.map(rs.getObject(it)) }
                        }
                        val durationMs = (System.nanoTime() - started) / 1_000_000
                        return QueryResult(columns, rows, rows.size, truncated, durationMs)
                    }
                } catch (e: Exception) {
                    log.debug("Trino query failed", e)
                    throw exceptionTranslator.translate(e, catalog)
                } finally {
                    activeStatements.unregister(statement)
                }
            }
        }
    }

    /** Opens a pooled connection scoped to [catalog]/[schema] -- see TrinoDataSourceConfig for why the pool itself isn't pinned to one. */
    fun openConnection(catalog: String?, schema: String?): Connection {
        val connection = dataSource.connection
        try {
            connection.isReadOnly = true
            catalog?.let { connection.catalog = it }
            schema?.let { connection.schema = it }
            return connection
        } catch (e: Exception) {
            connection.close()
            throw exceptionTranslator.translate(e, catalog)
        }
    }

    private fun cancelQuietly(statement: Statement?) {
        if (statement == null) return
        try {
            statement.cancel()
        } catch (e: Exception) {
            log.debug("Failed to cancel Trino statement (best-effort)", e)
        }
    }
}
