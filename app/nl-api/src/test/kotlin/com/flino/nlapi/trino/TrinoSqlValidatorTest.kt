package com.flino.nlapi.trino

import com.flino.nlapi.exception.StatementNotAllowedException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TrinoSqlValidatorTest {

    private val validator = TrinoSqlValidator()

    @Test
    fun `allows a plain SELECT`() {
        assertDoesNotThrow { validator.assertReadOnly("SELECT * FROM iceberg.sales.orders") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["SHOW CATALOGS", "SHOW SCHEMAS FROM iceberg", "DESCRIBE iceberg.sales.orders", "EXPLAIN SELECT 1"])
    fun `allows metadata statements`(sql: String) {
        assertDoesNotThrow { validator.assertReadOnly(sql) }
    }

    @Test
    fun `allows a WITH clause that ends in SELECT`() {
        assertDoesNotThrow {
            validator.assertReadOnly(
                "WITH recent AS (SELECT * FROM iceberg.sales.orders WHERE order_ts > DATE '2024-01-01') SELECT count(*) FROM recent",
            )
        }
    }

    @Test
    fun `allows multiple CTEs before the final SELECT`() {
        assertDoesNotThrow {
            validator.assertReadOnly(
                "WITH a AS (SELECT 1 AS x), b (y) AS (SELECT 2) SELECT a.x, b.y FROM a, b",
            )
        }
    }

    @Test
    fun `rejects a bare write statement`() {
        val ex = assertThrows<StatementNotAllowedException> { validator.assertReadOnly("DELETE FROM iceberg.sales.orders") }
        assert(ex.message!!.contains("DELETE"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["INSERT INTO t SELECT 1", "UPDATE t SET x = 1", "DELETE FROM t", "DROP TABLE t", "CREATE TABLE t (x int)", "CALL some_procedure()"])
    fun `rejects every non-read statement type`(sql: String) {
        assertThrows<StatementNotAllowedException> { validator.assertReadOnly(sql) }
    }

    @Test
    fun `rejects a write statement disguised behind a CTE`() {
        // Only SELECT may legally follow a WITH clause in Trino's own grammar; a
        // write statement after one must never be treated as read-only just
        // because "WITH" was the first word.
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("WITH x AS (SELECT 1) INSERT INTO iceberg.sales.orders SELECT * FROM x")
        }
    }

    @Test
    fun `rejects a write statement disguised behind a line comment`() {
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("-- SELECT is fine right?\nDELETE FROM iceberg.sales.orders")
        }
    }

    @Test
    fun `rejects a write statement disguised behind a block comment`() {
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("/* this looks safe */ DROP TABLE iceberg.sales.orders")
        }
    }

    @Test
    fun `rejects a write statement disguised via case variation`() {
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("dElEtE FROM iceberg.sales.orders")
        }
    }

    @Test
    fun `is not fooled by a comment containing a semicolon or write keyword`() {
        assertDoesNotThrow {
            validator.assertReadOnly("SELECT * FROM iceberg.sales.orders -- ; DELETE FROM x\n WHERE id = 1")
        }
    }

    @Test
    fun `is not fooled by a string literal containing SQL keywords`() {
        assertDoesNotThrow {
            validator.assertReadOnly("SELECT * FROM iceberg.sales.orders WHERE note = 'please DELETE FROM everything; DROP TABLE x'")
        }
    }

    @Test
    fun `rejects multiple statements even if all individually read-only`() {
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("SELECT 1; SELECT 2")
        }
    }

    @Test
    fun `rejects a write statement stacked after a read-only one via semicolon`() {
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("SELECT * FROM iceberg.sales.orders; DROP TABLE iceberg.sales.orders")
        }
    }

    @Test
    fun `tolerates a single trailing semicolon`() {
        assertDoesNotThrow { validator.assertReadOnly("SELECT 1;") }
    }

    @Test
    fun `rejects blank input`() {
        assertThrows<StatementNotAllowedException> { validator.assertReadOnly("   ") }
    }

    @Test
    fun `rejects a WITH clause not followed by SELECT`() {
        assertThrows<StatementNotAllowedException> {
            validator.assertReadOnly("WITH x AS (SELECT 1) DESCRIBE iceberg.sales.orders")
        }
    }
}
