package com.flusssync.nlapi.trino

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataModelsTest {

    @Test
    fun `pages a middle slice and reports hasMore`() {
        val page = (1..10).toList().toPage(offset = 2, limit = 3)
        assertEquals(listOf(3, 4, 5), page.items)
        assertEquals(10, page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `the last page reports hasMore false`() {
        val page = (1..10).toList().toPage(offset = 8, limit = 5)
        assertEquals(listOf(9, 10), page.items)
        assertFalse(page.hasMore)
    }

    @Test
    fun `an offset past the end returns an empty page, not an error`() {
        val page = (1..3).toList().toPage(offset = 100, limit = 10)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(3, page.total)
    }
}
