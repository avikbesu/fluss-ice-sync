package com.flusssync.nlapi.web

import com.flusssync.nlapi.ask.AskService
import com.flusssync.nlapi.web.dto.AskRequest
import com.flusssync.nlapi.web.dto.AskResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class AskController(private val askService: AskService) {

    /**
     * POST /api/v1/ask -- see AskService for the full pipeline and the
     * README for the stateless-vs-multi-turn decision. Caller identity for
     * rate limiting is `X-Caller-Id` when a client sends one (recommended
     * for any internal caller sharing this service's network path), falling
     * back to the request's remote address so an unidentified caller is
     * still bounded, just coarsely.
     */
    @PostMapping("/api/v1/ask")
    fun ask(
        @Valid @RequestBody request: AskRequest,
        @RequestHeader("X-Caller-Id", required = false) callerIdHeader: String?,
        httpRequest: HttpServletRequest,
    ): AskResponse {
        val callerId = callerIdHeader?.takeIf { it.isNotBlank() } ?: httpRequest.remoteAddr ?: "unknown"
        return askService.ask(request, callerId)
    }
}
