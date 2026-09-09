package com.flino.nlapi.claude

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.flino.nlapi.config.ClaudeProperties
import com.flino.nlapi.exception.ClaudeUnavailableException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.retry.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.function.Supplier

/**
 * Calls the Claude Messages API (`POST /v1/messages`) with a single tool
 * (`generate_sql`) and `tool_choice` forcing that exact tool -- Claude's
 * documented mechanism for guaranteeing a structured, schema-conformant
 * response instead of free text this service would otherwise have to
 * regex/fence-strip out of a prose answer (see app/ui/bff/src/routes/chat.ts's
 * `extractSql` for the fragile version of that this module deliberately
 * avoids).
 *
 * Uses the JDK's built-in `java.net.http.HttpClient` rather than the
 * Anthropic Java SDK or a third-party HTTP library -- the build prompt
 * allows either "the Anthropic Java SDK, or a plain HTTP client", and a
 * plain client keeps this module's dependency footprint small, makes the
 * exact wire format (and therefore what WireMock needs to stub in tests)
 * fully explicit, and needs nothing beyond Jackson (already required for
 * the REST layer) to build/parse it.
 */
@Component
class AnthropicMessagesClient(
    private val props: ClaudeProperties,
    private val objectMapper: ObjectMapper,
    private val circuitBreaker: CircuitBreaker,
    private val retry: Retry,
) : ClaudeClient {

    private val log = LoggerFactory.getLogger(AnthropicMessagesClient::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()

    override fun generateSql(systemPrompt: String, userMessage: String): SqlGenerationResult {
        if (!props.enabled || props.apiKey.isBlank()) {
            throw ClaudeUnavailableException("The /ask endpoint is disabled: Claude is not configured (nlapi.claude.enabled=false or no API key).")
        }

        val supplier = Supplier { callOnce(systemPrompt, userMessage) }
        val withRetry = Retry.decorateSupplier(retry, supplier)
        val withCircuitBreaker = CircuitBreaker.decorateSupplier(circuitBreaker, withRetry)

        return try {
            withCircuitBreaker.get()
        } catch (e: CallNotPermittedException) {
            throw ClaudeUnavailableException("Claude API is temporarily unavailable (circuit breaker open after repeated failures).", e)
        } catch (e: ClaudeTransientException) {
            throw ClaudeUnavailableException("Claude API call failed after retrying: ${e.message}", e)
        } catch (e: IOException) {
            throw ClaudeUnavailableException("Claude API call failed: ${e.message}", e)
        }
    }

    private fun callOnce(systemPrompt: String, userMessage: String): SqlGenerationResult {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${props.baseUrl.trimEnd('/')}/v1/messages"))
            .timeout(props.requestTimeout)
            .header("content-type", "application/json")
            .header("x-api-key", props.apiKey)
            .header("anthropic-version", props.apiVersion)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildRequestBody(systemPrompt, userMessage))))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw ClaudeTransientException("Claude API request timed out after ${props.requestTimeout}", e)
        } catch (e: IOException) {
            throw ClaudeTransientException("Network error calling Claude API: ${e.message}", e)
        }

        return when {
            response.statusCode() == 200 -> parseSuccess(response.body())
            response.statusCode() == 429 || response.statusCode() >= 500 ->
                throw ClaudeTransientException("Claude API returned HTTP ${response.statusCode()}: ${extractErrorMessage(response.body())}")
            else ->
                throw ClaudeUnavailableException("Claude API returned HTTP ${response.statusCode()}: ${extractErrorMessage(response.body())}")
        }
    }

    private fun buildRequestBody(systemPrompt: String, userMessage: String): Map<String, Any> = mapOf(
        "model" to props.model,
        "max_tokens" to props.maxOutputTokens,
        "system" to systemPrompt,
        "messages" to listOf(mapOf("role" to "user", "content" to userMessage)),
        "tools" to listOf(GENERATE_SQL_TOOL),
        "tool_choice" to mapOf("type" to "tool", "name" to "generate_sql"),
    )

    private fun parseSuccess(body: String): SqlGenerationResult {
        val root = objectMapper.readTree(body)
        val toolUse = root.path("content").firstOrNull { it.path("type").asText() == "tool_use" && it.path("name").asText() == "generate_sql" }
            ?: throw ClaudeUnavailableException("Claude did not return the expected generate_sql tool call.")

        val input = toolUse.path("input")
        val needsClarification = input.path("needs_clarification").asBoolean(false)
        val sql = input.path("sql").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        val clarificationQuestion = input.path("clarification_question").takeIf { it.isTextual }?.asText()
        val tablesUsed = input.path("tables_used").takeIf { it.isArray }?.map { it.asText() } ?: emptyList()

        if (!needsClarification && sql == null) {
            log.warn("Claude's generate_sql call set needs_clarification=false but returned no sql: {}", input)
            throw ClaudeUnavailableException("Claude returned an incomplete response (no SQL and no clarification request).")
        }

        return SqlGenerationResult(needsClarification, clarificationQuestion, sql, tablesUsed)
    }

    private fun extractErrorMessage(body: String): String = try {
        objectMapper.readTree(body).path("error").path("message").asText().ifBlank { body.take(200) }
    } catch (e: Exception) {
        body.take(200)
    }

    private fun JsonNode.firstOrNull(predicate: (JsonNode) -> Boolean): JsonNode? {
        for (node in this) if (predicate(node)) return node
        return null
    }

    companion object {
        /**
         * The one fixed JSON schema every `/ask` call forces Claude's answer
         * into -- `needs_clarification` is required so a response can never
         * omit the "I don't know" branch; `sql` is optional precisely
         * because it's absent on that branch.
         */
        private val GENERATE_SQL_TOOL: Map<String, Any> = mapOf(
            "name" to "generate_sql",
            "description" to "Return the generated read-only Trino SQL for the question, or request clarification if it can't be answered as given.",
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "needs_clarification" to mapOf(
                        "type" to "boolean",
                        "description" to "true if the question is ambiguous, out of scope, or unanswerable with the listed schema.",
                    ),
                    "clarification_question" to mapOf(
                        "type" to "string",
                        "description" to "A specific question to ask the user, required when needs_clarification is true.",
                    ),
                    "sql" to mapOf(
                        "type" to "string",
                        "description" to "A single read-only Trino SELECT statement, required when needs_clarification is false.",
                    ),
                    "tables_used" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "Fully-qualified catalog.schema.table names referenced by sql.",
                    ),
                ),
                "required" to listOf("needs_clarification"),
            ),
        )
    }
}
