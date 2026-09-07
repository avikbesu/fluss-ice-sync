package com.flusssync.nlapi.trino

import com.flusssync.nlapi.config.TrinoProperties
import com.flusssync.nlapi.exception.InvalidIdentifierException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PseudoTableNameTest {

    private val pseudoTables = TrinoProperties.PseudoTables()

    @Test
    fun `a plain table name has no suffix`() {
        val parsed = PseudoTableName.parse("orders", pseudoTables)
        assertEquals("orders", parsed.baseTable)
        assertNull(parsed.suffix)
        assertEquals("orders", parsed.qualifiedIdentifier)
    }

    @Test
    fun `accepts an allowlisted pseudo-table suffix`() {
        val parsed = PseudoTableName.parse("orders\$snapshots", pseudoTables)
        assertEquals("orders", parsed.baseTable)
        assertEquals("snapshots", parsed.suffix)
        assertEquals("orders\$snapshots", parsed.qualifiedIdentifier)
    }

    @Test
    fun `rejects a suffix outside the allowlist`() {
        assertThrows<InvalidIdentifierException> { PseudoTableName.parse("orders\$madeup", pseudoTables) }
    }

    @Test
    fun `rejects an empty base table before the dollar sign`() {
        assertThrows<InvalidIdentifierException> { PseudoTableName.parse("\$snapshots", pseudoTables) }
    }

    @Test
    fun `rejects pseudo-tables entirely when disabled`() {
        val disabled = TrinoProperties.PseudoTables(enabled = false)
        assertThrows<InvalidIdentifierException> { PseudoTableName.parse("orders\$snapshots", disabled) }
    }

    @Test
    fun `still allows a plain table name when pseudo-tables are disabled`() {
        val disabled = TrinoProperties.PseudoTables(enabled = false)
        val parsed = PseudoTableName.parse("orders", disabled)
        assertEquals("orders", parsed.baseTable)
    }
}
