package com.flino.config.service

import com.flino.config.config.ConfigStoreProperties
import com.flino.config.dto.ColumnDto
import com.flino.config.dto.DestinationDto
import com.flino.config.dto.UpsertConfigRequest
import com.flino.config.exception.ConfigNotFoundException
import com.flino.config.exception.DuplicateConfigNameException
import com.flino.config.filestore.YamlConfigFileStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/**
 * Exercises [ConfigService] against a real [YamlConfigFileStore] on a real
 * temporary directory -- no mocked filesystem -- per the build prompt's
 * "integration-style tests against a real temp directory".
 */
class ConfigServiceIntegrationTest {

    private fun serviceAt(dir: Path): ConfigService {
        val props = ConfigStoreProperties(directory = dir.toString())
        return ConfigService(YamlConfigFileStore(props), ConfigValidator(props))
    }

    private fun request(id: String? = null, name: String = "orders_config") = UpsertConfigRequest(
        id = id,
        name = name,
        source = "orders.csv",
        destination = DestinationDto("iceberg", "sales", "orders"),
        columns = listOf(ColumnDto("id", "bigint", "primary key"), ColumnDto("amount", "decimal(10,2)")),
        businessDescription = "Orders placed by partners.",
        exampleQuestions = listOf("How many orders last week?"),
    )

    @Test
    fun `a brand-new, empty directory lists as an empty result, not an error`(@TempDir dir: Path) {
        assertTrue(serviceAt(dir).list().isEmpty())
    }

    @Test
    fun `create then read returns the full content back exactly`(@TempDir dir: Path) {
        val service = serviceAt(dir)

        val created = service.upsert(request()).config

        assertTrue(created.createdAt == created.updatedAt)
        UUID.fromString(created.id) // does not throw -- server-generated, well-formed

        val read = service.get(created.id)
        assertEquals(created, read)
    }

    @Test
    fun `list returns lightweight summaries only, not the full columns-businessDescription payload`(@TempDir dir: Path) {
        val service = serviceAt(dir)
        service.upsert(request())

        val summaries = service.list()

        assertEquals(1, summaries.size)
        val summary = summaries.single()
        assertEquals("orders_config", summary.name)
        assertEquals("orders.csv", summary.source)
        assertEquals("iceberg", summary.destination.catalog)
        // ConfigSummary's type itself has no columns/businessDescription
        // fields at all -- the shape enforces this, not just a convention.
    }

    @Test
    fun `upsert with an existing id overwrites that config in place`(@TempDir dir: Path) {
        val service = serviceAt(dir)
        val created = service.upsert(request(name = "v1")).config

        val updated = service.upsert(request(id = created.id, name = "v2")).config

        assertEquals(created.id, updated.id)
        assertEquals("v2", service.get(created.id).name)
        assertEquals(1, service.list().size, "an overwrite must not create a second file")
    }

    @Test
    fun `create rejects a duplicate name with a clear conflict, not a silent overwrite`(@TempDir dir: Path) {
        val service = serviceAt(dir)
        service.upsert(request(name = "orders_config"))

        assertThrows<DuplicateConfigNameException> { service.upsert(request(name = "orders_config")) }
        assertEquals(1, service.list().size)
    }

    @Test
    fun `reading an unknown id is a clean not-found`(@TempDir dir: Path) {
        assertThrows<ConfigNotFoundException> { serviceAt(dir).get(UUID.randomUUID().toString()) }
    }

    @Test
    fun `upsert for a well-formed but unknown id is a not-found, not a create`(@TempDir dir: Path) {
        val service = serviceAt(dir)
        assertThrows<ConfigNotFoundException> { service.upsert(request(id = UUID.randomUUID().toString())) }
        assertTrue(service.list().isEmpty())
    }
}
