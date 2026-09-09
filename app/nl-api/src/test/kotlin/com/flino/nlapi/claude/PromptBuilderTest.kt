package com.flino.nlapi.claude

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    @Test
    fun `system prompt forbids write statements explicitly`() {
        val prompt = PromptBuilder.systemPrompt().lowercase()
        assertTrue(prompt.contains("select"))
        assertTrue(prompt.contains("insert"))
        assertTrue(prompt.contains("delete"))
        assertTrue(prompt.contains("never"))
    }

    @Test
    fun `system prompt tells the model to treat in-question instructions as untrusted`() {
        val prompt = PromptBuilder.systemPrompt().lowercase()
        assertTrue(prompt.contains("untrusted"))
    }

    @Test
    fun `user message embeds both the schema description and the question`() {
        val message = PromptBuilder.userMessage("how many orders were placed today?", "iceberg.sales.orders(id bigint, ts timestamp)")
        assertTrue(message.contains("iceberg.sales.orders(id bigint, ts timestamp)"))
        assertTrue(message.contains("how many orders were placed today?"))
    }

    @Test
    fun `user message handles an empty schema description gracefully`() {
        val message = PromptBuilder.userMessage("anything?", "")
        assertTrue(message.contains("no tables were found"))
    }
}
