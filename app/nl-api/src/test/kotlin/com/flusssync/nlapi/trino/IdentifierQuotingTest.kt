package com.flusssync.nlapi.trino

import com.flusssync.nlapi.exception.InvalidIdentifierException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdentifierQuotingTest {

    @Test
    fun `quotes a plain identifier`() {
        assertEquals("\"orders\"", IdentifierQuoting.quote("orders"))
    }

    @Test
    fun `escapes an embedded double quote by doubling it`() {
        assertEquals("\"sa\"\"les\"", IdentifierQuoting.quote("sa\"les"))
    }

    @Test
    fun `quotes and joins a fully-qualified name`() {
        assertEquals("\"iceberg\".\"sales\".\"orders\"", IdentifierQuoting.quoteQualified(listOf("iceberg", "sales", "orders")))
    }

    @Test
    fun `preserves a name that looks like it contains SQL syntax -- quoting neutralizes it`() {
        assertEquals("\"orders\"\"; DROP TABLE x --\"", IdentifierQuoting.quote("orders\"; DROP TABLE x --"))
    }

    @Test
    fun `rejects an empty identifier`() {
        assertThrows<InvalidIdentifierException> { IdentifierQuoting.requireValidIdentifierSyntax("", "Table") }
    }

    @Test
    fun `rejects a control character`() {
        assertThrows<InvalidIdentifierException> { IdentifierQuoting.requireValidIdentifierSyntax("orders\nDROP", "Table") }
    }

    @Test
    fun `rejects an unreasonably long identifier`() {
        assertThrows<InvalidIdentifierException> { IdentifierQuoting.requireValidIdentifierSyntax("a".repeat(300), "Table") }
    }

    @Test
    fun `accepts a normal identifier`() {
        IdentifierQuoting.requireValidIdentifierSyntax("partner_orders_raw", "Table")
    }

    @Test
    fun `accepts a Unicode identifier -- not itself unsafe, existence check is the real defense`() {
        // A homoglyph name (Cyrillic 'а' instead of Latin 'a') is syntactically
        // fine here; it simply won't match any real catalog object later.
        IdentifierQuoting.requireValidIdentifierSyntax("sаles", "Schema")
    }
}
