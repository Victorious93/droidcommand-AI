package ai.droidcommand.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolArgumentsTest {
    @Test
    fun `string and number primitives become text and number, then string-map values`() {
        val typed = ToolArgumentCodec.fromJsonMap(
            mapOf(
                "text" to JsonPrimitive("hi"),
                "count" to JsonPrimitive(3),
            ),
        )
        assertEquals(ToolArgumentValue.Text("hi"), typed["text"])
        assertEquals(ToolArgumentValue.Number("3"), typed["count"])
        assertEquals(mapOf("text" to "hi", "count" to "3"), ToolArgumentCodec.toStringMap(typed))
    }

    @Test
    fun `boolean primitives stay booleans and stringify as true or false`() {
        val typed = ToolArgumentCodec.fromJsonMap(
            mapOf("on" to JsonPrimitive(true), "off" to JsonPrimitive(false)),
        )
        assertEquals(ToolArgumentValue.Bool(true), typed["on"])
        assertEquals(ToolArgumentValue.Bool(false), typed["off"])
        assertEquals(mapOf("on" to "true", "off" to "false"), ToolArgumentCodec.toStringMap(typed))
    }

    @Test
    fun `a JSON string true is text, not a boolean`() {
        val typed = ToolArgumentCodec.fromJsonMap(mapOf("flag" to JsonPrimitive("true")))
        assertEquals(ToolArgumentValue.Text("true"), typed["flag"])
    }

    @Test
    fun `nested array and object arguments are preserved as compact JSON text`() {
        val typed = ToolArgumentCodec.fromJsonMap(
            mapOf(
                "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                "meta" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
            ),
        )
        val tags = assertIs<ToolArgumentValue.NestedJson>(typed["tags"])
        val meta = assertIs<ToolArgumentValue.NestedJson>(typed["meta"])
        assertEquals("""["a","b"]""", tags.json)
        assertEquals("""{"k":"v"}""", meta.json)
        val asStrings = ToolArgumentCodec.toStringMap(typed)
        assertEquals("""["a","b"]""", asStrings["tags"])
        assertEquals("""{"k":"v"}""", asStrings["meta"])
    }

    @Test
    fun `explicit JSON null is omitted, not passed as the text null`() {
        val typed = ToolArgumentCodec.fromJsonMap(
            mapOf("text" to JsonPrimitive("hi"), "optional" to JsonNull),
        )
        assertEquals(setOf("text"), typed.keys)
        assertEquals(mapOf("text" to "hi"), ToolArgumentCodec.toStringMap(typed))
    }

    @Test
    fun `null or empty argument maps become empty`() {
        assertEquals(emptyMap(), ToolArgumentCodec.fromJsonMap(null))
        assertEquals(emptyMap(), ToolArgumentCodec.fromJsonMap(emptyMap()))
    }

    @Test
    fun `call envelopes mint unique non-blank ids`() {
        val a = ToolCallEnvelope.newId()
        val b = ToolCallEnvelope.newId()
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        assertTrue(a != b)
        val envelope = ToolCallEnvelope(callId = a, toolName = "echo", arguments = emptyMap())
        assertEquals("echo", envelope.toolName)
    }
}
