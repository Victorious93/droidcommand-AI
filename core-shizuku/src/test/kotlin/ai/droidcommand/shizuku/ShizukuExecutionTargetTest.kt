package ai.droidcommand.shizuku

import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.PrivilegeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TargetShell(
    private val available: Boolean,
    private val result: PrivilegedResult,
) : PrivilegedShell {
    var lastArgv: List<String>? = null

    override fun isAvailable(): Boolean = available

    override fun exec(argv: List<String>, timeoutMs: Long): PrivilegedResult {
        lastArgv = argv
        return result
    }

    override fun readDeviceState() = result

    override fun pmGrant(packageName: String, permission: String) = result
}

class ShizukuExecutionTargetTest {
    private fun context() = ExecutionContext(
        workingDir = "/",
        user = "shell",
        environment = emptyMap(),
        privilegeLevel = PrivilegeLevel.ELEVATED,
    )

    @Test
    fun `type is ANDROID and health follows the shell`() {
        val healthy = ShizukuExecutionTarget("shizuku", context(), TargetShell(true, PrivilegedResult.Success(0, "", "", false)))
        val down = ShizukuExecutionTarget("shizuku", context(), TargetShell(false, PrivilegedResult.Success(0, "", "", false)))
        assertEquals(ExecutionTargetType.ANDROID, healthy.type)
        assertTrue(healthy.isHealthy())
        assertFalse(down.isHealthy())
    }

    @Test
    fun `execute forwards argv and refuses workingDir and env`() {
        val shell = TargetShell(true, PrivilegedResult.Success(0, "ok", "", false))
        val target = ShizukuExecutionTarget("shizuku", context(), shell)
        val ok = target.execute(listOf("id"), workingDir = null, env = null, timeoutMs = 5_000)
        assertEquals(0, ok.exitCode)
        assertEquals(listOf("id"), shell.lastArgv)
        assertFalse(ok.verified)
        assertEquals(-1, target.execute(listOf("id"), workingDir = "/tmp", env = null, timeoutMs = 5_000).exitCode)
        assertEquals(-1, target.execute(listOf("id"), workingDir = null, env = mapOf("A" to "B"), timeoutMs = 5_000).exitCode)
    }
}
