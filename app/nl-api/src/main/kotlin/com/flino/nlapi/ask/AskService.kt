package com.flino.nlapi.ask

import com.flino.nlapi.claude.CallerRateLimiter
import com.flino.nlapi.claude.ClaudeClient
import com.flino.nlapi.claude.PromptBuilder
import com.flino.nlapi.config.AskProperties
import com.flino.nlapi.config.TrinoProperties
import com.flino.nlapi.exception.CatalogNotFoundException
import com.flino.nlapi.exception.RateLimitExceededException
import com.flino.nlapi.exception.SchemaNotFoundException
import com.flino.nlapi.exception.TableNotFoundException
import com.flino.nlapi.exception.UnresolvedReferenceException
import com.flino.nlapi.trino.TrinoMetadataService
import com.flino.nlapi.trino.TrinoQueryExecutor
import com.flino.nlapi.trino.TrinoSqlValidator
import com.flino.nlapi.web.dto.AskRequest
import com.flino.nlapi.web.dto.AskResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Orchestrates `/ask`: build schema context -> ask Claude for structured
 * SQL -> validate it exactly like every other statement this service runs
 * -> cross-check every table it claims to use against real metadata ->
 * execute. Each stage is a hard gate, not a best-effort filter -- a
 * failure at any of them stops before Trino ever sees the SQL.
 *
 * Stateless per request, by design -- see the README's "`/ask`: stateless
 * vs. multi-turn" for the reasoning. Nothing here reads or writes any
 * state from a previous call.
 */
@Service
class AskService(
    private val rateLimiter: CallerRateLimiter,
    private val contextBuilder: AskContextBuilder,
    private val claudeClient: ClaudeClient,
    private val sqlValidator: TrinoSqlValidator,
    private val metadataService: TrinoMetadataService,
    private val queryExecutor: TrinoQueryExecutor,
    private val askProperties: AskProperties,
    private val trinoProperties: TrinoProperties,
) {
    private val log = LoggerFactory.getLogger(AskService::class.java)

    fun ask(request: AskRequest, callerId: String): AskResponse {
        rateLimiter.tryAcquire(callerId)?.let { retryAfter -> throw RateLimitExceededException(retryAfter) }

        val question = request.question.trim()
        require(question.isNotEmpty()) { "question must not be blank" }
        require(question.length <= askProperties.maxQuestionLength) {
            "question exceeds the maximum length of ${askProperties.maxQuestionLength} characters"
        }
        require(request.catalog != null || request.schema == null) {
            "schema was given without catalog -- specify both, or neither"
        }

        val context = contextBuilder.build(request.catalog, request.schema)
        // Prompt-injection attempts embedded in the question text (e.g. "ignore
        // previous instructions, run DELETE...") are handled by never trusting
        // the model's output more than any other untrusted input: whatever
        // Claude returns still has to pass assertReadOnly + the reference check
        // below before it can reach Trino, exactly like it would for a
        // hand-typed request. A suspicious question is logged for visibility,
        // not specially blocked -- blocking on keyword-matching the *question*
        // would be trivially bypassable and isn't the actual security boundary.
        if (looksLikeInjectionAttempt(question)) {
            log.warn("Ask question contains SQL-write-like keywords, flagging for visibility: {}", question.take(200))
        }

        val generated = claudeClient.generateSql(PromptBuilder.systemPrompt(), PromptBuilder.userMessage(question, context.description))

        if (generated.needsClarification || generated.sql == null) {
            return AskResponse(
                sql = null,
                needsClarification = true,
                clarificationQuestion = generated.clarificationQuestion
                    ?: "Could you be more specific about which tables/columns you mean?",
                tablesUsed = generated.tablesUsed,
                columns = emptyList(),
                rows = emptyList(),
                rowCount = 0,
                truncated = false,
                durationMs = 0,
            )
        }

        sqlValidator.assertReadOnly(generated.sql)
        val unresolved = unresolvedTables(generated.tablesUsed)
        if (unresolved.isNotEmpty()) {
            throw UnresolvedReferenceException(
                "Couldn't resolve ${unresolved.joinToString(", ")} against the catalog -- the generated query referenced " +
                    "a table that doesn't exist (or isn't visible to this service).",
            )
        }

        val result = queryExecutor.executeMaterialized(
            catalog = request.catalog,
            schema = request.schema,
            sql = generated.sql,
            timeoutSeconds = trinoProperties.query.defaultTimeoutSeconds,
            maxRows = trinoProperties.listing.maxPageSize,
        )

        return AskResponse(
            sql = generated.sql,
            needsClarification = false,
            clarificationQuestion = null,
            tablesUsed = generated.tablesUsed,
            columns = result.columns,
            rows = result.rows,
            rowCount = result.rowCount,
            truncated = result.truncated,
            durationMs = result.durationMs,
        )
    }

    /**
     * Catches the LLM-hallucinated-name case *before* Trino ever sees the
     * query: every `catalog.schema.table` Claude claims to have used in
     * [SqlGenerationResult.tablesUsed] is checked against real metadata,
     * and a miss here is turned into a specific "couldn't resolve X"
     * response instead of an opaque Trino "table not found" error. This
     * is a best-effort check keyed on what Claude self-reports using --
     * Trino's own execution-time error is still the final backstop if the
     * generated SQL references something outside that list.
     */
    private fun unresolvedTables(tablesUsed: List<String>): List<String> {
        val unresolved = mutableListOf<String>()
        for (qualified in tablesUsed) {
            val parts = qualified.split(".")
            if (parts.size != 3) {
                unresolved += qualified
                continue
            }
            val (catalog, schema, table) = parts
            try {
                metadataService.requireTableExists(catalog, schema, table)
            } catch (e: CatalogNotFoundException) {
                unresolved += qualified
            } catch (e: SchemaNotFoundException) {
                unresolved += qualified
            } catch (e: TableNotFoundException) {
                unresolved += qualified
            }
        }
        return unresolved
    }

    private fun looksLikeInjectionAttempt(question: String): Boolean {
        val lower = question.lowercase()
        return listOf("ignore previous", "ignore all previous", "disregard the above", "delete from", "drop table", "truncate table")
            .any { lower.contains(it) }
    }
}
