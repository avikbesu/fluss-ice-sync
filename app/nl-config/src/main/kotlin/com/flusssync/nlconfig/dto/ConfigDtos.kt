package com.flusssync.nlconfig.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class ColumnDto(
    @field:NotBlank val name: String,
    @field:NotBlank val inferredType: String,
    val description: String? = null,
)

data class DestinationDto(
    @field:NotBlank val catalog: String,
    @field:NotBlank val schema: String,
    @field:NotBlank val table: String,
)

/**
 * `POST /api/v1/configs` body -- upsert semantics: omit [id] to create
 * (the server generates a new UUID), include it to overwrite that exact
 * existing config. An [id] that doesn't match any existing file is a 404,
 * not a create-with-that-id -- ids are always server-generated, so a
 * client only ever has one by having read it back from this service
 * first.
 *
 * Length/count limits ([ConfigStoreProperties.Limits]) aren't expressed as
 * static `@Size` annotations here since they're runtime-configurable --
 * see [com.flusssync.nlconfig.service.ConfigValidator] for where they're
 * actually enforced, producing the same field-level error shape a static
 * annotation failure would.
 */
data class UpsertConfigRequest(
    val id: String? = null,
    @field:NotBlank val name: String,
    @field:NotBlank val source: String,
    @field:Valid val destination: DestinationDto,
    @field:Valid val columns: List<ColumnDto> = emptyList(),
    val businessDescription: String = "",
    val exampleQuestions: List<String> = emptyList(),
)

data class ConfigResponse(
    val id: String,
    val name: String,
    val source: String,
    val destination: DestinationDto,
    val columns: List<ColumnDto>,
    val businessDescription: String,
    val exampleQuestions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ConfigSummaryResponse(
    val id: String,
    val name: String,
    val source: String,
    val destination: DestinationDto,
)

data class ErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
)
