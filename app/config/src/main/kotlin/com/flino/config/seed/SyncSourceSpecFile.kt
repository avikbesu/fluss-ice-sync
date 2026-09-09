package com.flino.config.seed

/**
 * The small subset of a SyncSource spec's fields (`config/resources/spec/`,
 * see `app/sync`'s `FlinoConfigLoader`/`SyncSource` model for the full
 * shape) that [SyncSpecConfigSeeder] actually needs. Deliberately its own
 * minimal, tolerant Jackson DTO here rather than a dependency on
 * `app/sync` -- these are two fully independent Gradle modules/deployable
 * services (see settings.gradle), and this service only ever reads a
 * handful of fields, never the full spec (validation rules, watch/archive
 * paths, security roles, etc. are all `app/sync`'s concern, not this
 * one's). Every field defaults so an unrelated/malformed YAML file in the
 * same directory is skipped rather than crashing the seeder.
 */
data class SyncSourceSpecFile(
    val metadata: Metadata = Metadata(),
    val spec: Spec = Spec(),
) {
    data class Metadata(val name: String = "")

    data class Spec(
        /** Free-text tags (e.g. "sales", "csv", "event-stream") -- folded into the seeded businessDescription since a SyncSource spec has no free-text description field of its own. */
        val tag: List<String> = emptyList(),
        val contact: Contact = Contact(),
        val destination: Destination = Destination(),
        val format: Format = Format(),
    )

    data class Contact(val owner: String? = null, val support: List<String> = emptyList())

    data class Destination(
        val database: String = "",
        val table: String = "",
        val lakehouse: Lakehouse = Lakehouse(),
    )

    /**
     * Only a source with `enabled: true` is ever queryable via
     * Trino/Iceberg -- see SyncSpecConfigSeeder. `freshness` (e.g. "30s")
     * is folded into the seeded businessDescription when present.
     */
    data class Lakehouse(val enabled: Boolean = false, val freshness: String? = null)

    data class Format(val columns: List<ColumnDef> = emptyList())

    data class ColumnDef(val name: String = "", val type: String = "")
}
