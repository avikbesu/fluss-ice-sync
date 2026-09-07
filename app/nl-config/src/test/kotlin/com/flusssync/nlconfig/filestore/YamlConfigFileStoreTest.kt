package com.flusssync.nlconfig.filestore

import com.flusssync.nlconfig.config.ConfigStoreProperties
import com.flusssync.nlconfig.exception.ConfigNotFoundException
import com.flusssync.nlconfig.exception.ConfigStorageException
import com.flusssync.nlconfig.exception.CorruptConfigFileException
import com.flusssync.nlconfig.testsupport.sampleConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name

class YamlConfigFileStoreTest {

    private fun storeAt(dir: Path) = YamlConfigFileStore(ConfigStoreProperties(directory = dir.toString()))

    @Test
    fun `writes and reads a config round-trip exactly`(@TempDir dir: Path) {
        val store = storeAt(dir)
        val config = sampleConfig()

        store.write(config)

        assertEquals(config, store.read(UUID.fromString(config.id)))
    }

    @Test
    fun `read for a missing id is a clean not-found, not a filesystem exception`(@TempDir dir: Path) {
        val store = storeAt(dir)
        assertThrows<ConfigNotFoundException> { store.read(UUID.randomUUID()) }
    }

    @Test
    fun `an empty or not-yet-created directory lists as empty, not an error`(@TempDir dir: Path) {
        val notYetCreated = dir.resolve("does-not-exist-yet")
        val store = storeAt(notYetCreated)

        assertTrue(store.listSummaries().isEmpty())
    }

    @Test
    fun `a normal write leaves no stray temp file behind`(@TempDir dir: Path) {
        val store = storeAt(dir)
        store.write(sampleConfig())

        val entries = Files.list(dir).use { it.toList() }
        assertTrue(entries.all { it.name.endsWith(".yaml") }, "expected only .yaml files, found: $entries")
    }

    @Test
    fun `overwriting an existing id replaces its content atomically -- update is all-or-nothing`(@TempDir dir: Path) {
        val store = storeAt(dir)
        val id = UUID.randomUUID().toString()
        store.write(sampleConfig(id = id, name = "v1"))

        store.write(sampleConfig(id = id, name = "v2"))

        assertEquals("v2", store.read(UUID.fromString(id)).name)
        val entries = Files.list(dir).use { it.toList() }
        assertEquals(1, entries.size, "an update must not leave both an old and a new file")
    }

    @Test
    fun `a leftover temp file from a simulated crash mid-write is invisible to readers -- proves atomicity's observable guarantee`(@TempDir dir: Path) {
        val store = storeAt(dir)
        val config = sampleConfig(name = "stable")
        store.write(config)

        // Simulates exactly what a crash between "serialize to temp file" and
        // "atomic rename" would leave behind: a stray, possibly-garbage temp
        // file that was never moved into place.
        val strayTemp = dir.resolve(".tmp-${config.id}-crashed.tmp")
        Files.writeString(strayTemp, "not: [valid, yaml,")

        // The real config is untouched -- a reader can only ever see the last
        // complete, atomically-renamed file, never the crash artifact.
        assertEquals(config, store.read(UUID.fromString(config.id)))
        val summaries = store.listSummaries()
        assertEquals(1, summaries.size)
        assertEquals("stable", summaries.single().name)
    }

    @Test
    fun `a corrupt config file is skipped in listing but errors clearly on a direct read`(@TempDir dir: Path) {
        val store = storeAt(dir)
        val goodId = UUID.randomUUID().toString()
        store.write(sampleConfig(id = goodId, name = "good"))

        val corruptId = UUID.randomUUID()
        Files.writeString(dir.resolve("$corruptId.yaml"), "{ this is not: valid yaml [[[")

        val summaries = store.listSummaries()
        assertEquals(listOf("good"), summaries.map { it.name })

        assertThrows<CorruptConfigFileException> { store.read(corruptId) }
    }

    @Test
    fun `write fails with a clear storage error when the target path is unusable, not a silent no-op`(@TempDir dir: Path) {
        // Force Files.createDirectories to fail deterministically (and
        // portably -- no reliance on OS permission bits, which root often
        // bypasses in CI/sandboxes) by pointing the "directory" at a path
        // that already exists as a plain file.
        val blockingFile = dir.resolve("blocked")
        Files.writeString(blockingFile, "i am a file, not a directory")
        val store = storeAt(blockingFile)

        assertThrows<ConfigStorageException> { store.write(sampleConfig()) }
    }

    @Test
    fun `startup indexing tolerates a corrupt file already present, then still enforces uniqueness for the valid ones`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("${UUID.randomUUID()}.yaml"), "not valid yaml: [[[")
        val preExisting = storeAt(dir)
        preExisting.write(sampleConfig(name = "existing"))

        val reopened = storeAt(dir)
        assertTrue(reopened.isNameTaken("existing", excludingId = null))
    }
}
