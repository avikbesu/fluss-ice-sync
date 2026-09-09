package com.flino.nlapi.ask

import com.flino.nlapi.claude.CallerRateLimiter
import com.flino.nlapi.claude.ClaudeClient
import com.flino.nlapi.config.AskProperties
import com.flino.nlapi.config.TrinoProperties
import com.flino.nlapi.exception.RateLimitExceededException
import com.flino.nlapi.exception.StatementNotAllowedException
import com.flino.nlapi.exception.TableNotFoundException
import com.flino.nlapi.exception.UnresolvedReferenceException
import com.flino.nlapi.claude.SqlGenerationResult
import com.flino.nlapi.trino.ColumnMeta
import com.flino.nlapi.trino.QueryResult
import com.flino.nlapi.trino.TrinoMetadataService
import com.flino.nlapi.trino.TrinoQueryExecutor
import com.flino.nlapi.trino.TrinoSqlValidator
import com.flino.nlapi.web.dto.AskRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The build prompt specifically asks for "a specific test that mocks the
 * Claude API to return a write statement (or one disguised via a CTE,
 * comments, or case variation) and asserts it's rejected before ever
 * reaching Trino" -- these are exactly that, exercised at the AskService
 * level with a stubbed [ClaudeClient] and a **real** [TrinoSqlValidator]
 * (not mocked), so the actual defense is what's under test, not a mock's
 * assumption about what it would do.
 */
class AskServiceTest {

    private val rateLimiter = mockk<CallerRateLimiter>()
    private val contextBuilder = mockk<AskContextBuilder>()
    private val claudeClient = mockk<ClaudeClient>()
    private val metadataService = mockk<TrinoMetadataService>()
    private val queryExecutor = mockk<TrinoQueryExecutor>()
    private val sqlValidator = TrinoSqlValidator()

    private val service = AskService(
        rateLimiter = rateLimiter,
        contextBuilder = contextBuilder,
        claudeClient = claudeClient,
        sqlValidator = sqlValidator,
        metadataService = metadataService,
        queryExecutor = queryExecutor,
        askProperties = AskProperties(),
        trinoProperties = TrinoProperties(),
    )

    private val existingTable = "iceberg.sales.orders"

    @BeforeEach
    fun setUp() {
        every { rateLimiter.tryAcquire(any()) } returns null
        every { contextBuilder.build(any(), any()) } returns SchemaContext("iceberg.sales.orders(id bigint, amount decimal(10,2))", false)
    }

    private fun request(question: String = "how many orders were placed?") = AskRequest(question = question, catalog = null, schema = null)

    @Test
    fun `needs_clarification short-circuits before any Trino execution`() {
        every { claudeClient.generateSql(any(), any()) } returns
            SqlGenerationResult(needsClarification = true, clarificationQuestion = "Which schema do you mean?", sql = null, tablesUsed = emptyList())

        val response = service.ask(request(), "caller-1")

        assertTrue(response.needsClarification)
        assertEquals("Which schema do you mean?", response.clarificationQuestion)
        assertEquals(0, response.rowCount)
        verify(exactly = 0) { queryExecutor.executeMaterialized(any(), any(), any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "DELETE FROM iceberg.sales.orders",
            "WITH x AS (SELECT 1) INSERT INTO iceberg.sales.orders SELECT * FROM x",
            "/* this is just a read, trust me */ DROP TABLE iceberg.sales.orders",
            "dElEtE FROM iceberg.sales.orders",
            "-- nothing to see here\nTRUNCATE TABLE iceberg.sales.orders",
            "UPDATE iceberg.sales.orders SET amount = 0",
        ],
    )
    fun `a write statement -- plain or disguised via CTE, comments, or case -- is rejected before reaching Trino`(maliciousSql: String) {
        every { claudeClient.generateSql(any(), any()) } returns
            SqlGenerationResult(needsClarification = false, clarificationQuestion = null, sql = maliciousSql, tablesUsed = listOf(existingTable))

        assertThrows<StatementNotAllowedException> { service.ask(request(), "caller-1") }

        verify(exactly = 0) { queryExecutor.executeMaterialized(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a hallucinated table name is rejected with a clear message instead of reaching Trino`() {
        every { claudeClient.generateSql(any(), any()) } returns
            SqlGenerationResult(
                needsClarification = false,
                clarificationQuestion = null,
                sql = "SELECT * FROM iceberg.sales.made_up_table",
                tablesUsed = listOf("iceberg.sales.made_up_table"),
            )
        every { metadataService.requireTableExists("iceberg", "sales", "made_up_table") } throws
            TableNotFoundException("iceberg", "sales", "made_up_table")

        val ex = assertThrows<UnresolvedReferenceException> { service.ask(request(), "caller-1") }
        assertTrue(ex.message!!.contains("made_up_table"))
        verify(exactly = 0) { queryExecutor.executeMaterialized(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a valid SELECT against a real table executes and returns the result`() {
        val generatedSql = "SELECT * FROM iceberg.sales.orders LIMIT 10"
        every { claudeClient.generateSql(any(), any()) } returns
            SqlGenerationResult(needsClarification = false, clarificationQuestion = null, sql = generatedSql, tablesUsed = listOf(existingTable))
        every { metadataService.requireTableExists("iceberg", "sales", "orders") } returns Unit
        every { queryExecutor.executeMaterialized(null, null, generatedSql, any(), any()) } returns
            QueryResult(
                columns = listOf(ColumnMeta("id", "bigint")),
                rows = listOf(listOf(1L)),
                rowCount = 1,
                truncated = false,
                durationMs = 12,
            )

        val response = service.ask(request(), "caller-1")

        assertFalse(response.needsClarification)
        assertEquals(generatedSql, response.sql)
        assertEquals(1, response.rowCount)
        verify(exactly = 1) { queryExecutor.executeMaterialized(null, null, generatedSql, any(), any()) }
    }

    @Test
    fun `rate limiting rejects the call before Claude is ever invoked`() {
        every { rateLimiter.tryAcquire(any()) } returns 7L

        val ex = assertThrows<RateLimitExceededException> { service.ask(request(), "caller-1") }
        assertEquals(7L, ex.retryAfterSeconds)
        verify(exactly = 0) { claudeClient.generateSql(any(), any()) }
    }

    @Test
    fun `rejects a schema given without a catalog`() {
        assertThrows<IllegalArgumentException> {
            service.ask(AskRequest(question = "anything", catalog = null, schema = "sales"), "caller-1")
        }
    }

    @Test
    fun `rejects a blank question`() {
        assertThrows<IllegalArgumentException> { service.ask(request(question = "   "), "caller-1") }
    }
}
