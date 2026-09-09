package com.flino.nlapi.trino

import com.flino.nlapi.config.TrinoProperties
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Trino connection pool. Deliberately **not** pinned to one
 * catalog/schema in the JDBC URL -- this service browses/queries across
 * whatever catalogs the connected role can see (see TrinoQueryExecutor,
 * which calls `Connection.setCatalog`/`setSchema` per borrowed connection
 * instead), unlike app/ui/bff's per-schema-role design, which only ever
 * talks to a single fixed `iceberg` catalog.
 *
 * `isReadOnly(true)` is one leg of the build prompt's defense-in-depth
 * requirement (c): every connection this pool hands out asks the JDBC
 * driver to mark the session read-only, which the Iceberg connector
 * honors, on top of (a) TrinoSqlValidator's allowlist and (b) the
 * dedicated `nl-api-read-role` Trino user (see TrinoProperties/rules.json)
 * that only ever holds SELECT grants.
 */
@Configuration
class TrinoDataSourceConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(HikariDataSource::class)
    fun trinoDataSource(props: TrinoProperties): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = props.jdbcUrl
            driverClassName = "io.trino.jdbc.TrinoDriver"
            username = props.user
            props.password?.let { password = it }
            isReadOnly = true
            poolName = "trino-nl-api"
            maximumPoolSize = props.pool.maximumPoolSize
            minimumIdle = props.pool.minimumIdle
            connectionTimeout = props.pool.connectionTimeout.toMillis()
            validationTimeout = props.pool.validationTimeout.toMillis()
            maxLifetime = props.pool.maxLifetime.toMillis()
            keepaliveTime = props.pool.keepaliveTime.toMillis()
            addDataSourceProperty("SSL", props.ssl.toString())
            addDataSourceProperty("source", props.applicationName)
            addDataSourceProperty("applicationNamePrefix", props.applicationName)
        }
        return HikariDataSource(config)
    }

    /**
     * A bounded pool JDBC work runs on, separate from Tomcat's own request
     * threads -- sized independently of HTTP concurrency (see
     * TrinoProperties.query.executorPoolSize) so a burst of slow Trino
     * queries queues instead of exhausting Hikari's pool from every request
     * thread simultaneously, and gives TrinoQueryExecutor a `Future` to
     * enforce a timeout against and a place to hook graceful shutdown (see
     * [QueryExecutorLifecycle]).
     */
    @Bean(destroyMethod = "")
    fun trinoQueryExecutorService(props: TrinoProperties): ExecutorService {
        val counter = AtomicInteger(1)
        val threadFactory = ThreadFactory { r ->
            Thread(r, "trino-query-${counter.getAndIncrement()}").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(
            props.query.executorPoolSize,
            props.query.executorPoolSize,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            threadFactory,
        )
    }
}
