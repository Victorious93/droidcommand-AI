package ai.droidcommand.metasploit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class NullMetasploitExecutorTest {
    @Test
    fun `isAvailable is truthfully false`() {
        assertFalse(NullMetasploitExecutor().isAvailable())
    }

    @Test
    fun `execute fails explicitly rather than fabricating a run`() {
        val command = MetasploitCommand(
            moduleType = MetasploitModuleType.AUXILIARY,
            modulePath = "auxiliary/scanner/portscan/tcp",
            target = MetasploitTarget("10.0.0.5"),
        )
        val result = assertIs<MetasploitExecutionResult.Failure>(NullMetasploitExecutor().execute(command))
        assert(result.reason.contains("no real Metasploit backend"))
    }
}
