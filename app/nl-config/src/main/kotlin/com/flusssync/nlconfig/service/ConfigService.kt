package com.flusssync.nlconfig.service

import com.flusssync.nlconfig.dto.UpsertConfigRequest
import com.flusssync.nlconfig.dto.toDomain
import com.flusssync.nlconfig.exception.DuplicateConfigNameException
import com.flusssync.nlconfig.filestore.ConfigId
import com.flusssync.nlconfig.filestore.YamlConfigFileStore
import com.flusssync.nlconfig.model.ConfigSummary
import com.flusssync.nlconfig.model.TableConfig
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class UpsertResult(val config: TableConfig, val created: Boolean)

/**
 * Business orchestration for the 3 endpoints -- [ConfigController] stays a
 * thin HTTP/DTO translation layer, [YamlConfigFileStore] stays a pure
 * file-I/O layer, and the "what does a valid write actually mean" logic
 * (validation, name uniqueness, timestamps) lives here in between.
 */
@Service
class ConfigService(
    private val store: YamlConfigFileStore,
    private val validator: ConfigValidator,
) {
    /**
     * Guards the whole "check name uniqueness, then write" sequence for
     * every create/update in this process -- deliberately coarse (one lock
     * for the whole store, not per-id) rather than fine-grained, since
     * correctness (no two concurrent requests both succeeding with the
     * same name) matters far more here than write throughput for what's
     * expected to be a low-volume admin/config-authoring workload. See the
     * README for why this does *not* extend across multiple replicas.
     */
    private val writeLock = ReentrantLock()

    fun get(rawId: String): TableConfig = store.read(ConfigId.parse(rawId))

    fun list(): List<ConfigSummary> = store.listSummaries()

    fun upsert(request: UpsertConfigRequest): UpsertResult {
        validator.validate(request)

        return writeLock.withLock {
            if (request.id == null) create(request) else update(request)
        }
    }

    private fun create(request: UpsertConfigRequest): UpsertResult {
        if (store.isNameTaken(request.name, excludingId = null)) {
            throw DuplicateConfigNameException(request.name)
        }

        val now = Instant.now()
        val config = TableConfig(
            id = ConfigId.generate().toString(),
            name = request.name,
            source = request.source,
            destination = request.destination.toDomain(),
            columns = request.columns.map { it.toDomain() },
            businessDescription = request.businessDescription,
            exampleQuestions = request.exampleQuestions,
            createdAt = now,
            updatedAt = now,
        )

        val written = store.write(config)
        // Registered only after a successful write -- a failed write must
        // never reserve a name nothing on disk actually holds.
        store.registerName(written.name, written.id)
        return UpsertResult(written, created = true)
    }

    private fun update(request: UpsertConfigRequest): UpsertResult {
        val id = ConfigId.parse(request.id!!)
        // "Include id to overwrite the existing file" -- an id that doesn't
        // resolve to a real config is a 404, not an implicit create with a
        // client-chosen id (ids are always server-generated).
        val existing = store.read(id)

        if (store.isNameTaken(request.name, excludingId = existing.id)) {
            throw DuplicateConfigNameException(request.name)
        }

        val updated = existing.copy(
            name = request.name,
            source = request.source,
            destination = request.destination.toDomain(),
            columns = request.columns.map { it.toDomain() },
            businessDescription = request.businessDescription,
            exampleQuestions = request.exampleQuestions,
            updatedAt = Instant.now(),
            // createdAt is preserved from the original write, never reset by an update.
        )

        val written = store.write(updated)
        store.renameIndexEntry(existing.name, written.name, written.id)
        return UpsertResult(written, created = false)
    }
}
