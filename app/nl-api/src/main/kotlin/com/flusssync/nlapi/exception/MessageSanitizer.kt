package com.flusssync.nlapi.exception

/**
 * First line only, length-capped -- the one place a message derived from a
 * raw Trino/JDBC failure is trimmed down before it can reach an HTTP
 * client, so a multi-line Trino error (which can echo back query text or a
 * server-side stack trace) never does. Applied both where
 * [TrinoQueryFailedException] is constructed (TrinoExceptionTranslator) and
 * again at [com.flusssync.nlapi.web.GlobalExceptionHandler] as a second,
 * independent safety net -- idempotent, so sanitizing an already-clean
 * message is a no-op.
 */
object MessageSanitizer {
    private const val MAX_LENGTH = 300

    fun sanitizeForClient(message: String): String =
        message.lineSequence().first().take(MAX_LENGTH).let { if (it.length == MAX_LENGTH) "$it..." else it }
}
