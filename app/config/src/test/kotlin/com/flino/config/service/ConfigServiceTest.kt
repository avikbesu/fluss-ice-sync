package com.flino.config.service

import com.flino.config.dto.ColumnDto
import com.flino.config.dto.DestinationDto
import com.flino.config.dto.UpsertConfigRequest
import com.flino.config.exception.ConfigNotFoundException
import com.flino.config.exception.DuplicateConfigNameException
import com.flino.config.filestore.YamlConfigFileStore
import com.flino.config.testsupport.sampleConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class ConfigServiceTest {

    // relaxUnitFun -- registerName/renameIndexEntry return Unit and are
    // asserted via verify() where it matters; every other call stays strict.
    private val store = mockk<YamlConfigFileStore>(relaxUnitFun = true)
    private val validator = mockk<ConfigValidator>(relaxed = true)
    private val service = ConfigService(store, validator)

    private fun request(id: String? = null, name: String = "orders_config") = UpsertConfigRequest(
        id = id,
        name = name,
        source = "orders.csv",
        destination = DestinationDto("iceberg", "sales", "orders"),
        columns = listOf(ColumnDto("id", "bigint")),
        businessDescription = "desc",
        exampleQuestions = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        every { store.write(any()) } answers { firstArg() }
    }

    @Test
    fun `every upsert is validated before anything else happens`() {
        every { store.isNameTaken(any(), any()) } returns false

        service.upsert(request())

        verify(exactly = 1) { validator.validate(any()) }
    }

    @Test
    fun `create with no id generates a fresh server-side id and writes it`() {
        every { store.isNameTaken("orders_config", null) } returns false

        val result = service.upsert(request(id = null))

        assertTrue(result.created)
        assertNotEquals(null, UUID.fromString(result.config.id))
        verify { store.write(match { it.name == "orders_config" }) }
        verify { store.registerName("orders_config", result.config.id) }
    }

    @Test
    fun `create with a name already taken is rejected before any write`() {
        every { store.isNameTaken("orders_config", null) } returns true

        assertThrows<DuplicateConfigNameException> { service.upsert(request(id = null)) }

        verify(exactly = 0) { store.write(any()) }
        verify(exactly = 0) { store.registerName(any(), any()) }
    }

    @Test
    fun `update overwrites the existing file, preserving createdAt and updating updatedAt`() {
        val existing = sampleConfig(name = "old_name")
        every { store.read(UUID.fromString(existing.id)) } returns existing
        every { store.isNameTaken("new_name", existing.id) } returns false

        val result = service.upsert(request(id = existing.id, name = "new_name"))

        assertEquals(false, result.created)
        assertEquals(existing.id, result.config.id)
        assertEquals(existing.createdAt, result.config.createdAt)
        assertTrue(result.config.updatedAt >= existing.updatedAt)
        verify { store.renameIndexEntry("old_name", "new_name", existing.id) }
    }

    @Test
    fun `updating with an id that doesn't resolve to a real config is a clean not-found, not an implicit create`() {
        val unknownId = UUID.randomUUID().toString()
        every { store.read(UUID.fromString(unknownId)) } throws ConfigNotFoundException(unknownId)

        assertThrows<ConfigNotFoundException> { service.upsert(request(id = unknownId, name = "whatever")) }

        verify(exactly = 0) { store.write(any()) }
    }

    @Test
    fun `renaming to a name already owned by a different config is rejected before any write`() {
        val existing = sampleConfig(name = "old_name")
        every { store.read(UUID.fromString(existing.id)) } returns existing
        every { store.isNameTaken("taken_elsewhere", existing.id) } returns true

        assertThrows<DuplicateConfigNameException> { service.upsert(request(id = existing.id, name = "taken_elsewhere")) }

        verify(exactly = 0) { store.write(any()) }
    }

    @Test
    fun `renaming a config to its own current name is not a conflict`() {
        val existing = sampleConfig(name = "same_name")
        every { store.read(UUID.fromString(existing.id)) } returns existing
        every { store.isNameTaken("same_name", existing.id) } returns false

        val result = service.upsert(request(id = existing.id, name = "same_name"))

        assertEquals("same_name", result.config.name)
    }
}
