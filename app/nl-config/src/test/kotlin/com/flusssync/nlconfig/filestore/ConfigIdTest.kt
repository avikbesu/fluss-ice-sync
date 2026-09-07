package com.flusssync.nlconfig.filestore

import com.flusssync.nlconfig.exception.InvalidConfigIdException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class ConfigIdTest {

    @Test
    fun `parses a well-formed UUID`() {
        val id = UUID.randomUUID()
        assertEquals(id, ConfigId.parse(id.toString()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "../../etc/passwd",
            "../../../secrets.yaml",
            "/etc/passwd",
            "not-a-uuid",
            "",
            "   ",
            "12345678-1234-1234-1234-12345678901Z",
            "..\\..\\windows\\system32",
            "id/with/slashes",
        ],
    )
    fun `rejects every path-traversal or malformed shape as an invalid id, never a filesystem call`(raw: String) {
        assertThrows<InvalidConfigIdException> { ConfigId.parse(raw) }
    }

    @Test
    fun `generate produces distinct ids`() {
        assertNotEquals(ConfigId.generate(), ConfigId.generate())
    }

    @Test
    fun `filename is derived from the parsed UUID's canonical form, not raw input`() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        assertEquals("123e4567-e89b-12d3-a456-426614174000.yaml", ConfigId.filename(id))
    }
}
