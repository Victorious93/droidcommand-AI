package ai.droidcommand.cli

import ai.droidcommand.metasploit.MetasploitCommand
import ai.droidcommand.metasploit.MetasploitExecutionResult
import ai.droidcommand.metasploit.MetasploitModuleType
import ai.droidcommand.metasploit.MetasploitTarget
import ai.droidcommand.metasploit.NullMetasploitExecutor
import ai.droidcommand.metasploit.ShellBackedMetasploitExecutor
import ai.droidcommand.setoolkit.NullSetExecutor
import ai.droidcommand.setoolkit.ProcessBackedSetExecutor
import ai.droidcommand.setoolkit.SetAttackVector
import ai.droidcommand.setoolkit.SetCommand
import ai.droidcommand.setoolkit.SetExecutionResult
import ai.droidcommand.setoolkit.SetTarget
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Assumes `DROIDCOMMAND_CLI_SHELL_ALLOWED_EXECUTABLES` is unset in the environment this test runs
 * in (true for a clean CI run, and the same assumption `MainTest`'s
 * "fails cleanly when no executable is allow-listed" case already relies on) — that lets a real
 * [ai.droidcommand.shell.ProcessBuilderShellExecutor]'s own denial message double as proof that a
 * *real* executor, not [NullMetasploitExecutor]/[NullSetExecutor], actually received the call.
 */
class SecurityToolCommandsTest {
    @Test
    fun `metasploitExecutorFor returns NullMetasploitExecutor when disabled`() {
        assertIs<NullMetasploitExecutor>(metasploitExecutorFor { null })
    }

    @Test
    fun `metasploitExecutorFor is disabled unless DROIDCOMMAND_CLI_METASPLOIT is exactly '1' or 'true'`() {
        assertIs<NullMetasploitExecutor>(metasploitExecutorFor { key -> if (key == METASPLOIT_ENV) "yes" else null })
    }

    @Test
    fun `metasploitExecutorFor threads the configured path through to a real executor`() {
        val env: (String) -> String? = { key ->
            when (key) {
                METASPLOIT_ENV -> "1"
                METASPLOIT_PATH_ENV -> "totally-not-a-real-binary-xyz"
                else -> null
            }
        }
        val executor = assertIs<ShellBackedMetasploitExecutor>(metasploitExecutorFor(env))
        val result = assertIs<MetasploitExecutionResult.Failure>(
            executor.execute(
                MetasploitCommand(
                    moduleType = MetasploitModuleType.AUXILIARY,
                    modulePath = "auxiliary/scanner/portscan/tcp",
                    target = MetasploitTarget("10.0.0.5"),
                ),
            ),
        )
        assertTrue(result.reason.contains("totally-not-a-real-binary-xyz"))
        assertTrue(result.reason.contains("not in the allowed executable list"))
    }

    @Test
    fun `metasploitExecutorFor defaults the path to msfconsole when enabled without one`() {
        val executor = assertIs<ShellBackedMetasploitExecutor>(
            metasploitExecutorFor { key -> if (key == METASPLOIT_ENV) "TRUE" else null },
        )
        val result = assertIs<MetasploitExecutionResult.Failure>(
            executor.execute(
                MetasploitCommand(
                    moduleType = MetasploitModuleType.AUXILIARY,
                    modulePath = "auxiliary/scanner/portscan/tcp",
                    target = MetasploitTarget("10.0.0.5"),
                ),
            ),
        )
        assertTrue(result.reason.contains("'msfconsole'"))
    }

    @Test
    fun `setExecutorFor returns NullSetExecutor when disabled`() {
        assertIs<NullSetExecutor>(setExecutorFor { null })
    }

    @Test
    fun `setExecutorFor threads the configured path through to a real executor`() {
        val env: (String) -> String? = { key ->
            when (key) {
                SETOOLKIT_ENV -> "1"
                SETOOLKIT_PATH_ENV -> "totally-not-a-real-binary-xyz"
                else -> null
            }
        }
        val executor = assertIs<ProcessBackedSetExecutor>(setExecutorFor(env))
        val result = assertIs<SetExecutionResult.Failure>(
            executor.execute(SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = "10.0.0.5"))),
        )
        assertTrue(result.reason.contains("totally-not-a-real-binary-xyz"))
        assertTrue(result.reason.contains("not in the allowed executable list"))
    }

    @Test
    fun `setExecutorFor defaults the path to setoolkit when enabled without one`() {
        val executor = assertIs<ProcessBackedSetExecutor>(
            setExecutorFor { key -> if (key == SETOOLKIT_ENV) "1" else null },
        )
        val result = assertIs<SetExecutionResult.Failure>(
            executor.execute(SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = "10.0.0.5"))),
        )
        assertTrue(result.reason.contains("'setoolkit'"))
    }
}
