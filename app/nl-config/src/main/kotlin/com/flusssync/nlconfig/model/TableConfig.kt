package com.flusssync.nlconfig.model

import java.time.Instant

/**
 * The full content of one config file -- the semantic/context definition
 * (inferred schema plus business detail) `trino-nl-ui`'s Config tab
 * creates, and `trino-nl-api` fetches by [id] to build `/ask` prompt
 * context. This is both the on-disk YAML shape (see `YamlConfigFileStore`)
 * and the JSON shape returned by `GET /api/v1/configs/{id}` -- one model,
 * serialized through two different Jackson mappers (YAML for the file,
 * the default JSON one for the API), not two parallel classes kept in
 * sync by hand.
 *
 * [id] is always server-generated (a UUID, also the filename) and never
 * derived from [name] or any other user-supplied value -- see
 * `ConfigId.parse` for the one place a caller-supplied id string is
 * validated before it can touch the filesystem.
 */
data class TableConfig(
    val id: String,
    val name: String,
    val source: String,
    val destination: Destination,
    val columns: List<ConfigColumn>,
    val businessDescription: String,
    val exampleQuestions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ConfigColumn(
    val name: String,
    val inferredType: String,
    val description: String? = null,
)

data class Destination(
    val catalog: String,
    val schema: String,
    val table: String,
)

/**
 * The lightweight per-config entry `GET /api/v1/configs` returns -- no
 * `columns`/`businessDescription`/`exampleQuestions`, since that's what
 * keeps the listing endpoint cheap for the UI's carousel (see the build
 * prompt: "don't return full columns/businessDescription payloads here").
 */
data class ConfigSummary(
    val id: String,
    val name: String,
    val source: String,
    val destination: Destination,
)

fun TableConfig.toSummary(): ConfigSummary = ConfigSummary(id, name, source, destination)
