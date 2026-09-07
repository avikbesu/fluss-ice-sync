package com.flusssync.nlapi.trino

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs on context shutdown (SIGTERM), after Spring Boot's own graceful web
 * shutdown (`server.shutdown: graceful`) has already stopped accepting new
 * requests and waited out `spring.lifecycle.timeout-per-shutdown-phase` for
 * in-flight ones -- so by the time this runs, only genuinely stuck queries
 * should remain. Those get a short additional grace period, then an
 * explicit cancel of everything still running plus a hard executor
 * shutdown, so a slow Trino query can't hang process exit indefinitely.
 */
@Component
class QueryExecutorLifecycle(
    private val executor: ExecutorService,
    private val activeStatements: ActiveStatementRegistry,
) {
    private val log = LoggerFactory.getLogger(QueryExecutorLifecycle::class.java)

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        val finishedCleanly = executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
        if (!finishedCleanly) {
            log.warn("Trino query executor did not drain within {}s; cancelling in-flight statements", SHUTDOWN_GRACE_SECONDS)
            activeStatements.cancelAll()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS = 5L
    }
}
