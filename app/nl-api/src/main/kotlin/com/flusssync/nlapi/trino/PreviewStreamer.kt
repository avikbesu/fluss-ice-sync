package com.flusssync.nlapi.trino

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.flusssync.nlapi.config.TrinoProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import javax.sql.DataSource

/**
 * Streams the `preview` endpoint's rows straight from the JDBC `ResultSet`
 * into the HTTP response body, rather than materializing them in memory
 * first -- see the build prompt's "server-side LIMIT enforced regardless
 * of client-requested value, streamed rows, capped response bytes".
 *
 * Two independent caps apply, and either can end the stream early:
 * - **Row cap**: `LIMIT <effectiveLimit>` in the SQL itself, where
 *   `effectiveLimit` is the client's requested limit clamped to
 *   [TrinoProperties.Preview.maxRowLimit] -- never the raw client value.
 *   Trino itself never returns more rows than this, so it isn't reported
 *   as `truncated`; the response always carries the `limitApplied` value
 *   used so a caller can tell a capped preview from a genuinely short table.
 * - **Byte cap**: [TrinoProperties.Preview.maxResponseBytes], checked
 *   before each row is written. Hitting this ends the stream with fewer
 *   rows than `limitApplied` even though more may exist -- `truncated:
 *   true` signals exactly that case, since it can't be inferred from
 *   `rowCount` alone (a naturally short table also reports `rowCount <
 *   limitApplied`).
 *
 * By the time this runs, TrinoMetadataService has already confirmed the
 * catalog/schema/table exist -- required, since HTTP headers/status are
 * already committed once a streaming response begins, which is the one
 * real limitation this design accepts: a failure that happens *during* the
 * stream (a connector going unavailable mid-query, a race with a
 * concurrent DROP) can't be turned into a clean error response any more;
 * see the README's "edge cases not handled".
 */
@Component
class PreviewStreamer(
    private val dataSource: DataSource,
    private val trinoProperties: TrinoProperties,
    private val activeStatements: ActiveStatementRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(PreviewStreamer::class.java)

    fun stream(
        catalog: String,
        schema: String,
        pseudoTable: PseudoTableName,
        requestedLimit: Int,
        timeoutSeconds: Int,
        out: OutputStream,
    ) {
        val effectiveLimit = requestedLimit.coerceIn(1, trinoProperties.preview.maxRowLimit)
        val effectiveTimeout = timeoutSeconds.coerceAtMost(trinoProperties.query.maxTimeoutSeconds)
        val qualified = IdentifierQuoting.quoteQualified(listOf(catalog, schema, pseudoTable.qualifiedIdentifier))
        val sql = "SELECT * FROM $qualified LIMIT $effectiveLimit"

        val counting = CountingOutputStream(out)
        val generator = objectMapper.factory.createGenerator(counting)

        try {
            dataSource.connection.use { connection ->
                connection.isReadOnly = true
                connection.catalog = catalog
                connection.schema = schema
                connection.createStatement().use { statement ->
                    activeStatements.register(statement)
                    statement.queryTimeout = effectiveTimeout
                    try {
                        writeStream(generator, statement, sql, effectiveLimit, counting)
                    } finally {
                        activeStatements.unregister(statement)
                    }
                }
            }
        } catch (e: IOException) {
            // Broken pipe / reset connection -- the client disconnected mid-stream.
            // The registered statement is still cancelled via the `finally` above
            // (Statement.cancel() closing the underlying connection unblocks the
            // in-progress `executeQuery`/`next()` call), so the query doesn't keep
            // running server-side after nobody is left to read the result.
            log.debug("Preview stream interrupted, likely a client disconnect", e)
        } catch (e: Exception) {
            log.warn("Preview stream failed after headers were already committed", e)
        } finally {
            runCatching { generator.close() }
        }
    }

    private fun writeStream(
        generator: JsonGenerator,
        statement: java.sql.Statement,
        sql: String,
        effectiveLimit: Int,
        counting: CountingOutputStream,
    ) {
        statement.executeQuery(sql).use { rs ->
            val meta = rs.metaData
            generator.writeStartObject()

            generator.writeArrayFieldStart("columns")
            for (i in 1..meta.columnCount) {
                generator.writeStartObject()
                generator.writeStringField("name", meta.getColumnLabel(i))
                generator.writeStringField("type", meta.getColumnTypeName(i))
                generator.writeEndObject()
            }
            generator.writeEndArray()

            generator.writeArrayFieldStart("rows")
            var rowCount = 0
            var byteCapped = false
            while (rs.next()) {
                generator.flush()
                if (counting.count >= trinoProperties.preview.maxResponseBytes) {
                    byteCapped = true
                    break
                }
                generator.writeStartArray()
                for (i in 1..meta.columnCount) {
                    writeJsonValue(generator, TrinoValueMapper.map(rs.getObject(i)))
                }
                generator.writeEndArray()
                rowCount++
            }
            generator.writeEndArray()

            generator.writeNumberField("rowCount", rowCount)
            generator.writeNumberField("limitApplied", effectiveLimit)
            generator.writeBooleanField("truncated", byteCapped)
            generator.writeEndObject()
        }
    }

    private fun writeJsonValue(g: JsonGenerator, value: Any?) {
        when (value) {
            null -> g.writeNull()
            is Boolean -> g.writeBoolean(value)
            is Int -> g.writeNumber(value)
            is Long -> g.writeNumber(value)
            is Short -> g.writeNumber(value.toInt())
            is Byte -> g.writeNumber(value.toInt())
            is Float -> g.writeNumber(value)
            is Double -> g.writeNumber(value)
            is BigDecimal -> g.writeNumber(value)
            is String -> g.writeString(value)
            is List<*> -> {
                g.writeStartArray()
                value.forEach { writeJsonValue(g, it) }
                g.writeEndArray()
            }
            is Map<*, *> -> {
                g.writeStartObject()
                value.forEach { (k, v) -> g.writeFieldName(k.toString()); writeJsonValue(g, v) }
                g.writeEndObject()
            }
            else -> g.writeString(value.toString())
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
