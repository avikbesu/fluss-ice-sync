package com.flino.config.seed

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.flino.config.config.SyncSpecSeedProperties
import com.flino.config.dto.ColumnDto
import com.flino.config.dto.DestinationDto
import com.flino.config.dto.UpsertConfigRequest
import com.flino.config.exception.ConfigException
import com.flino.config.service.ConfigService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Auto-imports a starter config from each SyncSource spec
 * (`config/resources/spec/`) that's actually queryable via Trino
 * (`lakehouse.enabled: true`) and doesn't already have a config under that
 * name -- see [SyncSpecSeedProperties]'s doc for why this exists (the
 * Config tab otherwise starts completely empty after a `make reset`, with
 * no link back to the ingestion sources `app/sync` already knows about).
 *
 * Runs once at startup, after every other bean (including [ConfigService]
 * and its startup name-index build) is ready, matching a plain
 * [ApplicationRunner]'s normal ordering. Idempotent by name: a source
 * already imported (or a user-authored config that happens to share its
 * `metadata.name`) is left alone, never overwritten -- this is deliberately
 * a one-way "create if missing", not a sync, so hand-edits (a filled-in
 * businessDescription, example questions) survive every restart.
 *
 * **Known limitation**: matching is by name only. Renaming an auto-seeded
 * config breaks the link -- the next restart won't find "partner-orders"
 * under its original name anymore and seeds a fresh one. Acceptable for
 * what this is (a one-time bootstrap), not attempting to track seeded-ness
 * with separate metadata.
 */
@Component
class SyncSpecConfigSeeder(
    private val props: SyncSpecSeedProperties,
    private val configService: ConfigService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SyncSpecConfigSeeder::class.java)

    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    override fun run(args: ApplicationArguments) {
        val directory = props.directory
        if (directory == null) {
            log.info("config.sync-specs.directory not set; skipping SyncSource spec import")
            return
        }

        val dir = Paths.get(directory)
        if (!Files.isDirectory(dir)) {
            log.info("SyncSource spec directory {} does not exist; skipping import", dir)
            return
        }

        var created = 0
        var alreadyPresent = 0
        var notTiered = 0
        var unreadable = 0

        Files.newDirectoryStream(dir) { it.extension.equals("yaml", ignoreCase = true) }.use { stream ->
            for (path in stream) {
                when (importOne(path)) {
                    ImportOutcome.CREATED -> created++
                    ImportOutcome.ALREADY_PRESENT -> alreadyPresent++
                    ImportOutcome.NOT_TIERED -> notTiered++
                    ImportOutcome.UNREADABLE -> unreadable++
                }
            }
        }

        log.info(
            "SyncSource spec import from {}: {} created, {} already present, {} not lakehouse-enabled, {} unreadable",
            dir, created, alreadyPresent, notTiered, unreadable,
        )
    }

    private enum class ImportOutcome { CREATED, ALREADY_PRESENT, NOT_TIERED, UNREADABLE }

    private fun importOne(path: Path): ImportOutcome {
        val spec = try {
            yamlMapper.readValue(path.toFile(), SyncSourceSpecFile::class.java)
        } catch (e: IOException) {
            log.warn("Skipping unreadable SyncSource spec {}: {}", path.name, e.message)
            return ImportOutcome.UNREADABLE
        }

        val name = spec.metadata.name
        if (name.isBlank()) {
            log.warn("Skipping SyncSource spec {}: missing metadata.name", path.name)
            return ImportOutcome.UNREADABLE
        }
        if (!spec.spec.destination.lakehouse.enabled) {
            return ImportOutcome.NOT_TIERED
        }
        if (configService.existsByName(name)) {
            return ImportOutcome.ALREADY_PRESENT
        }

        val request = UpsertConfigRequest(
            id = null,
            name = name,
            source = "sync-spec:${path.name}",
            destination = DestinationDto(
                catalog = props.defaultCatalog,
                schema = spec.spec.destination.database,
                table = spec.spec.destination.table,
            ),
            columns = spec.spec.format.columns.map { ColumnDto(name = it.name, inferredType = it.type, description = null) },
            businessDescription = businessDescriptionFor(name, path, spec),
            exampleQuestions = emptyList(),
        )

        return try {
            configService.upsert(request)
            ImportOutcome.CREATED
        } catch (e: ConfigException) {
            // A genuine race (another replica/process created the same name
            // between the existsByName check and this upsert) or a spec
            // whose column/name lengths exceed this deployment's limits --
            // either way, log and move on rather than fail startup over one
            // bad spec.
            log.warn("Could not import SyncSource spec {} as config '{}': {}", path.name, name, e.message)
            ImportOutcome.UNREADABLE
        }
    }

    /**
     * A SyncSource spec has no free-text description field of its own --
     * this folds whatever context it *does* carry (tags, ownership,
     * lakehouse freshness) into a still-generic but genuinely spec-derived
     * sentence, rather than a placeholder that says nothing about this
     * particular source. Any of these can be absent (an untagged/unowned
     * spec), so each clause is only included when present.
     */
    private fun businessDescriptionFor(name: String, path: Path, spec: SyncSourceSpecFile): String {
        val details = buildList {
            if (spec.spec.tag.isNotEmpty()) add("tags: ${spec.spec.tag.joinToString(", ")}")
            val contacts = listOfNotNull(spec.spec.contact.owner) + spec.spec.contact.support
            if (contacts.isNotEmpty()) add("owned by ${contacts.joinToString(", ")}")
            spec.spec.destination.lakehouse.freshness?.let { add("lakehouse freshness $it") }
        }
        val detailsText = if (details.isNotEmpty()) " (${details.joinToString("; ")})" else ""

        return "Auto-imported from the '$name' SyncSource spec (${path.name})$detailsText. " +
            "Edit this description and add example questions to improve NL-to-SQL accuracy."
    }
}
