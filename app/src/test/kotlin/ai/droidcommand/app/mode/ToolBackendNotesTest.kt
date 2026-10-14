package ai.droidcommand.app.mode

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolBackendNotesTest {
    @Test
    fun `null-backed tools name the executor that will refuse the run`() {
        assertTrue(ToolBackendNotes.forName("run_root_command")!!.contains("NullRootExecutor"))
        assertTrue(ToolBackendNotes.forName("run_termux_command")!!.contains("NullTermuxExecutor"))
        assertTrue(ToolBackendNotes.forName("run_metasploit_module")!!.contains("NullMetasploitExecutor"))
        assertTrue(ToolBackendNotes.forName("run_setoolkit_attack")!!.contains("NullSetExecutor"))
        assertTrue(ToolBackendNotes.forName("run_hackerai_agent_task")!!.contains("not bound"))
        assertTrue(ToolBackendNotes.forName("pentest_swarm_run_chain")!!.contains("not bound"))
    }

    @Test
    fun `unknown tools have no invented backend note`() {
        assertNull(ToolBackendNotes.forName("echo"))
        assertNull(ToolBackendNotes.forName(""))
    }
}
