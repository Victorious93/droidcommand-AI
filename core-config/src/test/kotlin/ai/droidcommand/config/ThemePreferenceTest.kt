package ai.droidcommand.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePreferenceTest {
    @Test
    fun `defaults are system mode, blue accent, dynamic off`() {
        assertEquals(ThemePreference(), ThemeController(InMemoryThemePreferenceStore()).current())
        assertEquals(ThemeMode.SYSTEM, ThemePreference().mode)
        assertEquals(ThemeAccent.BLUE, ThemePreference().accent)
        assertFalse(ThemePreference().dynamicColor)
    }

    @Test
    fun `choices persist across a reload of the same store`() {
        val store = InMemoryThemePreferenceStore()
        val first = ThemeController(store)
        first.setMode(ThemeMode.DARK)
        first.setAccent(ThemeAccent.TEAL)
        first.setDynamicColor(true)
        val reloaded = ThemeController(store).current()
        assertEquals(ThemeMode.DARK, reloaded.mode)
        assertEquals(ThemeAccent.TEAL, reloaded.accent)
        assertTrue(reloaded.dynamicColor)
    }

    @Test
    fun `codec round trips and falls back on junk`() {
        val original = ThemePreference(ThemeMode.LIGHT, ThemeAccent.ORANGE, dynamicColor = true)
        val encoded = ThemePreferenceCodec.encode(original)
        assertEquals(original, ThemePreferenceCodec.decode(encoded))
        assertEquals(ThemePreference(), ThemePreferenceCodec.decode(null))
        assertEquals(ThemePreference(), ThemePreferenceCodec.decode("mode=NEON\naccent=PINK\n"))
        assertEquals(ThemeMode.DARK, ThemePreferenceCodec.decode("mode=DARK\nunknown=1\n").mode)
    }

    @Test
    fun `system follows the phone, light and dark do not`() {
        assertTrue(ThemePreference(mode = ThemeMode.SYSTEM).resolvesToDark(true))
        assertFalse(ThemePreference(mode = ThemeMode.SYSTEM).resolvesToDark(false))
        assertFalse(ThemePreference(mode = ThemeMode.LIGHT).resolvesToDark(true))
        assertTrue(ThemePreference(mode = ThemeMode.DARK).resolvesToDark(false))
    }
}
