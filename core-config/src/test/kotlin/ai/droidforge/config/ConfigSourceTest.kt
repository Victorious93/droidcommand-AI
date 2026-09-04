package ai.droidforge.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigSourceTest {
    @Test
    fun `MapConfigSource returns a stored value and null for a missing key`() {
        val source = MapConfigSource(mapOf("KEY" to "value"))
        assertEquals("value", source.get("KEY"))
        assertNull(source.get("MISSING"))
    }

    @Test
    fun `CompositeConfigSource returns the first non-null value in order`() {
        val source = CompositeConfigSource(
            listOf(
                MapConfigSource(emptyMap()),
                MapConfigSource(mapOf("KEY" to "second")),
                MapConfigSource(mapOf("KEY" to "third")),
            ),
        )
        assertEquals("second", source.get("KEY"))
    }

    @Test
    fun `CompositeConfigSource returns null when no source has the key`() {
        val source = CompositeConfigSource(listOf(MapConfigSource(emptyMap()), MapConfigSource(emptyMap())))
        assertNull(source.get("KEY"))
    }

    @Test
    fun `EnvConfigSource delegates to the injected environment function`() {
        var lookedUp: String? = null
        val source = EnvConfigSource { key -> lookedUp = key; "fake-value" }
        val result = source.get("SOME_VAR")
        assertEquals("SOME_VAR", lookedUp)
        assertEquals("fake-value", result)
    }
}
