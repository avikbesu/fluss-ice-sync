package com.flusssync.nlapi.claude

import com.flusssync.nlapi.config.AskProperties
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerRateLimiterTest {

    @Test
    fun `allows requests up to the configured burst capacity, then denies`() {
        val props = AskProperties(rateLimit = AskProperties.RateLimit(enabled = true, requestsPerMinute = 60, burst = 2))
        val limiter = CallerRateLimiter(props)
        val capacity = props.rateLimit.requestsPerMinute + props.rateLimit.burst

        repeat(capacity) { i ->
            assertNull(limiter.tryAcquire("caller-a"), "call #$i should have been allowed")
        }
        val retryAfter = limiter.tryAcquire("caller-a")
        assertNotNull(retryAfter, "call beyond capacity should have been denied")
        assertTrue(retryAfter!! >= 1)
    }

    @Test
    fun `tracks callers independently`() {
        val props = AskProperties(rateLimit = AskProperties.RateLimit(enabled = true, requestsPerMinute = 60, burst = 0))
        val limiter = CallerRateLimiter(props)

        assertNull(limiter.tryAcquire("caller-a"))
        assertNull(limiter.tryAcquire("caller-b"), "a different caller must not share caller-a's bucket")
    }

    @Test
    fun `never denies when disabled`() {
        val props = AskProperties(rateLimit = AskProperties.RateLimit(enabled = false, requestsPerMinute = 1, burst = 0))
        val limiter = CallerRateLimiter(props)

        repeat(50) { assertNull(limiter.tryAcquire("caller-a")) }
    }
}
