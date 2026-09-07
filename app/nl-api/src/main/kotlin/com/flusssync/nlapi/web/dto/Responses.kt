package com.flusssync.nlapi.web.dto

import com.flusssync.nlapi.trino.ColumnDescription
import com.flusssync.nlapi.trino.Page
import com.flusssync.nlapi.trino.TableSummary

data class PageInfo(val offset: Int, val limit: Int, val total: Int, val hasMore: Boolean)

private fun <T> Page<T>.info() = PageInfo(offset, limit, total, hasMore)

data class CatalogsResponse(val catalogs: List<String>, val page: PageInfo)
data class SchemasResponse(val catalog: String, val schemas: List<String>, val page: PageInfo)
data class TablesResponse(val catalog: String, val schema: String, val tables: List<TableSummary>, val page: PageInfo)
data class DescribeResponse(
    val catalog: String,
    val schema: String,
    val table: String,
    val columns: List<ColumnDescription>,
    val page: PageInfo,
)
data class DdlResponse(val catalog: String, val schema: String, val table: String, val ddl: String)

data class ErrorResponse(val code: String, val message: String, val details: Map<String, Any?> = emptyMap())

fun catalogsResponse(page: Page<String>) = CatalogsResponse(page.items, page.info())
fun schemasResponse(catalog: String, page: Page<String>) = SchemasResponse(catalog, page.items, page.info())
fun tablesResponse(catalog: String, schema: String, page: Page<TableSummary>) = TablesResponse(catalog, schema, page.items, page.info())
fun describeResponse(catalog: String, schema: String, table: String, page: Page<ColumnDescription>) =
    DescribeResponse(catalog, schema, table, page.items, page.info())
