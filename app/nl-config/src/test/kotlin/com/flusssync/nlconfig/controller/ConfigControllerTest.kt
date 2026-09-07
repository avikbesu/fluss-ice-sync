package com.flusssync.nlconfig.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.flusssync.nlconfig.exception.ConfigNotFoundException
import com.flusssync.nlconfig.exception.DuplicateConfigNameException
import com.flusssync.nlconfig.exception.InvalidConfigIdException
import com.flusssync.nlconfig.service.ConfigService
import com.flusssync.nlconfig.model.toSummary
import com.flusssync.nlconfig.service.UpsertResult
import com.flusssync.nlconfig.testsupport.sampleConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ConfigControllerTest {

    private val configService = mockk<ConfigService>()
    private lateinit var mockMvc: MockMvc
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(ConfigController(configService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    private fun validBody() = """
        {
          "name": "orders_config",
          "source": "orders.csv",
          "destination": {"catalog":"iceberg","schema":"sales","table":"orders"},
          "columns": [{"name":"id","inferredType":"bigint"}],
          "businessDescription": "desc",
          "exampleQuestions": []
        }
    """.trimIndent()

    @Test
    fun `create returns 201 with a Location header`() {
        val created = sampleConfig()
        every { configService.upsert(any()) } returns UpsertResult(created, created = true)

        mockMvc.perform(post("/api/v1/configs").contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isCreated)
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").value(created.id))
    }

    @Test
    fun `update via upsert returns 200, not 201`() {
        val updated = sampleConfig()
        every { configService.upsert(any()) } returns UpsertResult(updated, created = false)

        mockMvc.perform(post("/api/v1/configs").contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isOk)
    }

    @Test
    fun `a blank required field is a structured 400, not a 500`() {
        val invalidBody = """{"name":"","source":"","destination":{"catalog":"","schema":"","table":""}}"""

        mockMvc.perform(post("/api/v1/configs").contentType(MediaType.APPLICATION_JSON).content(invalidBody))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `duplicate name maps to 409`() {
        every { configService.upsert(any()) } throws DuplicateConfigNameException("orders_config")

        mockMvc.perform(post("/api/v1/configs").contentType(MediaType.APPLICATION_JSON).content(validBody()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFIG_NAME_CONFLICT"))
    }

    @Test
    fun `get for an unknown id maps to 404`() {
        every { configService.get("123e4567-e89b-12d3-a456-426614174000") } throws
            ConfigNotFoundException("123e4567-e89b-12d3-a456-426614174000")

        mockMvc.perform(get("/api/v1/configs/123e4567-e89b-12d3-a456-426614174000"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONFIG_NOT_FOUND"))
    }

    @Test
    fun `get for a path-traversal-shaped id maps to 400, never touches the filesystem`() {
        every { configService.get("..%2F..%2Fetc%2Fpasswd") } throws InvalidConfigIdException("../../etc/passwd")

        mockMvc.perform(get("/api/v1/configs/..%2F..%2Fetc%2Fpasswd"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_CONFIG_ID"))
    }

    @Test
    fun `list returns a plain JSON array of summaries`() {
        every { configService.list() } returns listOf(sampleConfig().toSummary())

        mockMvc.perform(get("/api/v1/configs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("orders_config"))
    }
}
