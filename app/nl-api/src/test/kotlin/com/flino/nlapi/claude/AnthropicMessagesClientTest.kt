package com.flino.nlapi.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.flino.nlapi.config.ClaudeProperties
import com.flino.nlapi.exception.ClaudeUnavailableException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class AnthropicMessagesClientTest {

    private lateinit var server: WireMockServer
    private lateinit var client: AnthropicMessagesClient
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()

        val props = ClaudeProperties(
            enabled = true,
            apiKey = "test-key",
            baseUrl = server.baseUrl(),
            model = "claude-sonnet-5",
            requestTimeout = Duration.ofSeconds(5),
        )
        val retry = Retry.of("test", RetryConfig.custom<Any>().maxAttempts(2).waitDuration(Duration.ofMillis(10)).retryExceptions(ClaudeTransientException::class.java).build())
        val breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom().minimumNumberOfCalls(1000).build())
        client = AnthropicMessagesClient(props, objectMapper, breaker, retry)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun toolUseResponseBody(input: Map<String, Any?>): String = objectMapper.writeValueAsString(
        mapOf(
            "id" to "msg_1",
            "type" to "message",
            "role" to "assistant",
            "content" to listOf(
                mapOf("type" to "tool_use", "id" to "toolu_1", "name" to "generate_sql", "input" to input),
            ),
            "stop_reason" to "tool_use",
        ),
    )

    @Test
    fun `parses a successful generate_sql tool call`() {
        server.stubFor(
            post(urlEqualTo("/v1/messages")).willReturn(
                aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(
                    toolUseResponseBody(
                        mapOf(
                            "needs_clarification" to false,
                            "sql" to "SELECT * FROM iceberg.sales.orders LIMIT 10",
                            "tables_used" to listOf("iceberg.sales.orders"),
                        ),
                    ),
                ),
            ),
        )

        val result = client.generateSql("system prompt", "user message")

        assertFalse(result.needsClarification)
        assertEquals("SELECT * FROM iceberg.sales.orders LIMIT 10", result.sql)
        assertEquals(listOf("iceberg.sales.orders"), result.tablesUsed)

        server.verify(postRequestedFor(urlEqualTo("/v1/messages")).withHeader("x-api-key", equalTo("test-key")))
    }

    @Test
    fun `parses a needs_clarification response with no sql`() {
        server.stubFor(
            post(urlEqualTo("/v1/messages")).willReturn(
                aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(
                    toolUseResponseBody(mapOf("needs_clarification" to true, "clarification_question" to "Which schema?")),
                ),
            ),
        )

        val result = client.generateSql("system prompt", "user message")

        assertTrue(result.needsClarification)
        assertEquals("Which schema?", result.clarificationQuestion)
        assertEquals(null, result.sql)
    }

    @Test
    fun `retries once on a 500 and succeeds on the next attempt`() {
        server.stubFor(
            post(urlEqualTo("/v1/messages")).inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500).withBody("""{"error":{"message":"internal error"}}"""))
                .willSetStateTo("second"),
        )
        server.stubFor(
            post(urlEqualTo("/v1/messages")).inScenario("retry")
                .whenScenarioStateIs("second")
                .willReturn(
                    aResponse().withStatus(200).withBody(
                        toolUseResponseBody(mapOf("needs_clarification" to false, "sql" to "SELECT 1", "tables_used" to emptyList<String>())),
                    ),
                ),
        )

        val result = client.generateSql("system prompt", "user message")

        assertEquals("SELECT 1", result.sql)
        server.verify(2, postRequestedFor(urlEqualTo("/v1/messages")))
    }

    @Test
    fun `does not retry a non-retryable 4xx and fails fast`() {
        server.stubFor(
            post(urlEqualTo("/v1/messages")).willReturn(
                aResponse().withStatus(401).withBody("""{"error":{"message":"invalid api key"}}"""),
            ),
        )

        assertThrows<ClaudeUnavailableException> { client.generateSql("system prompt", "user message") }

        server.verify(1, postRequestedFor(urlEqualTo("/v1/messages")))
    }

    @Test
    fun `fails clearly when no tool_use block is present`() {
        server.stubFor(
            post(urlEqualTo("/v1/messages")).willReturn(
                aResponse().withStatus(200).withBody("""{"id":"msg_1","type":"message","content":[{"type":"text","text":"I refuse."}]}"""),
            ),
        )

        assertThrows<ClaudeUnavailableException> { client.generateSql("system prompt", "user message") }
    }
}
