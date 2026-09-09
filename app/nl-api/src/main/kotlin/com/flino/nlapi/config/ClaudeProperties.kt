package com.flino.nlapi.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Claude API configuration for the `/ask` endpoint. Bound from
 * `nlapi.claude.*`. The API key is never committed to
 * config/apps/nl-api/application.yaml -- src/main/resources/application.yml
 * sources it from the `ANTHROPIC_API_KEY` environment variable via a
 * `${ANTHROPIC_API_KEY:}` placeholder, mirroring app/ui/bff's existing
 * apiKeyEnv convention of keeping secrets out of any file this repo commits.
 */
@Validated
@ConfigurationProperties(prefix = "nlapi.claude")
data class ClaudeProperties(
    /** `/ask` is disabled (503) entirely when false or when apiKey is blank -- see AskService. */
    val enabled: Boolean = true,

    val apiKey: String = "",

    @field:NotBlank
    val baseUrl: String = "https://api.anthropic.com",

    @field:NotBlank
    val model: String = "claude-sonnet-5",

    @field:NotBlank
    val apiVersion: String = "2023-06-01",

    val requestTimeout: Duration = Duration.ofSeconds(20),

    @field:Positive
    val maxOutputTokens: Int = 1024,

    @field:NestedConfigurationProperty
    val retry: Retry = Retry(),

    @field:NestedConfigurationProperty
    val circuitBreaker: CircuitBreaker = CircuitBreaker(),
) {
    data class Retry(
        val maxAttempts: Int = 2,
        val waitDuration: Duration = Duration.ofMillis(500),
    )

    data class CircuitBreaker(
        val failureRateThreshold: Float = 50f,
        val slidingWindowSize: Int = 20,
        val waitDurationInOpenState: Duration = Duration.ofSeconds(30),
        val permittedCallsInHalfOpenState: Int = 3,
        val minimumNumberOfCalls: Int = 10,
    )
}
