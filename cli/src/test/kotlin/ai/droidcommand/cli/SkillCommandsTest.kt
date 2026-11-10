package ai.droidcommand.cli

import ai.droidcommand.config.ConfigSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillCommandsTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("cli-skill-commands-test")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun config(values: Map<String, String?>): ConfigSource = ConfigSource { key -> values[key] }

    private fun dirConfig(): ConfigSource = config(mapOf("DROIDCOMMAND_SKILLS_DIR" to root.toString()))

    private fun stateFile(): Path = root / "enabled-skills.txt"

    /** Writes a SKILL.md whose frontmatter declares a `parameters:` block (each entry is one '- ' line's content). */
    private fun writeSkillWithParameters(name: String, parameters: List<String>, body: String = "Body of $name") {
        val dir = (root / name).also { it.createDirectories() }
        val block = parameters.joinToString("\n") { "  - $it" }
        (dir / "SKILL.md").writeText(
            "---\nname: $name\ndescription: Skill $name\nparameters:\n$block\n---\n$body\n",
        )
    }

    private fun writeSkill(name: String, body: String = "Body of $name") {
        val dir = (root / name).also { it.createDirectories() }
        (dir / "SKILL.md").writeText(
            """
            ---
            name: $name
            description: Skill $name
            ---
            $body
            """.trimIndent(),
        )
    }

    /** Runs [block], returning its exit code plus everything it wrote to stdout and stderr. */
    private fun capture(block: () -> Int): Triple<Int, String, String> {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            val code = block()
            return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `no subcommand fails with usage`() {
        val (code, _, err) = capture { runSkills(emptyList(), dirConfig()) }
        assertEquals(1, code)
        assertTrue(err.contains("Usage: skills"), "expected usage on stderr, was: $err")
    }

    @Test
    fun `unknown subcommand fails with usage`() {
        val (code, _, err) = capture { runSkills(listOf("frobnicate"), dirConfig()) }
        assertEquals(1, code)
        assertTrue(err.contains("Unknown skills subcommand 'frobnicate'"))
    }

    @Test
    fun `list without a configured skills dir fails cleanly`() {
        val (code, _, err) = capture { runSkills(listOf("list"), config(emptyMap())) }
        assertEquals(1, code)
        assertTrue(err.contains("DROIDCOMMAND_SKILLS_DIR"))
    }

    @Test
    fun `list reports each discovered skill with its state`() {
        writeSkill("summarize")
        writeSkill("translate")
        stateFile().writeText("summarize=true\n")

        val (code, out, _) = capture { runSkills(listOf("list"), dirConfig()) }
        assertEquals(0, code)
        assertTrue(out.contains("1 enabled / 2 total"), out)
        assertTrue(out.contains("[enabled ] summarize — Skill summarize"), out)
        assertTrue(out.contains("[disabled] translate — Skill translate"), out)
    }

    @Test
    fun `list shows a skill's declared parameters with their config keys`() {
        writeSkillWithParameters(
            "translate",
            listOf(
                "target | Target language | required",
                "tone | Desired tone | optional | neutral",
            ),
        )
        stateFile().writeText("translate=true\n")

        val (code, out, _) = capture { runSkills(listOf("list"), dirConfig()) }
        assertEquals(0, code)
        assertTrue(out.contains("param target (required) — Target language  [set DROIDCOMMAND_SKILL_TRANSLATE_TARGET]"), out)
        assertTrue(
            out.contains("param tone (optional, default: neutral) — Desired tone  [set DROIDCOMMAND_SKILL_TRANSLATE_TONE]"),
            out,
        )
    }

    @Test
    fun `list omits a parameters line for a skill that declares none`() {
        writeSkill("summarize")
        stateFile().writeText("summarize=true\n")

        val (code, out, _) = capture { runSkills(listOf("list"), dirConfig()) }
        assertEquals(0, code)
        assertFalse(out.contains("param "), "a parameter-free skill should print no param lines: $out")
    }

    @Test
    fun `list on an empty skills dir succeeds and says so`() {
        val (code, out, _) = capture { runSkills(listOf("list"), dirConfig()) }
        assertEquals(0, code)
        assertTrue(out.contains("No skills"), out)
    }

    @Test
    fun `enable flips a discovered skill and persists it`() {
        writeSkill("summarize")

        val (code, out, _) = capture { runSkills(listOf("enable", "summarize"), dirConfig()) }
        assertEquals(0, code)
        assertTrue(out.contains("Skill 'summarize' is now enabled."))
        assertTrue(stateFile().exists(), "state file should be written")
        assertTrue(stateFile().readText().contains("summarize=true"))

        // A later list reflects the persisted decision.
        val (_, listOut, _) = capture { runSkills(listOf("list"), dirConfig()) }
        assertTrue(listOut.contains("[enabled ] summarize"), listOut)
    }

    @Test
    fun `disable flips an enabled skill back off and persists it`() {
        writeSkill("summarize")
        stateFile().writeText("summarize=true\n")

        val (code, out, _) = capture { runSkills(listOf("disable", "summarize"), dirConfig()) }
        assertEquals(0, code)
        assertTrue(out.contains("Skill 'summarize' is now disabled."))
        assertTrue(stateFile().readText().contains("summarize=false"))
    }

    @Test
    fun `enabling a skill not discovered on disk is refused`() {
        writeSkill("summarize")

        val (code, _, err) = capture { runSkills(listOf("enable", "ghost"), dirConfig()) }
        assertEquals(1, code)
        assertTrue(err.contains("No skill named 'ghost'"), err)
        // Nothing enabled => no contribution and no accidental write of the ghost name.
        if (stateFile().exists()) {
            assertFalse(stateFile().readText().contains("ghost"))
        }
    }

    @Test
    fun `enable requires a name`() {
        writeSkill("summarize")
        val (code, _, err) = capture { runSkills(listOf("enable"), dirConfig()) }
        assertEquals(1, code)
        assertTrue(err.contains("Usage: skills enable <name>"))
    }

    @Test
    fun `enable preserves a persisted entry for a skill no longer on disk`() {
        writeSkill("summarize")
        // 'archived' is approved in the state file but is not present on disk this run.
        stateFile().writeText("archived=true\n")

        val (code, _, _) = capture { runSkills(listOf("enable", "summarize"), dirConfig()) }
        assertEquals(0, code)
        val persisted = stateFile().readText()
        assertTrue(persisted.contains("summarize=true"), persisted)
        assertTrue(persisted.contains("archived=true"), "a decision for an off-disk skill must survive: $persisted")
    }

    @Test
    fun `honours a custom state file path`() {
        writeSkill("summarize")
        val custom = root / "custom-state.txt"
        val cfg = config(
            mapOf(
                "DROIDCOMMAND_SKILLS_DIR" to root.toString(),
                "DROIDCOMMAND_SKILLS_STATE_FILE" to custom.toString(),
            ),
        )

        val (code, _, _) = capture { runSkills(listOf("enable", "summarize"), cfg) }
        assertEquals(0, code)
        assertTrue(custom.exists(), "the custom state file should be written")
        assertTrue(custom.readText().contains("summarize=true"))
        assertFalse(stateFile().exists(), "the default state file should be left untouched")
    }
}
