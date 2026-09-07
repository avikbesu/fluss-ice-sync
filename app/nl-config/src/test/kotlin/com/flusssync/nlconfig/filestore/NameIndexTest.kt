package com.flusssync.nlconfig.filestore

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NameIndexTest {

    @Test
    fun `an unregistered name is never taken`() {
        assertFalse(NameIndex().isTaken("orders", excludingId = null))
    }

    @Test
    fun `a registered name is taken by anyone but its own owner`() {
        val index = NameIndex()
        index.register("orders", "id-1")

        assertTrue(index.isTaken("orders", excludingId = null))
        assertTrue(index.isTaken("orders", excludingId = "id-2"))
        assertFalse(index.isTaken("orders", excludingId = "id-1"))
    }

    @Test
    fun `name matching is case-insensitive`() {
        val index = NameIndex()
        index.register("Orders", "id-1")

        assertTrue(index.isTaken("orders", excludingId = null))
        assertTrue(index.isTaken("ORDERS", excludingId = "id-2"))
    }

    @Test
    fun `rename frees the old name and claims the new one`() {
        val index = NameIndex()
        index.register("orders", "id-1")

        index.rename("orders", "orders_v2", "id-1")

        assertFalse(index.isTaken("orders", excludingId = null))
        assertTrue(index.isTaken("orders_v2", excludingId = "id-2"))
    }

    @Test
    fun `renaming to the same name is a no-op, not a self-conflict`() {
        val index = NameIndex()
        index.register("orders", "id-1")

        index.rename("orders", "orders", "id-1")

        assertFalse(index.isTaken("orders", excludingId = "id-1"))
    }
}
