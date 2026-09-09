package com.flino.nlapi.config

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.validation.annotation.Validated

/**
 * Behavior of the `/ask` endpoint itself, independent of the Claude API
 * client (ClaudeProperties) or the Trino connection (TrinoProperties).
 *
 * **Stateless by default, and this module does not implement multi-turn
 * context** (a follow-up question like "and that table?" referring to a
 * prior /ask call) -- each request is answered using only its own
 * `question`/`catalog`/`schema` fields plus catalog metadata fetched fresh
 * from Trino. See the README's "`/ask`: stateless vs. multi-turn" section
 * for the reasoning.
 */
@Validated
@ConfigurationProperties(prefix = "nlapi.ask")
data class AskProperties(
    @field:Positive
    val maxQuestionLength: Int = 2000,

    /** Bounds how much schema metadata is stuffed into the Claude prompt -- see AskContextBuilder. */
    @field:Positive
    val maxContextTables: Int = 50,

    @field:Positive
    val maxContextColumnsPerTable: Int = 100,

    @field:NestedConfigurationProperty
    val rateLimit: RateLimit = RateLimit(),
) {
    data class RateLimit(
        val enabled: Boolean = true,
        /** Sustained request rate per caller (see CallerRateLimiter for how a caller is identified). */
        @field:Positive
        val requestsPerMinute: Int = 20,
        /** Extra burst capacity on top of the steady per-minute rate. */
        @field:Positive
        val burst: Int = 5,
    )
}
