package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ToolParameterSchemaTest {
    private val schema = ToolParameterSchema(
        parameters = listOf(
            ToolParameter("text", ToolJsonType.STRING, required = true),
            ToolParameter("count", ToolJsonType.NUMBER),
            ToolParameter("tags", ToolJsonType.ARRAY),
            ToolParameter("meta", ToolJsonType.OBJECT),
        ),
    )

    @Test
    fun `valid typed arguments pass`() {
        val result = schema.validate(
            mapOf(
                "text" to ToolArgumentValue.Text("hi"),
                "count" to ToolArgumentValue.Number("3"),
                "tags" to ToolArgumentValue.NestedJson("""["a"]"""),
                "meta" to ToolArgumentValue.NestedJson("""{"k":"v"}"""),
            ),
        )
        assertEquals(ToolSchemaValidation.Valid, result)
    }

    @Test
    fun `missing required argument is invalid`() {
        val result = schema.validate(emptyMap())
        val invalid = assertIs<ToolSchemaValidation.Invalid>(result)
        assertEquals("missing required argument 'text'", invalid.reason)
    }

    @Test
    fun `wrong JSON type is invalid`() {
        val result = schema.validate(
            mapOf(
                "text" to ToolArgumentValue.Text("hi"),
                "count" to ToolArgumentValue.Text("3"),
            ),
        )
        val invalid = assertIs<ToolSchemaValidation.Invalid>(result)
        assertEquals("argument 'count' must be number", invalid.reason)
    }

    @Test
    fun `array schema rejects an object payload`() {
        val result = schema.validate(
            mapOf(
                "text" to ToolArgumentValue.Text("hi"),
                "tags" to ToolArgumentValue.NestedJson("""{"k":"v"}"""),
            ),
        )
        val invalid = assertIs<ToolSchemaValidation.Invalid>(result)
        assertEquals("argument 'tags' must be array", invalid.reason)
    }

    @Test
    fun `additional properties are allowed by default`() {
        val result = schema.validate(
            mapOf(
                "text" to ToolArgumentValue.Text("hi"),
                "extra" to ToolArgumentValue.Text("ok"),
            ),
        )
        assertEquals(ToolSchemaValidation.Valid, result)
    }

    @Test
    fun `additional properties can be refused`() {
        val strict = schema.copy(additionalProperties = false)
        val result = strict.validate(
            mapOf(
                "text" to ToolArgumentValue.Text("hi"),
                "extra" to ToolArgumentValue.Text("nope"),
            ),
        )
        val invalid = assertIs<ToolSchemaValidation.Invalid>(result)
        assertEquals("unexpected argument 'extra'", invalid.reason)
    }

    @Test
    fun `duplicate parameter names are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            ToolParameterSchema(
                parameters = listOf(
                    ToolParameter("text", ToolJsonType.STRING),
                    ToolParameter("text", ToolJsonType.NUMBER),
                ),
            )
        }
    }
}
