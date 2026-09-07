package com.flusssync.nlconfig.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.validation.annotation.Validated

/**
 * Where config files live and the write-time validation limits applied to
 * their content. Bound from `nlconfig.store.*` -- see
 * src/main/resources/application.yml for defaults and
 * config/apps/nl-config/application.yaml (mounted at runtime) for how this
 * repo overrides the directory per environment, following v0/v1/v2's
 * existing per-app YAML-file convention.
 */
@Validated
@ConfigurationProperties(prefix = "nlconfig.store")
data class ConfigStoreProperties(
    /**
     * A local filesystem path -- in this repo's Kubernetes deployment, a
     * mounted volume (see the README's ReadWriteMany note for what that
     * means once this service runs more than one replica).
     */
    @field:NotBlank
    val directory: String = "/data/configs",

    @field:NestedConfigurationProperty
    val limits: Limits = Limits(),
) {
    data class Limits(
        @field:Positive val maxNameLength: Int = 200,
        @field:Positive val maxSourceLength: Int = 500,
        /**
         * This text (and every other free-text field here) is eventually
         * interpolated into an LLM prompt by trino-nl-api -- capping length
         * bounds prompt-injection surface and runaway token cost, though the
         * cap alone is not a substitute for trino-nl-api treating this
         * content as untrusted data rather than instructions (see the
         * README).
         */
        @field:Positive val maxBusinessDescriptionLength: Int = 10_000,
        @field:Positive val maxColumnDescriptionLength: Int = 2_000,
        @field:Positive val maxExampleQuestionLength: Int = 500,
        @field:Positive val maxExampleQuestions: Int = 50,
        /** Caps a "very wide inferred schema" (the build prompt's own example: hundreds of columns). */
        @field:Positive val maxColumns: Int = 500,
        @field:Positive val maxCatalogSchemaTableLength: Int = 200,
    )
}
