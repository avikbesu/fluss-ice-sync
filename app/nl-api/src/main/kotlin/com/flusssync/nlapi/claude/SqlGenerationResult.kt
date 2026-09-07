package com.flusssync.nlapi.claude

/**
 * Claude's forced structured output for one `/ask` call -- parsed straight
 * from the `generate_sql` tool-use block the Messages API is required to
 * return (see [AnthropicMessagesClient]), never from free text. Exactly one
 * of "needsClarification" or "sql" is the meaningful branch, matching the
 * build prompt's "force structured output... a fixed JSON schema with a
 * `sql` field plus a `needs_clarification` field".
 */
data class SqlGenerationResult(
    val needsClarification: Boolean,
    val clarificationQuestion: String?,
    val sql: String?,
    val tablesUsed: List<String>,
)
