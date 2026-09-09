package com.flino.config.service

import com.flino.config.config.ConfigStoreProperties
import com.flino.config.dto.ColumnDto
import com.flino.config.dto.DestinationDto
import com.flino.config.dto.UpsertConfigRequest
import com.flino.config.exception.ConfigValidationException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigValidatorTest {

    private val tinyLimits = ConfigStoreProperties.Limits(
        maxNameLength = 10,
        maxSourceLength = 10,
        maxBusinessDescriptionLength = 20,
        maxColumnDescriptionLength = 10,
        maxExampleQuestionLength = 15,
        maxExampleQuestions = 2,
        maxColumns = 2,
        maxCatalogSchemaTableLength = 10,
    )
    private val validator = ConfigValidator(ConfigStoreProperties(limits = tinyLimits))

    private fun request(
        name: String = "orders",
        source: String = "orders.csv",
        businessDescription: String = "short",
        columns: List<ColumnDto> = listOf(ColumnDto("id", "bigint")),
        exampleQuestions: List<String> = emptyList(),
    ) = UpsertConfigRequest(
        id = null,
        name = name,
        source = source,
        destination = DestinationDto("iceberg", "sales", "orders"),
        columns = columns,
        businessDescription = businessDescription,
        exampleQuestions = exampleQuestions,
    )

    @Test
    fun `a request within every limit passes`() {
        assertDoesNotThrow { validator.validate(request()) }
    }

    @Test
    fun `a name over the length cap fails with a field error keyed on name`() {
        val ex = assertThrows<ConfigValidationException> { validator.validate(request(name = "way too long a name")) }
        assertTrue(ex.fieldErrors.containsKey("name"))
    }

    @Test
    fun `a business description over the length cap fails`() {
        val ex = assertThrows<ConfigValidationException> {
            validator.validate(request(businessDescription = "this description is definitely too long for the cap"))
        }
        assertTrue(ex.fieldErrors.containsKey("businessDescription"))
    }

    @Test
    fun `too many columns -- a wide inferred schema -- fails on the columns field, not per-column`() {
        val manyColumns = (1..5).map { ColumnDto("c$it", "bigint") }
        val ex = assertThrows<ConfigValidationException> { validator.validate(request(columns = manyColumns)) }
        assertTrue(ex.fieldErrors.containsKey("columns"))
    }

    @Test
    fun `a single column description over its cap fails with an indexed field key`() {
        val columns = listOf(ColumnDto("id", "bigint", description = "a description that is much too long"))
        val ex = assertThrows<ConfigValidationException> { validator.validate(request(columns = columns)) }
        assertTrue(ex.fieldErrors.containsKey("columns[0].description"))
    }

    @Test
    fun `too many example questions fails on the exampleQuestions field`() {
        val ex = assertThrows<ConfigValidationException> {
            validator.validate(request(exampleQuestions = listOf("one?", "two?", "three?")))
        }
        assertTrue(ex.fieldErrors.containsKey("exampleQuestions"))
    }

    @Test
    fun `a single example question over its length cap fails with an indexed field key`() {
        val ex = assertThrows<ConfigValidationException> {
            validator.validate(request(exampleQuestions = listOf("this question is way too long for the cap")))
        }
        assertTrue(ex.fieldErrors.containsKey("exampleQuestions[0]"))
    }

    @Test
    fun `multiple violations are all reported together, not just the first`() {
        val ex = assertThrows<ConfigValidationException> {
            validator.validate(request(name = "way too long a name", businessDescription = "this description is definitely too long"))
        }
        assertTrue(ex.fieldErrors.size >= 2)
    }
}
