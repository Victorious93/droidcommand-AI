package ai.droidcommand.shizuku

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingShell(
    private val available: Boolean = true,
    private val result: PrivilegedResult = PrivilegedResult.Success(0, "ok", "", false),
) : PrivilegedShell {
    var lastExec: List<String>? = null
    var lastTimeout: Long? = null
    var lastGrant: Pair<String, String>? = null
    var deviceStateCalls = 0

    override fun isAvailable(): Boolean = available

    override fun exec(argv: List<String>, timeoutMs: Long): PrivilegedResult {
        lastExec = argv
        lastTimeout = timeoutMs
        return result
    }

    override fun readDeviceState(): PrivilegedResult {
        deviceStateCalls++
        return result
    }

    override fun pmGrant(packageName: String, permission: String): PrivilegedResult {
        lastGrant = packageName to permission
        return result
    }
}

class ShizukuToolTest {
    @Test
    fun `every kind is SENSITIVE DEVICE_CONTROL with shizuku in the name`() {
        ShizukuTool.all(NullPrivilegedShell()).forEach { tool ->
            assertEquals(false, tool.spec.requiresRoot)
            assertEquals(SecurityLevel.SENSITIVE, tool.spec.securityLevel)
            assertEquals(PermissionCategory.DEVICE_CONTROL, tool.spec.permissionCategory)
            assertTrue(tool.spec.requiresConfirmation)
            assertTrue("shizuku" in tool.spec.name)
        }
    }

    @Test
    fun `unavailable shell fails before any privileged op`() {
        val shell = RecordingShell(available = false)
        val result = ShizukuTool(shell, ShizukuToolKind.EXEC).execute(mapOf("executable" to "id"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, shell.lastExec)
    }

    @Test
    fun `exec parses argv and timeout`() {
        val shell = RecordingShell()
        val result = ShizukuTool(shell, ShizukuToolKind.EXEC).execute(
            mapOf("executable" to "pm", "args" to "list packages", "timeoutMs" to "5000", "onBehalfOf" to "ai.victorious.victorfiles"),
        )
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("pm", "list", "packages"), shell.lastExec)
        assertEquals(5_000L, shell.lastTimeout)
    }

    @Test
    fun `exec requires executable`() {
        val shell = RecordingShell()
        assertIs<ToolResult.Failure>(ShizukuTool(shell, ShizukuToolKind.EXEC).execute(emptyMap()))
        assertEquals(null, shell.lastExec)
    }

    @Test
    fun `device state ignores caller argv`() {
        val shell = RecordingShell()
        ShizukuTool(shell, ShizukuToolKind.DEVICE_STATE).execute(mapOf("executable" to "rm"))
        assertEquals(1, shell.deviceStateCalls)
        assertEquals(null, shell.lastExec)
    }

    @Test
    fun `pm grant forwards package and permission`() {
        val shell = RecordingShell()
        ShizukuTool(shell, ShizukuToolKind.PM_GRANT).execute(
            mapOf("package" to "ai.droidcommand.app", "permission" to "android.permission.INTERNET"),
        )
        assertEquals("ai.droidcommand.app" to "android.permission.INTERNET", shell.lastGrant)
    }

    @Test
    fun `non-zero exit and timeout become failures`() {
        val nonzero = RecordingShell(result = PrivilegedResult.Success(1, "", "denied", false))
        assertIs<ToolResult.Failure>(ShizukuTool(nonzero, ShizukuToolKind.EXEC).execute(mapOf("executable" to "id")))
        val timedOut = RecordingShell(result = PrivilegedResult.Success(143, "", "", true))
        assertIs<ToolResult.Failure>(ShizukuTool(timedOut, ShizukuToolKind.EXEC).execute(mapOf("executable" to "id")))
    }
}
