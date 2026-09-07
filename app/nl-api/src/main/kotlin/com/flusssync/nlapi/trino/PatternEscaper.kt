package com.flusssync.nlapi.trino

import java.sql.DatabaseMetaData

/**
 * `DatabaseMetaData.getSchemas`/`getTables`/`getColumns` all treat their
 * catalog/schema/table-name arguments as SQL `LIKE`-style search patterns
 * (`%`/`_` are wildcards), not literal names -- passing a caller-supplied
 * identifier straight through would let a name containing `_` or `%`
 * silently match sibling catalogs/schemas/tables it has no business
 * matching (e.g. a schema literally named `sales_2024` would, unescaped,
 * also match `salesX2024`). This is the identifier/filter-injection edge
 * case named in the build prompt, just via JDBC metadata patterns rather
 * than string-concatenated SQL. [TrinoMetadataService] escapes every
 * identifier through here before it reaches a `DatabaseMetaData` call, and
 * separately exact-matches results in Kotlin before trusting them.
 */
object PatternEscaper {
    fun escape(metaData: DatabaseMetaData, raw: String): String {
        val escapeChar = metaData.searchStringEscape
        if (escapeChar.isNullOrEmpty()) return raw
        val sb = StringBuilder(raw.length + 4)
        for (c in raw) {
            if (c == '%' || c == '_' || c.toString() == escapeChar) {
                sb.append(escapeChar)
            }
            sb.append(c)
        }
        return sb.toString()
    }
}
