package com.flusssync.nlapi.trino

import com.flusssync.nlapi.exception.CatalogNotFoundException
import com.flusssync.nlapi.exception.CatalogUnavailableException
import com.flusssync.nlapi.exception.MessageSanitizer
import com.flusssync.nlapi.exception.NlApiException
import com.flusssync.nlapi.exception.QueryTimeoutException
import com.flusssync.nlapi.exception.TrinoQueryFailedException
import org.springframework.stereotype.Component
import java.sql.SQLTimeoutException

/**
 * Turns a raw JDBC failure into one of this service's own structured
 * exceptions -- callers (controllers, via GlobalExceptionHandler) never see
 * a `SQLException` message or stack trace directly. Most existence checks
 * happen *before* a query runs (TrinoMetadataService resolves
 * catalog/schema/table against real `DatabaseMetaData` first), so this is
 * mainly a defensive fallback for what Trino itself reports mid-query
 * (a race with a concurrent DROP, a connector that's gone unavailable
 * between the pre-check and execution, etc.) -- see
 * TrinoMetadataService for the primary "not found" path.
 */
@Component
class TrinoExceptionTranslator {

    /** Trino JDBC error messages are the only signal available here -- SQLState/vendor codes vary too much by connector to key off reliably. */
    fun translate(e: Throwable, catalog: String? = null): NlApiException {
        if (e is NlApiException) return e
        if (e is SQLTimeoutException) return QueryTimeoutException("Query exceeded its configured timeout and was cancelled.")

        val message = e.message ?: e.javaClass.simpleName
        val lower = message.lowercase()

        return when {
            catalog != null && lower.contains("catalog") && (lower.contains("not found") || lower.contains("does not exist")) ->
                CatalogNotFoundException(catalog)

            lower.contains("access denied") ->
                TrinoQueryFailedException("Access denied by Trino's own read-only role grants.")

            catalog != null && looksUnavailable(lower) ->
                CatalogUnavailableException(catalog, e)

            looksUnavailable(lower) ->
                TrinoQueryFailedException("The Trino coordinator is temporarily unavailable.", e)

            else -> TrinoQueryFailedException(MessageSanitizer.sanitizeForClient(message), e)
        }
    }

    private fun looksUnavailable(lowerMessage: String): Boolean =
        listOf("connection refused", "connect timed out", "unable to create input stream", "i/o error", "unavailable", "handshake")
            .any { lowerMessage.contains(it) }
}
