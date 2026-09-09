package com.flino.nlapi.ask

import com.flino.nlapi.config.AskProperties
import com.flino.nlapi.trino.TrinoMetadataService
import org.springframework.stereotype.Component

data class SchemaContext(val description: String, val truncated: Boolean)

/**
 * Builds the `catalog.schema.table(col type, ...)` listing that goes into
 * the Claude prompt (see PromptBuilder.userMessage), bounded by
 * [AskProperties.maxContextTables]/[AskProperties.maxContextColumnsPerTable]
 * so an unscoped question (no catalog/schema given) can't blow up the
 * prompt size against a large deployment. This existence-validated
 * metadata -- not the question text -- is what constrains which
 * tables/columns Claude can legally reference; see AskService for how a
 * reference outside it is caught before execution.
 */
@Component
class AskContextBuilder(
    private val metadataService: TrinoMetadataService,
    private val props: AskProperties,
) {
    fun build(catalog: String?, schema: String?): SchemaContext {
        val catalogs = if (catalog != null) {
            metadataService.requireCatalogExists(catalog)
            listOf(catalog)
        } else {
            metadataService.listCatalogs()
        }

        val lines = mutableListOf<String>()
        var tableCount = 0
        var truncated = false

        outer@ for (cat in catalogs) {
            val schemas = if (schema != null) {
                metadataService.requireSchemaExists(cat, schema)
                listOf(schema)
            } else {
                metadataService.listSchemas(cat)
            }

            for (sch in schemas) {
                for (table in metadataService.listTables(cat, sch)) {
                    if (tableCount >= props.maxContextTables) {
                        truncated = true
                        break@outer
                    }
                    val columns = metadataService.describeTable(cat, sch, table.name).take(props.maxContextColumnsPerTable)
                    val columnText = columns.joinToString(", ") { "${it.name} ${it.type}" }
                    lines += "$cat.$sch.${table.name}($columnText)"
                    tableCount++
                }
            }
        }

        if (truncated) {
            lines += "... (more tables exist but were omitted; narrow the question with catalog/schema for full coverage)"
        }
        return SchemaContext(lines.joinToString("\n"), truncated)
    }
}
