package ai.droidcommand.skills

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillStateStoreTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("skill-state-store-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `file store round-trips enabled state`() {
        val store = FileSkillStateStore(dir / "state.txt")
        store.save(mapOf("summarize" to true, "triage" to false))

        val reloaded = FileSkillStateStore(dir / "state.txt").load()
        assertEquals(mapOf("summarize" to true, "triage" to false), reloaded)
    }

    @Test
    fun `a missing file loads as an empty map, not an error`() {
        val store = FileSkillStateStore(dir / "does-not-exist.txt")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `save overwrites prior content atomically`() {
        val path = dir / "state.txt"
        val store = FileSkillStateStore(path)
        store.save(mapOf("a" to true, "b" to true))
        store.save(mapOf("a" to false)) // b dropped, a flipped

        assertEquals(mapOf("a" to false), FileSkillStateStore(path).load())
    }

    @Test
    fun `load skips malformed lines, comments, blanks, and invalid names`() {
        val path = dir / "state.txt"
        path.writeText(
            """
            # a comment
            summarize=true

            garbage-without-equals
            =true
            not a slug!=true
            triage=false
            trailing=TRUE
            """.trimIndent(),
        )
        val loaded = FileSkillStateStore(path).load()
        // Only the two well-formed, valid-slug lines survive; "TRUE" (wrong case) reads as not-enabled.
        assertEquals(mapOf("summarize" to true, "triage" to false, "trailing" to false), loaded)
    }

    @Test
    fun `the written file is human-readable and sorted`() {
        val path = dir / "state.txt"
        FileSkillStateStore(path).save(mapOf("zed" to true, "alpha" to false))
        val lines = path.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("alpha=false", "zed=true"), lines)
    }

    @Test
    fun `in-memory store round-trips`() {
        val store = InMemorySkillStateStore(mapOf("seed" to true))
        assertEquals(mapOf("seed" to true), store.load())
        store.save(mapOf("other" to false))
        assertEquals(mapOf("other" to false), store.load())
    }

    @Test
    fun `registry snapshot and apply round-trip through a store, re-hydrating approvals`() {
        // First run: install two skills (both disabled), enable one, persist.
        val registry1 = SkillRegistry()
        registry1.install(SkillManifest(name = "summarize", description = "d"))
        registry1.install(SkillManifest(name = "triage", description = "d"))
        registry1.enable("summarize")

        val store = FileSkillStateStore(dir / "state.txt")
        store.save(registry1.enabledStateByName())

        // Second run: a fresh registry re-installs (disabled), then applies persisted state.
        val registry2 = SkillRegistry()
        registry2.install(SkillManifest(name = "summarize", description = "d"))
        registry2.install(SkillManifest(name = "triage", description = "d"))
        registry2.applyEnabledState(store.load())

        assertEquals(listOf("summarize"), registry2.enabled().map { it.manifest.name })
        assertTrue(registry2.get("triage")!!.enabled.not())
    }

    @Test
    fun `applyEnabledState never auto-enables a skill the store does not list`() {
        val registry = SkillRegistry()
        registry.install(SkillManifest(name = "known", description = "d"))
        registry.install(SkillManifest(name = "brand-new", description = "d")) // not in the stored state

        registry.applyEnabledState(mapOf("known" to true))

        assertTrue(registry.get("known")!!.enabled)
        assertTrue(registry.get("brand-new")!!.enabled.not(), "a skill absent from the store stays disabled")
    }
}
