package com.flusssync.nlapi.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Connection, pool, and safety-limit configuration for the Trino JDBC client.
 * Bound from `nlapi.trino.*` -- see src/main/resources/application.yml for
 * defaults and config/apps/nl-api/application.yaml (mounted at runtime) for
 * how this repo overrides it per environment, following v0/v1's existing
 * per-app YAML-file convention.
 */
@Validated
@ConfigurationProperties(prefix = "nlapi.trino")
data class TrinoProperties(
    /** e.g. jdbc:trino://trino-coordinator:8080 -- no catalog/schema baked in; those are set per-connection. */
    @field:NotBlank
    val jdbcUrl: String = "jdbc:trino://localhost:8080",

    /**
     * The dedicated read-only Trino user/role this service authenticates as
     * (see config/trino/etc/access-control/rules.json's "nl-api-read-role" --
     * the defense-in-depth requirement (b) from the build prompt: Trino-side
     * GRANTs restricted to SELECT, independent of this service's own SQL
     * validator).
     */
    @field:NotBlank
    val user: String = "nl-api-read-role",

    /** Optional -- most Compose-local Trino deployments (like this repo's) authenticate by username alone. */
    val password: String? = null,

    val ssl: Boolean = false,

    /** Reported to Trino as ApplicationName, visible in its query log / web UI. */
    val applicationName: String = "fluss-ice-sync-nl-api",

    @field:NestedConfigurationProperty
    val pool: Pool = Pool(),

    @field:NestedConfigurationProperty
    val query: Query = Query(),

    @field:NestedConfigurationProperty
    val preview: Preview = Preview(),

    @field:NestedConfigurationProperty
    val listing: Listing = Listing(),

    @field:NestedConfigurationProperty
    val pseudoTables: PseudoTables = PseudoTables(),
) {
    init {
        require(query.defaultTimeoutSeconds <= query.maxTimeoutSeconds) {
            "nlapi.trino.query.default-timeout-seconds must not exceed max-timeout-seconds"
        }
        require(preview.defaultRowLimit <= preview.maxRowLimit) {
            "nlapi.trino.preview.default-row-limit must not exceed max-row-limit"
        }
    }

    data class Pool(
        @field:Positive val maximumPoolSize: Int = 10,
        @field:Min(0) val minimumIdle: Int = 2,
        val connectionTimeout: Duration = Duration.ofSeconds(10),
        val validationTimeout: Duration = Duration.ofSeconds(5),
        val maxLifetime: Duration = Duration.ofMinutes(30),
        val keepaliveTime: Duration = Duration.ofMinutes(2),
    )

    data class Query(
        /** Applied via JDBC Statement.setQueryTimeout when a request doesn't ask for a shorter one. */
        @field:Positive val defaultTimeoutSeconds: Int = 30,
        /** Hard ceiling -- no request, including /ask, can push a query's server-side timeout past this. */
        @field:Positive val maxTimeoutSeconds: Int = 120,
        /** Bounded worker pool executing JDBC calls, sized for internal/low-concurrency use -- see README. */
        @field:Positive val executorPoolSize: Int = 16,
    )

    data class Preview(
        @field:Positive val defaultRowLimit: Int = 100,
        /** Server-side LIMIT enforced regardless of the client-requested value -- never bypassable via `limit`. */
        @field:Positive val maxRowLimit: Int = 1000,
        /** Caps the serialized preview response even if maxRowLimit rows would otherwise exceed it. */
        @field:Positive val maxResponseBytes: Long = 5_000_000,
    )

    data class Listing(
        @field:Positive val defaultPageSize: Int = 200,
        @field:Positive val maxPageSize: Int = 1000,
    )

    /**
     * Iceberg exposes per-table metadata as pseudo-tables reachable via
     * `catalog.schema."table$snapshots"` etc. -- not real tables, so they
     * never appear in SHOW TABLES / this API's table-listing endpoint, but
     * describe/ddl/preview accept them explicitly against a table that's
     * confirmed to exist, restricted to this fixed suffix allowlist. This is
     * the one consistent policy for them across every endpoint -- see README.
     */
    data class PseudoTables(
        val enabled: Boolean = true,
        val allowedSuffixes: Set<String> = setOf(
            "snapshots", "history", "partitions", "manifests", "files",
            "refs", "properties", "entries", "metadata_log_entries",
        ),
    )
}
