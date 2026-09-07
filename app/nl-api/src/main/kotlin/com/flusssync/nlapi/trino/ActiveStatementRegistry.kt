package com.flusssync.nlapi.trino

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Statement
import java.util.Collections

/**
 * Every currently-executing Trino `Statement` this service knows about --
 * materialized queries and streamed previews alike register themselves
 * here for the one thing a per-call-site reference can't provide on its
 * own: a graceful-shutdown hook (see [QueryExecutorLifecycle]) that can
 * reach and cancel *every* in-flight query at once on SIGTERM, not just
 * the one a single timeout race is watching.
 */
@Component
class ActiveStatementRegistry {
    private val log = LoggerFactory.getLogger(ActiveStatementRegistry::class.java)
    private val statements: MutableSet<Statement> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun register(statement: Statement) {
        statements += statement
    }

    fun unregister(statement: Statement) {
        statements -= statement
    }

    /** Best-effort cancel of every still-registered statement -- called during graceful shutdown. */
    fun cancelAll() {
        val snapshot = statements.toList()
        if (snapshot.isEmpty()) return
        log.info("Cancelling {} in-flight Trino statement(s) for graceful shutdown", snapshot.size)
        for (statement in snapshot) {
            try {
                statement.cancel()
            } catch (e: Exception) {
                log.debug("Failed to cancel a Trino statement during shutdown (best-effort)", e)
            }
        }
    }
}
