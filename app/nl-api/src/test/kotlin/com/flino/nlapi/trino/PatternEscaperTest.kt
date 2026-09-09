package com.flino.nlapi.trino

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DatabaseMetaData

class PatternEscaperTest {

    @Test
    fun `escapes LIKE metacharacters so a literal name can't accidentally match a sibling`() {
        val metaData = mockk<DatabaseMetaData>()
        every { metaData.searchStringEscape } returns "\\"

        // Unescaped, "sales_2024" would also LIKE-match "salesX2024" -- the
        // identifier/filter-injection edge case named in the build prompt.
        assertEquals("sales\\_2024", PatternEscaper.escape(metaData, "sales_2024"))
        assertEquals("100\\%done", PatternEscaper.escape(metaData, "100%done"))
        assertEquals("a\\\\b", PatternEscaper.escape(metaData, "a\\b"))
    }

    @Test
    fun `passes the name through unchanged when the driver reports no escape character`() {
        val metaData = mockk<DatabaseMetaData>()
        every { metaData.searchStringEscape } returns ""

        assertEquals("sales_2024", PatternEscaper.escape(metaData, "sales_2024"))
    }

    @Test
    fun `leaves a name with no metacharacters untouched`() {
        val metaData = mockk<DatabaseMetaData>()
        every { metaData.searchStringEscape } returns "\\"

        assertEquals("orders", PatternEscaper.escape(metaData, "orders"))
    }
}
