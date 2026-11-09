package ai.droidcommand.skills

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillDiscoveryTest {
    private lateinit var root: Path
    private val discovery = SkillDiscovery()

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("skill-discovery-test")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun writeSkill(relativeDir: String, content: String) {
        val dir = root.resolve(relativeDir)
        dir.createDirectories()
        (dir / "SKILL.md").writeText(content)
    }

    private fun validSkill(name: String) = """
        ---
        name: $name
        description: Skill $name
        tools: [read_file]
        ---
        Body of $name.
        """.trimIndent()

    @Test
    fun `discovers and parses skill files across nested directories, sorted by path`() {
        writeSkill("alpha", validSkill("alpha"))
        writeSkill("nested/deep", validSkill("deep"))

        val report = discovery.discover(root)

        assertTrue(report.failures.isEmpty(), "no failures expected, got ${report.failures}")
        assertEquals(listOf("alpha", "deep"), report.discovered.map { it.manifest.name })
        // Sorted by path: "alpha" sorts before "nested/deep".
        assertTrue(report.discovered[0].sourcePath < report.discovered[1].sourcePath)
        assertEquals(listOf("read_file"), report.discovered[0].manifest.declaredTools)
    }

    @Test
    fun `records a malformed skill as a failure without hiding the valid ones`() {
        writeSkill("good", validSkill("good"))
        writeSkill("bad", "---\nname: bad\n") // missing description and closing fence

        val report = discovery.discover(root)

        assertEquals(listOf("good"), report.discovered.map { it.manifest.name })
        assertEquals(1, report.failures.size)
        assertTrue(report.failures[0].sourcePath.endsWith("bad/SKILL.md".replace("/", java.io.File.separator)))
        assertTrue(report.failures[0].reason.isNotBlank())
    }

    @Test
    fun `ignores files that are not named SKILL dot md`() {
        writeSkill("real", validSkill("real"))
        (root / "notes.md").writeText("# just notes")
        (root / "skill.md").writeText(validSkill("wrongcase")) // lowercase name is not SKILL.md

        val report = discovery.discover(root)

        assertEquals(listOf("real"), report.discovered.map { it.manifest.name })
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `an empty tree yields an empty report`() {
        val report = discovery.discover(root)
        assertTrue(report.discovered.isEmpty())
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `a non-existent root yields one failure, not an exception`() {
        val missing = root / "does-not-exist"
        val report = discovery.discover(missing)
        assertTrue(report.discovered.isEmpty())
        assertEquals(1, report.failures.size)
        assertTrue(report.failures[0].reason.contains("does not exist or is not a directory"))
    }

    @Test
    fun `a custom file name is honoured`() {
        val dir = root.resolve("x").also { it.createDirectories() }
        (dir / "AGENT.md").writeText(validSkill("custom"))
        (dir / "SKILL.md").writeText(validSkill("ignored"))

        val report = SkillDiscovery(fileName = "AGENT.md").discover(root)

        assertEquals(listOf("custom"), report.discovered.map { it.manifest.name })
    }

    @Test
    fun `discovered skills install into a registry, all disabled`() {
        writeSkill("a", validSkill("a"))
        writeSkill("b", validSkill("b"))
        val registry = SkillRegistry()

        val report = discovery.discover(root)
        report.discovered.forEach { registry.install(it.manifest) }

        assertEquals(setOf("a", "b"), registry.list().map { it.manifest.name }.toSet())
        assertTrue(registry.enabled().isEmpty(), "discovery must not enable anything")
    }
}
