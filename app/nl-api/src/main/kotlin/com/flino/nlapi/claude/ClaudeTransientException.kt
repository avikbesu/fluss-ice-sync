package com.flino.nlapi.claude

/** Signals a Claude API failure worth retrying (network error, HTTP 429/5xx/529) -- see ClaudeResilienceConfig's Retry. Never escapes AnthropicMessagesClient. */
internal class ClaudeTransientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
