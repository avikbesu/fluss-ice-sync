package com.flusssync.nlconfig.testsupport

import com.flusssync.nlconfig.model.ConfigColumn
import com.flusssync.nlconfig.model.Destination
import com.flusssync.nlconfig.model.TableConfig
import java.time.Instant
import java.util.UUID

fun sampleConfig(
    id: String = UUID.randomUUID().toString(),
    name: String = "orders_config",
    source: String = "partner_orders_raw.csv",
    destination: Destination = Destination("iceberg", "sales", "orders"),
    columns: List<ConfigColumn> = listOf(
        ConfigColumn("id", "bigint", "primary key"),
        ConfigColumn("amount", "decimal(10,2)", null),
    ),
    businessDescription: String = "Orders placed by partners.",
    exampleQuestions: List<String> = listOf("How many orders were placed last week?"),
    createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt: Instant = createdAt,
): TableConfig = TableConfig(
    id = id,
    name = name,
    source = source,
    destination = destination,
    columns = columns,
    businessDescription = businessDescription,
    exampleQuestions = exampleQuestions,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
