package ai.droidcommand.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRegistryTest {
    private fun manifest(name: String, description: String = "a skill", tools: List<String> = emptyList()) =
        SkillManifest(name = name, description = description, declaredTools = tools)

    @Test
    fun `install adds a skill in the disabled state`() {
        val registry = SkillRegistry()
        val result = assertIs<InstallResult.Installed>(registry.install(manifest("summarize")))
        assertFalse(result.skill.enabled, "a freshly installed skill must start disabled")
        assertFalse(registry.get("summarize")!!.enabled)
        assertTrue(registry.enabled().isEmpty(), "nothing should be enabled right after install")
    }

    @Test
    fun `install rejects a duplicate name without overwriting`() {
        val registry = SkillRegistry()
        registry.install(manifest("dup", description = "first"))
        val conflict = assertIs<InstallResult.Conflict>(registry.install(manifest("dup", description = "second")))
        assertEquals("dup", conflict.name)
        assertEquals("first", registry.get("dup")!!.manifest.description, "the original must be preserved")
    }

    @Test
    fun `enable then disable toggles the flag and is idempotent`() {
        val registry = SkillRegistry()
        registry.install(manifest("s"))

        assertTrue(registry.enable("s"))
        assertTrue(registry.get("s")!!.enabled)
        assertTrue(registry.enable("s"), "enable is idempotent and still reports the skill exists")
        assertEquals(listOf("s"), registry.enabled().map { it.manifest.name })

        assertTrue(registry.disable("s"))
        assertFalse(registry.get("s")!!.enabled)
        assertTrue(registry.enabled().isEmpty())
    }

    @Test
    fun `enable and disable report false for an unknown skill`() {
        val registry = SkillRegistry()
        assertFalse(registry.enable("nope"))
        assertFalse(registry.disable("nope"))
    }

    @Test
    fun `uninstall removes the skill`() {
        val registry = SkillRegistry()
        registry.install(manifest("s"))
        assertTrue(registry.uninstall("s"))
        assertNull(registry.get("s"))
        assertFalse(registry.uninstall("s"), "uninstalling a missing skill reports false")
    }

    @Test
    fun `list preserves installation order`() {
        val registry = SkillRegistry()
        registry.install(manifest("b"))
        registry.install(manifest("a"))
        registry.install(manifest("c"))
        assertEquals(listOf("b", "a", "c"), registry.list().map { it.manifest.name })
    }

    @Test
    fun `search matches name and description case-insensitively`() {
        val registry = SkillRegistry()
        registry.install(manifest("summarize", description = "Condense a document"))
        registry.install(manifest("translate", description = "Translate text"))
        registry.install(manifest("outline", description = "Summarize into headings"))

        assertEquals(listOf("summarize", "outline"), registry.search("summ").map { it.manifest.name })
        assertEquals(listOf("translate"), registry.search("TEXT").map { it.manifest.name })
        assertEquals(3, registry.search("  ").size, "a blank query matches everything")
    }

    @Test
    fun `enabling a skill does not alter its declared tools`() {
        // Enabling is approval-to-surface, not a capability grant; declaredTools stays advisory metadata.
        val registry = SkillRegistry()
        registry.install(manifest("s", tools = listOf("run_shell", "read_file")))
        registry.enable("s")
        assertEquals(listOf("run_shell", "read_file"), registry.get("s")!!.manifest.declaredTools)
    }
}
