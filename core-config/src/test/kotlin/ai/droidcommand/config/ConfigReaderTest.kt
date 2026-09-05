package ai.droidcommand.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigReaderTest {
    @Test
    fun `require returns the value when present`() {
        val reader = ConfigReader(MapConfigSource(mapOf("KEY" to "value")))
        assertEquals("value", reader.require("KEY"))
    }

    @Test
    fun `require throws MissingConfigException when absent`() {
        val reader = ConfigReader(MapConfigSource(emptyMap()))
        assertFailsWith<MissingConfigException> { reader.require("KEY") }
    }

    @Test
    fun `optional falls back to the default when absent`() {
        val reader = ConfigReader(MapConfigSource(emptyMap()))
        assertEquals("fallback", reader.optional("KEY", "fallback"))
        assertNull(reader.optional("KEY"))
    }

    @Test
    fun `optionalInt parses a present value and falls back on absence or a bad parse`() {
        val reader = ConfigReader(MapConfigSource(mapOf("N" to "42", "BAD" to "not-a-number")))
        assertEquals(42, reader.optionalInt("N"))
        assertEquals(7, reader.optionalInt("MISSING", 7))
        assertEquals(null, reader.optionalInt("BAD"))
    }

    @Test
    fun `optionalDouble parses a present value and falls back on absence or a bad parse`() {
        val reader = ConfigReader(MapConfigSource(mapOf("T" to "0.7", "BAD" to "nope")))
        assertEquals(0.7, reader.optionalDouble("T"))
        assertEquals(1.0, reader.optionalDouble("MISSING", 1.0))
        assertEquals(null, reader.optionalDouble("BAD"))
    }

    @Test
    fun `optionalBoolean parses case-insensitively and defaults to false`() {
        val reader = ConfigReader(MapConfigSource(mapOf("A" to "true", "B" to "TRUE", "C" to "false", "D" to "nonsense")))
        assertEquals(true, reader.optionalBoolean("A"))
        assertEquals(true, reader.optionalBoolean("B"))
        assertEquals(false, reader.optionalBoolean("C"))
        assertEquals(false, reader.optionalBoolean("D"))
        assertEquals(false, reader.optionalBoolean("MISSING"))
        assertEquals(true, reader.optionalBoolean("MISSING", default = true))
    }
}
