package com.flino.nlapi.web

import com.flino.nlapi.exception.CatalogNotFoundException
import com.flino.nlapi.exception.CatalogUnavailableException
import com.flino.nlapi.exception.ClaudeUnavailableException
import com.flino.nlapi.exception.InvalidIdentifierException
import com.flino.nlapi.exception.MessageSanitizer
import com.flino.nlapi.exception.QueryTimeoutException
import com.flino.nlapi.exception.RateLimitExceededException
import com.flino.nlapi.exception.SchemaNotFoundException
import com.flino.nlapi.exception.StatementNotAllowedException
import com.flino.nlapi.exception.TableNotFoundException
import com.flino.nlapi.exception.TrinoQueryFailedException
import com.flino.nlapi.exception.UnresolvedReferenceException
import com.flino.nlapi.web.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns every exception a controller can throw into the one JSON error
 * shape ([ErrorResponse]) this API ever returns -- "structured error
 * response, never a raw JDBC exception or stack trace" from the build
 * prompt applies here as much as it does inside TrinoExceptionTranslator;
 * this is the layer that ensures it holds for every response, not just
 * ones that went through Trino.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(CatalogNotFoundException::class)
    fun catalogNotFound(e: CatalogNotFoundException) =
        respond(HttpStatus.NOT_FOUND, "CATALOG_NOT_FOUND", e.message!!, mapOf("catalog" to e.catalog))

    @ExceptionHandler(SchemaNotFoundException::class)
    fun schemaNotFound(e: SchemaNotFoundException) =
        respond(HttpStatus.NOT_FOUND, "SCHEMA_NOT_FOUND", e.message!!, mapOf("catalog" to e.catalog, "schema" to e.schema))

    @ExceptionHandler(TableNotFoundException::class)
    fun tableNotFound(e: TableNotFoundException) =
        respond(
            HttpStatus.NOT_FOUND,
            "TABLE_NOT_FOUND",
            e.message!!,
            mapOf("catalog" to e.catalog, "schema" to e.schema, "table" to e.table),
        )

    @ExceptionHandler(CatalogUnavailableException::class)
    fun catalogUnavailable(e: CatalogUnavailableException): ResponseEntity<ErrorResponse> {
        log.warn("Catalog '{}' unavailable: {}", e.catalog, e.cause?.message)
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", e.message!!, mapOf("catalog" to e.catalog))
    }

    @ExceptionHandler(InvalidIdentifierException::class)
    fun invalidIdentifier(e: InvalidIdentifierException) = respond(HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER", e.message!!)

    @ExceptionHandler(StatementNotAllowedException::class)
    fun statementNotAllowed(e: StatementNotAllowedException) =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "STATEMENT_NOT_ALLOWED", e.message!!)

    @ExceptionHandler(UnresolvedReferenceException::class)
    fun unresolvedReference(e: UnresolvedReferenceException) =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "UNRESOLVED_REFERENCE", e.message!!)

    @ExceptionHandler(QueryTimeoutException::class)
    fun queryTimeout(e: QueryTimeoutException) = respond(HttpStatus.GATEWAY_TIMEOUT, "QUERY_TIMEOUT", e.message!!)

    @ExceptionHandler(TrinoQueryFailedException::class)
    fun trinoQueryFailed(e: TrinoQueryFailedException): ResponseEntity<ErrorResponse> {
        log.warn("Trino query failed", e)
        // Sanitized again here, independent of TrinoExceptionTranslator's own
        // pass -- a second, cheap, idempotent safety net so "never a raw
        // JDBC exception or stack trace" holds even if some future call site
        // constructs this exception without going through the translator.
        return respond(HttpStatus.BAD_GATEWAY, "UPSTREAM_QUERY_FAILED", MessageSanitizer.sanitizeForClient(e.message!!))
    }

    @ExceptionHandler(ClaudeUnavailableException::class)
    fun claudeUnavailable(e: ClaudeUnavailableException): ResponseEntity<ErrorResponse> {
        log.warn("Claude API unavailable", e)
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", e.message!!)
    }

    @ExceptionHandler(RateLimitExceededException::class)
    fun rateLimited(e: RateLimitExceededException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds.toString())
            .body(ErrorResponse("RATE_LIMITED", "Rate limit exceeded, retry after ${e.retryAfterSeconds}s."))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidRequestBody(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body failed validation.", details)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegalArgument(e: IllegalArgumentException) = respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.message ?: "Invalid request.")

    /** Anything not already mapped -- still never a stack trace to the client, but logged at ERROR since it's an unanticipated failure mode. */
    @ExceptionHandler(Exception::class)
    fun unhandled(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.")
    }

    private fun respond(status: HttpStatus, code: String, message: String, details: Map<String, Any?> = emptyMap()) =
        ResponseEntity.status(status).body(ErrorResponse(code, message, details))
}
