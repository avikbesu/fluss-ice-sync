package com.flino.nlapi.claude

import com.flino.nlapi.config.AskProperties
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Per-caller token bucket for `/ask` -- the build prompt's "cost control...
 * consider basic per-caller rate limiting even though it's internal-only":
 * every call costs a real Claude API request, so one caller in a retry
 * loop shouldn't be able to run up the bill (or exhaust the Claude
 * circuit-breaker's failure budget for everyone else) unbounded.
 *
 * Deliberately simple: an in-memory bucket per caller, refilled
 * continuously at [AskProperties.RateLimit.requestsPerMinute] with
 * [AskProperties.RateLimit.burst] extra headroom on top. Good enough for a
 * single-instance internal service; a multi-instance deployment would need
 * a shared store (Redis, etc.) instead -- see the README's "edge cases not
 * handled" for why that's out of scope here. Buckets for callers that stop
 * calling are never evicted, an accepted unbounded-but-slow memory growth
 * for the same reason.
 */
@Component
class CallerRateLimiter(private val props: AskProperties) {

    private class Bucket(var tokens: Double, var lastRefillNanos: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Returns null if the call is allowed, or the number of seconds the caller should wait before retrying. */
    fun tryAcquire(callerId: String): Long? {
        if (!props.rateLimit.enabled) return null

        val capacity = (props.rateLimit.requestsPerMinute + props.rateLimit.burst).toDouble()
        val refillPerNano = props.rateLimit.requestsPerMinute / 60_000_000_000.0
        val now = System.nanoTime()
        val bucket = buckets.computeIfAbsent(callerId) { Bucket(capacity, now) }

        synchronized(bucket) {
            val elapsedNanos = (now - bucket.lastRefillNanos).coerceAtLeast(0)
            bucket.tokens = (bucket.tokens + elapsedNanos * refillPerNano).coerceAtMost(capacity)
            bucket.lastRefillNanos = now

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return null
            }
            val deficit = 1.0 - bucket.tokens
            return ceil(deficit / (props.rateLimit.requestsPerMinute / 60.0)).toLong().coerceAtLeast(1)
        }
    }
}
