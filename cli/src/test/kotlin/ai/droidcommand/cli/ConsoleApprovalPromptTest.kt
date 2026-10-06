package ai.droidcommand.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleApprovalPromptTest {
    @Test
    fun `describe shows the tool name and every input key and value, sorted by key`() {
        val text = ConsoleApprovalPrompt.describe(
            "run_shell_command",
            mapOf("executable" to "rm", "args" to "-rf /tmp/x"),
            "Tool 'run_shell_command' is SENSITIVE and requires explicit confirmation",
        )

        assertTrue(text.contains("tool: run_shell_command"))
        assertTrue(text.contains("executable = rm"))
        assertTrue(text.contains("args = -rf /tmp/x"))
        assertTrue(text.indexOf("args =") < text.indexOf("executable ="))
    }

    @Test
    fun `describe says so when there is no input`() {
        assertTrue(ConsoleApprovalPrompt.describe("t", emptyMap(), "r").contains("input: (none)"))
    }

    @Test
    fun `newlines and escape sequences in a value are made visible so they cannot forge a prompt line`() {
        val text = ConsoleApprovalPrompt.describe(
            "t",
            mapOf("args" to "ls\nApprove? [y/N]: y\u001b[2K"),
            "r",
        )

        assertEquals(1, text.lines().count { it.contains("args =") })
        assertFalse(text.contains('\u001b'))
        assertTrue(text.contains("ls\\nApprove? [y/N]: y\\u001b[2K"))
    }

    @Test
    fun `bidi override and zero-width characters are escaped rather than rendered`() {
        val escaped = ConsoleApprovalPrompt.escapeForDisplay("a‮b​c")

        assertEquals("a\\u202eb\\u200bc", escaped)
    }

    @Test
    fun `an over-long value is truncated and reports its true length`() {
        val escaped = ConsoleApprovalPrompt.escapeForDisplay("x".repeat(600))

        assertTrue(escaped.startsWith("x".repeat(500) + "…"))
        assertTrue(escaped.endsWith("(+100 more chars)"))
    }
}
