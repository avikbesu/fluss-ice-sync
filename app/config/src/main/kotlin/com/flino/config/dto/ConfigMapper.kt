package com.flino.config.dto

import com.flino.config.model.ConfigColumn
import com.flino.config.model.ConfigSummary
import com.flino.config.model.Destination
import com.flino.config.model.TableConfig

fun Destination.toDto() = DestinationDto(catalog, schema, table)
fun DestinationDto.toDomain() = Destination(catalog, schema, table)

fun ConfigColumn.toDto() = ColumnDto(name, inferredType, description)
fun ColumnDto.toDomain() = ConfigColumn(name, inferredType, description)

fun TableConfig.toResponse() = ConfigResponse(
    id = id,
    name = name,
    source = source,
    destination = destination.toDto(),
    columns = columns.map { it.toDto() },
    businessDescription = businessDescription,
    exampleQuestions = exampleQuestions,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ConfigSummary.toResponse() = ConfigSummaryResponse(id, name, source, destination.toDto())
