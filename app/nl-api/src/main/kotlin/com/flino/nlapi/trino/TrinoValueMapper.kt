package com.flino.nlapi.trino

import java.math.BigDecimal
import java.sql.Timestamp
import java.util.Base64
import java.util.UUID

/**
 * Converts a single JDBC column value (from `ResultSet.getObject`) into a
 * plain Kotlin value Jackson can serialize directly -- the one place every
 * Trino type this service might return is normalized, for both the
 * materialized query path (TrinoQueryExecutor) and the streamed preview
 * path (PreviewStreamer), so the two never drift into different JSON
 * shapes for the same column type.
 *
 * Full-fidelity coverage: BOOLEAN, all integer/floating types, DECIMAL
 * (kept as a JSON number via BigDecimal, not stringified -- Jackson renders
 * it exactly, no double-precision rounding), VARCHAR/CHAR, VARBINARY
 * (base64 string), DATE/TIME/TIMESTAMP/TIMESTAMP WITH TIME ZONE (ISO-8601
 * strings), UUID, JSON (Trino returns this as a VARCHAR-like string
 * already), ARRAY, and MAP (both recursively mapped). NULL passes through
 * as JSON null in every case, including nested inside an array/map.
 *
 * **Known limitation -- ROW**: the JDBC driver does not expose a single
 * stable, structured Java representation of `ROW` across versions, and
 * introspecting it correctly would mean depending on trino-jdbc internals
 * this module has no compiled-in guarantee about. ROW values fall through
 * to [Any.toString], Trino's own textual `{field=value, ...}`-style
 * rendering, rather than a nested JSON object with named fields. See the
 * README's "edge cases not handled" section.
 */
object TrinoValueMapper {

    fun map(value: Any?): Any? = when (value) {
        null -> null
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is String -> value
        is BigDecimal -> value
        is ByteArray -> Base64.getEncoder().encodeToString(value)
        is UUID -> value.toString()
        is Timestamp -> value.toLocalDateTime().toString()
        is java.sql.Date -> value.toLocalDate().toString()
        is java.sql.Time -> value.toLocalTime().toString()
        is java.time.temporal.Temporal -> value.toString()
        is java.sql.Array -> mapSqlArray(value)
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to map(v) }
        is Collection<*> -> value.map { map(it) }
        is Array<*> -> value.map { map(it) }
        else -> value.toString()
    }

    private fun mapSqlArray(array: java.sql.Array): List<Any?> {
        val raw = array.array
        return when (raw) {
            is Array<*> -> raw.map { map(it) }
            is IntArray -> raw.map { it }
            is LongArray -> raw.map { it }
            is DoubleArray -> raw.map { it }
            is FloatArray -> raw.map { it }
            is BooleanArray -> raw.map { it }
            is ShortArray -> raw.map { it }
            is ByteArray -> raw.map { it }
            else -> listOf(raw?.toString())
        }
    }
}
