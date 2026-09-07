package com.flusssync.nlapi.web.dto

import com.flusssync.nlapi.trino.ColumnMeta
import jakarta.validation.constraints.NotBlank

/**
 * `catalog`/`schema` are optional -- see the README's "`/ask`: stateless
 * vs. multi-turn" section. When both are omitted, AskService builds its
 * schema context from every catalog/schema the connected role can see
 * (bounded, see AskProperties), which is slower and more likely to need
 * needs_clarification than a scoped request.
 */
data class AskRequest(
    @field:NotBlank(message = "question must not be blank")
    val question: String,
    val catalog: String? = null,
    val schema: String? = null,
)

data class AskResponse(
    val sql: String?,
    val needsClarification: Boolean,
    val clarificationQuestion: String?,
    val tablesUsed: List<String>,
    val columns: List<ColumnMeta>,
    val rows: List<List<Any?>>,
    val rowCount: Int,
    val truncated: Boolean,
    val durationMs: Long,
)
