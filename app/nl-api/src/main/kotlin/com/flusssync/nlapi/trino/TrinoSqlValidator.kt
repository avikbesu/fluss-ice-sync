package com.flusssync.nlapi.trino

import com.flusssync.nlapi.exception.StatementNotAllowedException
import org.springframework.stereotype.Component

/**
 * The single read-only allowlist gate every statement passes through before
 * it reaches Trino -- whether it's SQL this service built itself
 * (SHOW/DESCRIBE against a validated identifier) or SQL Claude generated
 * for `/ask`. Per the build prompt's hard constraint, LLM output is never
 * trusted more than raw user input: both paths call [assertReadOnly].
 *
 * This is defense-in-depth, not the only boundary -- the dedicated
 * `nl-api-read-role` Trino user (see TrinoProperties/rules.json) only ever
 * holds SELECT grants, so a statement that somehow slipped past this
 * validator would still be rejected by Trino's own access control. But
 * failing here first means a clear, structured 422 instead of an opaque
 * Trino "Access Denied", and means a mistake in that Trino-side grant isn't
 * the only thing standing between a write statement and execution.
 *
 * Deliberately **not** a full SQL parser (see [SqlLexer]) -- it only proves
 * enough structure to answer one question: "is the statement's real leading
 * keyword one Trino treats as read-only?", robust to whitespace/casing,
 * `--`/`/* */` comments, and a `WITH ... AS (...)` CTE prefix in front of
 * the true statement type (all three are exactly the disguises the build
 * prompt calls out: comments, CTEs, case variation).
 */
@Component
class TrinoSqlValidator {

    companion object {
        /** Mirrors app/ui/bff/src/trino.ts's ALLOWED_LEADING_KEYWORDS -- this module's stricter, comment/CTE-aware version of the same policy. */
        val READ_ONLY_LEADING_KEYWORDS = setOf("select", "show", "describe", "desc", "explain")
    }

    /** @throws StatementNotAllowedException if [sql] is not a single, read-only statement. */
    fun assertReadOnly(sql: String) {
        val tokens = SqlLexer.tokenize(sql)
        val statements = SqlLexer.splitStatements(tokens)

        if (statements.isEmpty()) {
            throw StatementNotAllowedException("No statement was provided.")
        }
        if (statements.size > 1) {
            throw StatementNotAllowedException("Only a single statement is supported.")
        }

        val leading = leadingKeyword(statements[0])
            ?: throw StatementNotAllowedException("Statement must begin with a recognizable keyword.")

        if (leading !in READ_ONLY_LEADING_KEYWORDS) {
            throw StatementNotAllowedException(
                "Only ${READ_ONLY_LEADING_KEYWORDS.joinToString(", ") { it.uppercase() }} statements are supported; " +
                    "got '${leading.uppercase()}'.",
            )
        }
    }

    /**
     * Returns the statement's effective leading keyword, lower-cased -- for
     * a bare statement that's just its first word; for `WITH <cte-list>
     * <stmt>`, this walks past the (possibly multiple) CTE definitions to
     * the keyword that actually follows them, since that -- not "WITH" --
     * is what determines whether the statement reads or writes. Returns
     * null if the token stream doesn't parse as a recognizable statement
     * shape at all (caller treats that as "not allowed").
     */
    private fun leadingKeyword(tokens: List<SqlLexer.Token>): String? {
        if (tokens.isEmpty()) return null
        val first = tokens[0]
        if (first !is SqlLexer.Word) return null

        if (!first.text.equals("with", ignoreCase = true)) {
            return first.text.lowercase()
        }

        var idx = 1
        while (true) {
            // CTE name.
            if (idx >= tokens.size || tokens[idx] !is SqlLexer.Word) return null
            idx++

            // Optional column-alias list: (col1, col2, ...).
            if (idx < tokens.size && tokens[idx] == SqlLexer.LParen) {
                idx = skipBalancedParens(tokens, idx) ?: return null
            }

            // AS ( <query> )
            val asWord = tokens.getOrNull(idx) as? SqlLexer.Word ?: return null
            if (!asWord.text.equals("as", ignoreCase = true)) return null
            idx++
            if (tokens.getOrNull(idx) != SqlLexer.LParen) return null
            idx = skipBalancedParens(tokens, idx) ?: return null

            if (tokens.getOrNull(idx) == SqlLexer.Comma) {
                idx++
                continue
            }
            break
        }

        val terminal = tokens.getOrNull(idx) as? SqlLexer.Word ?: return null
        // Trino only allows WITH to precede a query (SELECT); INSERT/UPDATE/
        // DELETE/MERGE are the write-side equivalent in the ANSI grammar, and
        // SHOW/DESCRIBE/EXPLAIN never follow a WITH clause at all. Returning
        // anything other than the literal terminal keyword here would let a
        // non-SELECT statement ride through on WITH's coattails; the outer
        // allowed-keyword check alone isn't enough, since e.g. "describe" is
        // itself read-only but "WITH x AS (...) DESCRIBE y" isn't a
        // statement shape that should ever be accepted.
        val terminalKeyword = terminal.text.lowercase()
        return if (terminalKeyword == "select") terminalKeyword else null
    }

    /** Given the index of an [SqlLexer.LParen], returns the index just past its matching close, or null if unbalanced. */
    private fun skipBalancedParens(tokens: List<SqlLexer.Token>, openIdx: Int): Int? {
        var depth = 0
        var idx = openIdx
        while (idx < tokens.size) {
            when (tokens[idx]) {
                SqlLexer.LParen -> depth++
                SqlLexer.RParen -> {
                    depth--
                    if (depth == 0) return idx + 1
                }
                else -> {}
            }
            idx++
        }
        return null
    }
}
