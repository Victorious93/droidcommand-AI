package ai.droidcommand.cli

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe
import ai.droidcommand.security.ApprovalPrompt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives `device-terminal` and `run_termux_command` against a scripted `adb`, run as a real subprocess. */
class TerminalCommandsTest {
    private val dir = createTempDirectory("device-terminal").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A fake `adb`: answers get-state, pm list packages and dumpsys; anything else (such as su) fails. */
    private fun adb(connected: Boolean, installed: Boolean, versionName: String? = null): String {
        val script = File(dir, "adb")
        script.writeText(
            """
            #!/bin/sh
            [ "${'$'}1" = "-s" ] && shift 2
            if [ "${'$'}1" = "get-state" ]; then
              ${if (connected) "echo device; exit 0" else "exit 1"}
            fi
            if [ "${'$'}1" = "shell" ] && [ "${'$'}2" = "pm" ]; then
              ${if (installed) "echo package:com.termux" else ":"}
              exit 0
            fi
            if [ "${'$'}1" = "shell" ] && [ "${'$'}2" = "dumpsys" ]; then
              ${if (installed && versionName != null) "echo '    versionName=$versionName'" else ":"}
              exit 0
            fi
            exit 1
            """.trimIndent(),
        )
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun capture(block: () -> Int): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val code = try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `no connected phone is reported`() {
        val (code, _, err) = capture { runDeviceTerminal(listOf("--adb", adb(connected = false, installed = false))) }
        assertEquals(1, code)
        assertTrue(err.contains("No authorized adb device connected"), err)
    }

    @Test
    fun `a phone without Termux or VictorSuite falls back to the built-in terminal`() {
        val (code, out, _) = capture { runDeviceTerminal(listOf("--adb", adb(connected = true, installed = false))) }
        assertEquals(1, code)
        assertTrue(out.contains("Neither Termux nor VictorSuite is installed"), out)
        assertTrue(out.contains("isn't available over adb yet"), out)
    }

    @Test
    fun `an installed VictorSuite that can't run commands is named and explained`() {
        val (code, out, _) = capture {
            runDeviceTerminal(listOf("--adb", adb(connected = true, installed = true, versionName = "0.118.3.54"), "--serial", "abc123"))
        }
        assertEquals(1, code)
        assertTrue(out.contains("VictorSuite 0.118.3.54 is installed but can't run commands"), out)
    }

    @Test
    fun `adb access is off unless explicitly enabled`() {
        assertNull(AdbDeviceConfig.fromEnvironment { null })
        assertNull(AdbDeviceConfig.fromEnvironment { if (it == ADB_ENV) "no" else null })
        val env = mapOf(ADB_ENV to "true", ADB_SERIAL_ENV to "abc123", ADB_PATH_ENV to "/opt/adb")
        assertEquals(AdbDeviceConfig("/opt/adb", "abc123"), AdbDeviceConfig.fromEnvironment { env[it] })
        assertEquals(AdbDeviceConfig(), AdbDeviceConfig.fromEnvironment { if (it == ADB_ENV) "1" else null })
    }

    @Test
    fun `run_termux_command reaches the phone only when adb is enabled, and still needs approval`() {
        var asked = 0
        val approve = ApprovalPrompt {
            asked++
            true
        }
        val offline = buildSession(approve, device = null).session.runPilotInstruction("run_termux_command", mapOf("executable" to "ls"))
        assertIs<ToolResult.Failure>(offline)

        val connectedButNoTermux = AdbDeviceConfig(adb(connected = true, installed = false))
        val result = buildSession(approve, device = connectedButNoTermux).session
            .runPilotInstruction("run_termux_command", mapOf("executable" to "ls"))
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.describe().contains("is not installed on the connected device"), result.describe())
        assertEquals(2, asked, "each call went through the approval prompt first")
    }
}
