package ai.droidcommand.repomap

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RM-10: the `repo_map` tool end to end, against real Kotlin files on disk. */
class RepoMapToolTest {
    private fun project(): Path {
        val root = createTempDirectory("repomap-tool").toRealPath()
        fun write(relative: String, text: String) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            file.writeText(text)
        }
        write("alpha/Engine.kt", "package alpha\nclass AlphaEngine {\n    fun start() {}\n}\n")
        write("alpha/Use.kt", "package alpha\nfun useAlpha() { AlphaEngine().start() }\n")
        write("beta/Engine.kt", "package beta\nclass BetaEngine {\n    fun start() {}\n}\n")
        write("beta/Use.kt", "package beta\nfun useBeta() { BetaEngine().start() }\n")
        return root
    }

    private fun ToolResult.text(): String = assertIs<ToolResult.Success>(this).output

    private fun ToolResult.reason(): String = assertIs<ToolResult.Failure>(this).reason

    @Test
    fun `RM-10 the tool is a normal-level file tool`() {
        val spec = RepoMapTool(listOf(project())).spec
        assertEquals("repo_map", spec.name)
        assertEquals(SecurityLevel.NORMAL, spec.securityLevel)
        assertEquals(PermissionCategory.FILES, spec.permissionCategory)
    }

    @Test
    fun `RM-10 a default call maps the project`() {
        val root = project()
        val out = RepoMapTool(listOf(root)).execute(emptyMap()).text()

        for (expected in listOf("alpha/Engine.kt:", "beta/Use.kt:", "class AlphaEngine", "fun useBeta")) assertContains(out, expected)
        assertContains(out, "2: class AlphaEngine")
    }

    @Test
    fun `RM-10 the task decides which part of the project comes first`() {
        val tool = RepoMapTool(listOf(project()))

        val alpha = tool.execute(mapOf("task" to "rework the alpha engine")).text()
        val beta = tool.execute(mapOf("task" to "the beta engine crashes")).text()

        assertTrue(alpha.indexOf("alpha/") < alpha.indexOf("beta/"), alpha)
        assertTrue(beta.indexOf("beta/") < beta.indexOf("alpha/"), beta)
    }

    @Test
    fun `RM-10 focus files are honoured and marked`() {
        val out = RepoMapTool(listOf(project())).execute(mapOf("focus_files" to "beta/Use.kt")).text()

        assertTrue(out.indexOf("beta/") < out.indexOf("alpha/"), out)
        assertContains(out, "beta/Use.kt (focus):")
    }

    @Test
    fun `RM-10 unknown focus files are reported rather than ignored`() {
        val out = RepoMapTool(listOf(project())).execute(mapOf("focus_files" to "alpha/Use.kt, nope.kt")).text()
        assertContains(out, "focus_files not found in the index: nope.kt")
        assertFalse("alpha/Use.kt," in out.substringAfter("not found in the index"))
    }

    @Test
    fun `RM-10 the map respects the budget`() {
        val root = project()
        for (i in 1..40) root.resolve("alpha/Extra$i.kt").writeText("class Extra$i {\n fun a$i() {}\n fun b$i() {}\n}\n")

        for (budget in listOf(200, 350, 1000, 3000)) {
            val out = RepoMapTool(listOf(root)).execute(mapOf("budget_chars" to "$budget")).text()
            val map = out.substringBefore("\n\n[")
            assertTrue(map.length <= budget, "budget $budget produced ${map.length}")
        }
    }

    @Test
    fun `RM-10 a truncated map says files were left out`() {
        val root = project()
        for (i in 1..40) root.resolve("alpha/Extra$i.kt").writeText("class Extra$i")

        val out = RepoMapTool(listOf(root)).execute(mapOf("budget_chars" to "400")).text()

        assertContains(out, "more file(s) not shown")
    }

    @Test
    fun `RM-10 bad budgets fail with a clear reason`() {
        val tool = RepoMapTool(listOf(project()))
        for (bad in listOf("abc", "199", "200001", "-5", "1.5")) {
            assertContains(tool.execute(mapOf("budget_chars" to bad)).reason(), "budget_chars")
        }
    }

    @Test
    fun `RM-10 roots outside authorization fail`() {
        val other = createTempDirectory("repomap-other").toRealPath()
        val reason = RepoMapTool(listOf(project())).execute(mapOf("root" to other.toString())).reason()
        assertContains(reason, "outside every authorized root")
    }

    @Test
    fun `RM-10 with nothing authorized the tool refuses`() {
        assertContains(RepoMapTool(emptyList()).execute(emptyMap()).reason(), "No authorized root")
    }

    @Test
    fun `RM-10 an empty project says so`() {
        val root = createTempDirectory("repomap-empty").toRealPath()
        assertContains(RepoMapTool(listOf(root)).execute(emptyMap()).text(), "no supported source files")
    }

    @Test
    fun `RM-10 a budget too small for any file block is reported`() {
        val root = createTempDirectory("repomap-long").toRealPath()
        val deep = "d".repeat(150) + "/" + "e".repeat(80)
        Files.createDirectories(root.resolve(deep))
        root.resolve("$deep/X.kt").writeText("class X")

        assertContains(RepoMapTool(listOf(root)).execute(mapOf("budget_chars" to "200")).text(), "too small")
    }

    @Test
    fun `RM-10 repeat calls reuse the parse cache`() {
        val root = project()
        val indexer = RepoIndexer(listOf(root))
        val tool = RepoMapTool(listOf(root), indexer)

        tool.execute(emptyMap())
        val afterFirst = indexer.parsedFileCount
        tool.execute(mapOf("task" to "alpha"))

        assertEquals(4, afterFirst)
        assertEquals(afterFirst, indexer.parsedFileCount)
    }
}
