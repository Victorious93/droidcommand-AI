package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PilotInstructionTest {
    @Test
    fun `parses a tool name and key value arguments`() {
        val parsed = PilotInstruction.parse("run_shell_command executable=echo text=a=b")
        val ok = assertIs<PilotParse.Ok>(parsed)
        assertEquals("run_shell_command", ok.toolName)
        assertEquals(mapOf("executable" to "echo", "text" to "a=b"), ok.input)
    }

    @Test
    fun `rejects an empty instruction, a leading pair, and a bare word argument`() {
        assertIs<PilotParse.Invalid>(PilotInstruction.parse("   "))
        assertIs<PilotParse.Invalid>(PilotInstruction.parse("executable=echo"))
        val bad = assertIs<PilotParse.Invalid>(PilotInstruction.parse("echo hello"))
        assertEquals("Invalid argument 'hello'. Use key=value.", bad.message)
    }
}
