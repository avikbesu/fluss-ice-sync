package com.flusssync.nlconfig.filestore

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.flusssync.nlconfig.config.ConfigStoreProperties
import com.flusssync.nlconfig.exception.ConfigNotFoundException
import com.flusssync.nlconfig.exception.ConfigStorageException
import com.flusssync.nlconfig.exception.CorruptConfigFileException
import com.flusssync.nlconfig.model.ConfigSummary
import com.flusssync.nlconfig.model.TableConfig
import com.flusssync.nlconfig.model.toSummary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Every config is one YAML file on a local filesystem/volume, named
 * `<id>.yaml` -- see [ConfigId] for why the filename is always derived
 * from a parsed, canonical [UUID], never a raw path-parameter string.
 *
 * Human-inspectable on disk (matches `trino-nl-ui`'s YAML-view feature)
 * and swappable to JSON serialization at the API boundary independently
 * (see the `dto` package) -- this class is the *only* place that knows
 * the on-disk format is YAML at all.
 */
@Component
class YamlConfigFileStore(props: ConfigStoreProperties) {

    private val log = LoggerFactory.getLogger(YamlConfigFileStore::class.java)
    private val directory: Path = Paths.get(props.directory)

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val nameIndex = NameIndex()

    fun isNameTaken(name: String, excludingId: String?): Boolean = nameIndex.isTaken(name, excludingId)
    fun registerName(name: String, id: String) = nameIndex.register(name, id)
    fun renameIndexEntry(oldName: String?, newName: String, id: String) = nameIndex.rename(oldName, newName, id)

    init {
        // Never fails startup on a filesystem problem -- an empty/missing
        // directory is a valid, expected state (the build prompt's own
        // "config directory doesn't exist yet -> valid empty list, not an
        // error"), and a genuinely unwritable volume is surfaced by
        // ConfigVolumeHealthIndicator and by write() itself, not a crash
        // loop at boot.
        try {
            if (Files.isDirectory(directory)) {
                buildStartupIndex()
            } else {
                log.info("Config directory {} does not exist yet; starting with an empty config set", directory)
            }
        } catch (e: Exception) {
            log.error("Failed to build the startup name index from {}; continuing with an empty index", directory, e)
        }
    }

    private fun buildStartupIndex() {
        var indexed = 0
        var skipped = 0
        forEachConfigFile { path ->
            readFile(path)?.let { config ->
                nameIndex.register(config.name, config.id)
                indexed++
            } ?: skipped++
        }
        log.info("Indexed {} config(s) from {} ({} unreadable file(s) skipped)", indexed, directory, skipped)
    }

    fun exists(id: UUID): Boolean = Files.isRegularFile(pathFor(id))

    fun read(id: UUID): TableConfig {
        val path = pathFor(id)
        if (!Files.isRegularFile(path)) {
            throw ConfigNotFoundException(id.toString())
        }
        return try {
            yamlMapper.readValue(path.toFile(), TableConfig::class.java)
        } catch (e: IOException) {
            log.error("Config file {} exists but could not be parsed", path, e)
            throw CorruptConfigFileException(id.toString(), e)
        }
    }

    /** Corrupt/partially-written files are skipped with a logged warning, not a failed listing -- see the build prompt's edge case for exactly this. */
    fun listSummaries(): List<ConfigSummary> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        val summaries = mutableListOf<ConfigSummary>()
        forEachConfigFile { path ->
            readFile(path)?.let { summaries += it.toSummary() }
        }
        return summaries.sortedBy { it.name.lowercase() }
    }

    /**
     * Atomic write: serialize to a temp file *in the same directory* (so
     * the subsequent move is same-filesystem, required for atomicity),
     * then [Files.move] with `ATOMIC_MOVE` over the real target. A crash,
     * or a concurrent [read]/[listSummaries], can only ever observe the
     * old complete file or the new complete file -- never a partial one,
     * since the target filename is never opened for writing directly.
     */
    fun write(config: TableConfig): TableConfig {
        try {
            Files.createDirectories(directory)
        } catch (e: IOException) {
            throw ConfigStorageException("Config directory $directory could not be created (volume not writable?)", e)
        }

        val targetPath = pathFor(UUID.fromString(config.id))
        // Suffix is deliberately NOT ".yaml" -- forEachConfigFile filters by
        // that extension for its directory scan, and an in-progress temp
        // file must never be picked up mid-write as if it were a real,
        // complete config entry.
        val tempFile = try {
            Files.createTempFile(directory, ".tmp-${config.id}-", ".tmp")
        } catch (e: IOException) {
            throw ConfigStorageException("Could not create a temp file in $directory (disk full or volume not writable?)", e)
        }

        try {
            yamlMapper.writeValue(tempFile.toFile(), config)
            Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.deleteIfExists(tempFile)
            throw ConfigStorageException(
                "The config volume at $directory does not support atomic rename -- writes cannot be made crash-safe on this filesystem.",
                e,
            )
        } catch (e: IOException) {
            Files.deleteIfExists(tempFile)
            throw ConfigStorageException("Failed to write config '${config.id}' (disk full or volume not writable?)", e)
        } finally {
            // No-op if the move above already succeeded (nothing left at tempFile).
            Files.deleteIfExists(tempFile)
        }

        return config
    }

    private fun pathFor(id: UUID): Path = directory.resolve(ConfigId.filename(id))

    private fun forEachConfigFile(action: (Path) -> Unit) {
        Files.newDirectoryStream(directory) { it.extension.equals("yaml", ignoreCase = true) }.use { stream ->
            for (path in stream) action(path)
        }
    }

    /** Null (with a logged warning), never a thrown exception -- the caller decides what "unreadable" means for its context (skip vs. 500). */
    private fun readFile(path: Path): TableConfig? = try {
        yamlMapper.readValue(path.toFile(), TableConfig::class.java)
    } catch (e: IOException) {
        log.warn("Skipping unreadable config file {}: {}", path.name, e.message)
        null
    }
}
