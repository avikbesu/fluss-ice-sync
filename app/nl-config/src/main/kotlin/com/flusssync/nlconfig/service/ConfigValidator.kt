package com.flusssync.nlconfig.service

import com.flusssync.nlconfig.config.ConfigStoreProperties
import com.flusssync.nlconfig.dto.UpsertConfigRequest
import com.flusssync.nlconfig.exception.ConfigValidationException
import org.springframework.stereotype.Component

/**
 * The length/count limits from [ConfigStoreProperties] applied at write
 * time -- runtime-configurable, so they can't be static `@Size`
 * annotations on [UpsertConfigRequest] (see that class's doc). Produces
 * the same field-level error map a bean-validation failure would, so the
 * UI wizard's inline-error handling doesn't need to distinguish the two.
 *
 * Also where the build prompt's "very wide inferred schemas (hundreds of
 * columns)" and general payload-size edge cases are enforced.
 */
@Component
class ConfigValidator(private val props: ConfigStoreProperties) {

    fun validate(request: UpsertConfigRequest) {
        val limits = props.limits
        val errors = mutableMapOf<String, String>()

        checkLength(errors, "name", request.name, limits.maxNameLength)
        checkLength(errors, "source", request.source, limits.maxSourceLength)
        checkLength(errors, "destination.catalog", request.destination.catalog, limits.maxCatalogSchemaTableLength)
        checkLength(errors, "destination.schema", request.destination.schema, limits.maxCatalogSchemaTableLength)
        checkLength(errors, "destination.table", request.destination.table, limits.maxCatalogSchemaTableLength)
        checkLength(errors, "businessDescription", request.businessDescription, limits.maxBusinessDescriptionLength)

        if (request.columns.size > limits.maxColumns) {
            errors["columns"] = "must have at most ${limits.maxColumns} columns (got ${request.columns.size})"
        } else {
            request.columns.forEachIndexed { index, column ->
                checkLength(errors, "columns[$index].name", column.name, limits.maxNameLength)
                column.description?.let { checkLength(errors, "columns[$index].description", it, limits.maxColumnDescriptionLength) }
            }
        }

        if (request.exampleQuestions.size > limits.maxExampleQuestions) {
            errors["exampleQuestions"] = "must have at most ${limits.maxExampleQuestions} example questions (got ${request.exampleQuestions.size})"
        } else {
            request.exampleQuestions.forEachIndexed { index, question ->
                checkLength(errors, "exampleQuestions[$index]", question, limits.maxExampleQuestionLength)
            }
        }

        if (errors.isNotEmpty()) {
            throw ConfigValidationException(errors)
        }
    }

    private fun checkLength(errors: MutableMap<String, String>, field: String, value: String, max: Int) {
        if (value.length > max) {
            errors[field] = "must be at most $max characters (got ${value.length})"
        }
    }
}
