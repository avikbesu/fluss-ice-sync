package com.flusssync.nlapi.trino

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class TrinoValueMapperTest {

    @Test
    fun `maps null through unchanged`() {
        assertNull(TrinoValueMapper.map(null))
    }

    @Test
    fun `keeps DECIMAL as an exact BigDecimal, not a lossy double`() {
        val decimal = BigDecimal("12345678901234567890.123456789")
        assertEquals(decimal, TrinoValueMapper.map(decimal))
    }

    @Test
    fun `encodes VARBINARY as base64`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        assertEquals(Base64.getEncoder().encodeToString(bytes), TrinoValueMapper.map(bytes))
    }

    @Test
    fun `renders UUID as its string form`() {
        val uuid = UUID.randomUUID()
        assertEquals(uuid.toString(), TrinoValueMapper.map(uuid))
    }

    @Test
    fun `renders a TIMESTAMP as an ISO-8601 string`() {
        val ts = Timestamp.valueOf("2024-05-01 10:30:00")
        assertEquals("2024-05-01T10:30", TrinoValueMapper.map(ts))
    }

    @Test
    fun `renders TIMESTAMP WITH TIME ZONE preserving its offset`() {
        val odt = OffsetDateTime.of(2024, 5, 1, 10, 30, 0, 0, ZoneOffset.ofHours(-7))
        assertEquals(odt.toString(), TrinoValueMapper.map(odt))
    }

    @Test
    fun `recursively maps an ARRAY of primitives via java-sql-Array`() {
        val sqlArray = mockk<java.sql.Array>()
        every { sqlArray.array } returns arrayOf(1, 2, 3)
        assertEquals(listOf(1, 2, 3), TrinoValueMapper.map(sqlArray))
    }

    @Test
    fun `recursively maps a nested ARRAY of ARRAY`() {
        val inner = mockk<java.sql.Array>()
        every { inner.array } returns arrayOf("a", "b")
        val outer = mockk<java.sql.Array>()
        every { outer.array } returns arrayOf(inner)
        assertEquals(listOf(listOf("a", "b")), TrinoValueMapper.map(outer))
    }

    @Test
    fun `recursively maps a MAP, stringifying keys and mapping values`() {
        val map: Map<Any?, Any?> = mapOf(1 to BigDecimal("1.5"), 2 to null)
        assertEquals(mapOf("1" to BigDecimal("1.5"), "2" to null), TrinoValueMapper.map(map))
    }

    @Test
    fun `falls back to toString for an unrecognized type -- e-g- a ROW`() {
        class OpaqueRowLike(private val text: String) {
            override fun toString() = text
        }
        assertEquals("{field1=1, field2=abc}", TrinoValueMapper.map(OpaqueRowLike("{field1=1, field2=abc}")))
    }

    @Test
    fun `passes primitives through unchanged`() {
        assertEquals(true, TrinoValueMapper.map(true))
        assertEquals(42, TrinoValueMapper.map(42))
        assertEquals(42L, TrinoValueMapper.map(42L))
        assertEquals("hello", TrinoValueMapper.map("hello"))
    }
}
