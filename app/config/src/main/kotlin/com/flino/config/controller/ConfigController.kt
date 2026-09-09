package com.flino.config.controller

import com.flino.config.dto.ConfigResponse
import com.flino.config.dto.ConfigSummaryResponse
import com.flino.config.dto.UpsertConfigRequest
import com.flino.config.dto.toResponse
import com.flino.config.service.ConfigService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
class ConfigController(private val configService: ConfigService) {

    /** GET /api/v1/configs/{id} -- full config content. 400 for a malformed id, 404 for a well-formed one with no matching file. */
    @GetMapping("/api/v1/configs/{id}")
    fun get(@PathVariable id: String): ConfigResponse = configService.get(id).toResponse()

    /**
     * GET /api/v1/configs -- lightweight summaries (id/name/source/destination
     * only) for every readable config; an unreadable file is skipped with a
     * logged warning rather than failing the whole listing. An empty or
     * not-yet-created config directory returns an empty list, not an error.
     */
    @GetMapping("/api/v1/configs")
    fun list(): List<ConfigSummaryResponse> = configService.list().map { it.toResponse() }

    /**
     * POST /api/v1/configs -- upsert. Omit `id` to create (201, with a
     * Location header for the new resource); include it to overwrite that
     * exact existing config (200). A duplicate `name` is a 409, never a
     * silent overwrite.
     */
    @PostMapping("/api/v1/configs")
    fun upsert(@Valid @RequestBody request: UpsertConfigRequest): ResponseEntity<ConfigResponse> {
        val result = configService.upsert(request)
        val body = result.config.toResponse()
        return if (result.created) {
            val location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(body.id).toUri()
            ResponseEntity.created(location).body(body)
        } else {
            ResponseEntity.status(HttpStatus.OK).body(body)
        }
    }
}
