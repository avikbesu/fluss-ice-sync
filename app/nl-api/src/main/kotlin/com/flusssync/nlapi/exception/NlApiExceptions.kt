package com.flusssync.nlapi.exception

/** Base type for every error this service turns into a structured JSON response instead of a raw stack trace. */
sealed class NlApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class InvalidIdentifierException(message: String) : NlApiException(message)

class CatalogNotFoundException(val catalog: String) : NlApiException("Catalog '$catalog' does not exist")

class SchemaNotFoundException(val catalog: String, val schema: String) :
    NlApiException("Schema '$schema' does not exist in catalog '$catalog'")

class TableNotFoundException(val catalog: String, val schema: String, val table: String) :
    NlApiException("Table '$table' does not exist in '$catalog'.'$schema'")

/** The catalog/connector is known to Trino but currently failing (down, misconfigured) -- distinct from not existing at all. */
class CatalogUnavailableException(val catalog: String, cause: Throwable? = null) :
    NlApiException("Catalog '$catalog' is temporarily unavailable", cause)

/** SQL (hand-built internally, or LLM-generated) failed the read-only/allowlist validator -- never reached Trino. */
class StatementNotAllowedException(message: String) : NlApiException(message)

/** A generated statement referenced a table/column that doesn't resolve against real catalog metadata. */
class UnresolvedReferenceException(message: String) : NlApiException(message)

class QueryTimeoutException(message: String) : NlApiException(message)

/** A query reached Trino and failed there (bad predicate, connector error, etc.) -- message is sanitized, no stack trace. */
class TrinoQueryFailedException(message: String, cause: Throwable? = null) : NlApiException(message, cause)

class ClaudeUnavailableException(message: String, cause: Throwable? = null) : NlApiException(message, cause)

class RateLimitExceededException(val retryAfterSeconds: Long) : NlApiException("Rate limit exceeded")
