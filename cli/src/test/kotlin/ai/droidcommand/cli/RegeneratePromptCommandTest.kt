package ai.droidcommand.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegeneratePromptCommandTest {
    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exitCode = try {
            block()
        } finally {
            System.setOut(original)
        }
        return exitCode to buffer.toString()
    }

    @Test
    fun `no arguments fails with a usage message`() {
        val exitCode = runRegeneratePrompt(emptyList())
        assertEquals(1, exitCode)
    }

    @Test
    fun `only flags with no raw input fails cleanly`() {
        val exitCode = runRegeneratePrompt(listOf("--target", "claude"))
        assertEquals(1, exitCode)
    }

    @Test
    fun `an unknown target value fails cleanly`() {
        val exitCode = runRegeneratePrompt(listOf("do", "something", "--target", "not-a-real-model"))
        assertEquals(1, exitCode)
    }

    @Test
    fun `a missing previous-response-file fails cleanly, not with a stack trace`() {
        val exitCode = runRegeneratePrompt(listOf("do", "something", "--previous-response-file", "/nonexistent/path/xyz"))
        assertEquals(1, exitCode)
    }

    @Test
    fun `basic input against a repo with no markers succeeds and prints all seven sections`() {
        val dir = Files.createTempDirectory("regen-cli-test")
        val (exitCode, output) = captureStdout {
            runRegeneratePrompt(listOf("Add", "a", "caching", "layer", "--repo", dir.toString(), "--target", "claude"))
        }

        assertEquals(0, exitCode)
        assertTrue(output.contains("=== Optimized Prompt ==="))
        assertTrue(output.contains("=== Target Model ==="))
        assertTrue(output.contains("=== Recovered Intent ==="))
        assertTrue(output.contains("=== Context Used ==="))
        assertTrue(output.contains("=== Assumptions ==="))
        assertTrue(output.contains("=== Missing Information ==="))
        assertTrue(output.contains("=== Acceptance Criteria ==="))
        assertTrue(output.contains("CLAUDE_CODE"))
    }

    @Test
    fun `previous-response-file content routes through failure classification`() {
        val dir = Files.createTempDirectory("regen-cli-test")
        val responseFile = dir.resolve("previous-response.txt")
        responseFile.writeText("I don't have enough information to proceed.")

        val (exitCode, output) = captureStdout {
            runRegeneratePrompt(listOf("?", "--repo", dir.toString(), "--previous-response-file", responseFile.toString()))
        }

        assertEquals(0, exitCode)
        assertTrue(output.contains("MISSING_CONTEXT"))
    }

    @Test
    fun `adapt flag switches the printed target model`() {
        val dir = Files.createTempDirectory("regen-cli-test")
        val (exitCode, output) = captureStdout {
            runRegeneratePrompt(listOf("do", "something", "--repo", dir.toString(), "--target", "gpt", "--adapt", "codex"))
        }

        assertEquals(0, exitCode)
        assertTrue(output.contains("CODEX"))
    }
}
