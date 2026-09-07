package com.flusssync.nlapi.trino

import com.flusssync.nlapi.exception.InvalidIdentifierException

/**
 * The one place raw identifiers (catalog/schema/table/column names -- from
 * a URL path segment, or a name this service itself read back out of
 * `DatabaseMetaData`) are turned into SQL text. Every identifier is always
 * double-quoted, never string-concatenated unquoted, so Trino preserves its
 * exact case and there is no unquoted-identifier injection surface -- see
 * the build prompt's "never string-concatenate raw input... into SQL" hard
 * constraint.
 *
 * Quoting alone does not make an arbitrary string a *safe* identifier to
 * query, though -- see [TrinoMetadataService], which never uses a
 * caller-supplied name here without first confirming it's a name Trino
 * itself returned from `DatabaseMetaData` (i.e. it actually exists). A
 * quoted reference to a name nothing resolves to is not a security hole (it
 * fails as "not found"), but skipping that existence check is how a
 * Unicode-lookalike or hallucinated name could otherwise silently probe
 * around, rather than failing closed with a clear error.
 */
object IdentifierQuoting {

    /** Generous but finite -- long enough for any real Trino identifier, short enough to reject abuse early. */
    private const val MAX_IDENTIFIER_LENGTH = 255

    /**
     * Fast, cheap sanity check applied before an identifier is ever used in
     * a metadata lookup or quoted into SQL: rejects the empty string,
     * control characters (including newlines -- no reason a real Trino
     * identifier ever contains one), and unreasonable lengths. This is a
     * clear-error filter, not the security boundary -- quoting plus the
     * existence check described above is.
     */
    fun requireValidIdentifierSyntax(name: String, kind: String) {
        if (name.isEmpty()) {
            throw InvalidIdentifierException("$kind name must not be empty")
        }
        if (name.length > MAX_IDENTIFIER_LENGTH) {
            throw InvalidIdentifierException("$kind name exceeds $MAX_IDENTIFIER_LENGTH characters")
        }
        if (name.any { it.isISOControl() }) {
            throw InvalidIdentifierException("$kind name contains control characters")
        }
    }

    /** Double-quotes and escapes a single identifier part, e.g. `sa"les` -> `"sa""les"`. */
    fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** Quotes and joins a fully-qualified name, e.g. [catalog, schema, table] -> `"catalog"."schema"."table"`. */
    fun quoteQualified(parts: List<String>): String = parts.joinToString(".") { quote(it) }
}
