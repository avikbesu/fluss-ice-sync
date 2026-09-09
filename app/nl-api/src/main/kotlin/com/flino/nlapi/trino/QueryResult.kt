package com.flino.nlapi.trino

data class ColumnMeta(val name: String, val type: String)

data class QueryResult(
    val columns: List<ColumnMeta>,
    val rows: List<List<Any?>>,
    val rowCount: Int,
    val truncated: Boolean,
    val durationMs: Long,
)
