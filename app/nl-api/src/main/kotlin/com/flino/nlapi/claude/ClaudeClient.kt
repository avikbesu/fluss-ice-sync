package com.flino.nlapi.claude

/** Deliberately Trino-agnostic -- takes fully-formed prompt text, returns structured output. See PromptBuilder for how AskService builds that text. */
fun interface ClaudeClient {
    fun generateSql(systemPrompt: String, userMessage: String): SqlGenerationResult
}
