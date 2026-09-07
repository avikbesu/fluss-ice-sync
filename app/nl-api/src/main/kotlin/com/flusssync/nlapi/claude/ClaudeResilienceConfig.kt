package com.flusssync.nlapi.claude

import com.flusssync.nlapi.config.ClaudeProperties
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Retry and circuit-breaker policy for calls to the Claude API -- the
 * build prompt's "Claude API latency, timeouts, and rate limits -> a
 * sensible request timeout, retry/backoff policy, and circuit breaker so a
 * slow or failed LLM call can't hang the request indefinitely".
 *
 * Only [ClaudeTransientException] triggers a retry (network errors, HTTP
 * 429, HTTP 5xx/529) -- a non-retryable failure (bad request, auth error)
 * fails immediately rather than repeating a call guaranteed to fail the
 * same way. The circuit breaker counts each retried call sequence as one
 * outcome and, once tripped, fails fast for [ClaudeProperties.CircuitBreaker.waitDurationInOpenState]
 * instead of letting every request queue up behind a Claude API that's
 * already down.
 */
@Configuration
class ClaudeResilienceConfig {

    @Bean
    fun claudeRetry(props: ClaudeProperties): Retry {
        val config = RetryConfig.custom<Any>()
            .maxAttempts(props.retry.maxAttempts)
            .waitDuration(props.retry.waitDuration)
            .retryExceptions(ClaudeTransientException::class.java)
            .build()
        return Retry.of("claude-api", config)
    }

    @Bean
    fun claudeCircuitBreaker(props: ClaudeProperties): CircuitBreaker {
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(props.circuitBreaker.failureRateThreshold)
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(props.circuitBreaker.slidingWindowSize)
            .minimumNumberOfCalls(props.circuitBreaker.minimumNumberOfCalls)
            .waitDurationInOpenState(props.circuitBreaker.waitDurationInOpenState)
            .permittedNumberOfCallsInHalfOpenState(props.circuitBreaker.permittedCallsInHalfOpenState)
            .build()
        return CircuitBreaker.of("claude-api", config)
    }
}
