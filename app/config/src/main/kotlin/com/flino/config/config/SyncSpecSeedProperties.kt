package com.flino.config.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where to look for SyncSource spec YAML files (`config/resources/spec/`
 * in this repo -- see [com.flino.config.seed.SyncSpecConfigSeeder]) to
 * auto-import as starter configs. `null` (the default) disables seeding
 * entirely -- a plain local run with no spec directory mounted starts with
 * an empty config set exactly like today, matching
 * [ConfigStoreProperties]'s own "missing directory is a valid state, not
 * an error" precedent.
 */
@ConfigurationProperties(prefix = "config.sync-specs")
data class SyncSpecSeedProperties(
    val directory: String? = null,
    /** This repo only ever has one Iceberg catalog in practice (see config/apps/ui/application.yaml's trino.catalog) -- configurable rather than hardcoded purely so a future multi-catalog deployment isn't blocked on a code change. */
    val defaultCatalog: String = "iceberg",
)
