package com.flino.nlapi.web

import com.flino.nlapi.exception.CatalogNotFoundException
import com.flino.nlapi.exception.CatalogUnavailableException
import com.flino.nlapi.exception.QueryTimeoutException
import com.flino.nlapi.exception.RateLimitExceededException
import com.flino.nlapi.exception.StatementNotAllowedException
import com.flino.nlapi.exception.TrinoQueryFailedException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A raw JDBC exception message or stack trace must never reach an HTTP client -- see GlobalExceptionHandler's class doc. */
class GlobalExceptionHandlerTest {

    @RestController
    class ThrowingController {
        @GetMapping("/throw")
        fun throwIt(@RequestParam type: String): String {
            throw when (type) {
                "catalog" -> CatalogNotFoundException("nope")
                "unavailable" -> CatalogUnavailableException("iceberg", RuntimeException("connection refused: coordinator down"))
                "statement" -> StatementNotAllowedException("Only SELECT is supported")
                "timeout" -> QueryTimeoutException("Query exceeded 30s")
                "ratelimit" -> RateLimitExceededException(42)
                "trino" -> TrinoQueryFailedException(
                    "line 1:1: mismatched input\n\tat io.trino.internal.SomeParser.blah(SomeParser.java:123)\n\tat java.base/...",
                )
                else -> RuntimeException("java.lang.RuntimeException: leaked internal detail at com.example.Secret.method")
            }
        }
    }

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ThrowingController()).setControllerAdvice(GlobalExceptionHandler()).build()
    }

    @Test
    fun `catalog not found maps to 404 with a structured body`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "catalog"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CATALOG_NOT_FOUND"))
    }

    @Test
    fun `catalog unavailable maps to 503, distinct from not-found`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "unavailable"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"))
    }

    @Test
    fun `a rejected statement maps to 422`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "statement"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("STATEMENT_NOT_ALLOWED"))
    }

    @Test
    fun `a query timeout maps to 504`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "timeout"))
            .andExpect(status().isGatewayTimeout)
    }

    @Test
    fun `rate limiting maps to 429 with a Retry-After header`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "ratelimit"))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
    }

    @Test
    fun `a Trino failure is sanitized -- no stack trace or multi-line internals reach the client`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "trino"))
            .andExpect(status().isBadGateway)
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SomeParser.java"))))
    }

    @Test
    fun `an unanticipated exception still returns a structured 500, never its raw message`() {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/throw").param("type", "other"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("com.example.Secret"))))
    }
}
