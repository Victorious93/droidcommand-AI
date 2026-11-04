package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.PrivilegedShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeShell(var available: Boolean = true) : PrivilegedShell {
    val ran = mutableListOf<List<String>>()
    override fun isAvailable() = available
    override fun exec(argv: List<String>, timeoutMs: Long): PrivilegedResult {
        ran += argv
        return PrivilegedResult.Success(0, "package:com.example.app\npackage:org.demo", "", false)
    }
    override fun readDeviceState() = PrivilegedResult.Failure("unused")
    override fun pmGrant(packageName: String, permission: String) = PrivilegedResult.Failure("unused")
}

private fun ToolboxBuild.ok(): ToolboxCommand = (this as ToolboxBuild.Ok).command

class ToolboxCommandsTest {
    @Test
    fun `package actions build exact argv and are destructive`() {
        assertEquals(listOf("pm", "uninstall", "--user", "0", "com.example.app"), ToolboxCommands.uninstall("com.example.app").ok().argv)
        assertEquals(listOf("pm", "disable-user", "--user", "0", "com.example.app"), ToolboxCommands.disable("com.example.app").ok().argv)
        assertEquals(listOf("pm", "enable", "--user", "0", "com.example.app"), ToolboxCommands.enable("com.example.app").ok().argv)
        assertEquals(listOf("pm", "clear", "--user", "0", "com.example.app"), ToolboxCommands.clearData("com.example.app").ok().argv)
        assertEquals(listOf("am", "force-stop", "com.example.app"), ToolboxCommands.forceStop("com.example.app").ok().argv)
        listOf(
            ToolboxCommands.uninstall("a.b"), ToolboxCommands.disable("a.b"), ToolboxCommands.enable("a.b"),
            ToolboxCommands.clearData("a.b"), ToolboxCommands.forceStop("a.b"), ToolboxCommands.grant("a.b", "android.permission.CAMERA"),
            ToolboxCommands.putSetting(SettingsNamespace.GLOBAL, "adb_enabled", "1"), ToolboxCommands.shell("id"),
        ).forEach { assertTrue(it.ok().destructive) }
        assertTrue(ToolboxCommands.reboot(RebootTarget.RECOVERY).destructive)
    }

    @Test
    fun `injection attempts are rejected`() {
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.uninstall("com.example; reboot"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.grant("a.b", "x; rm -rf /"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.putSetting(SettingsNamespace.SECURE, "a b", "1"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.putSetting(SettingsNamespace.SECURE, "k", "1\nreboot"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.listDir("relative/path"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.listDir("/data\n/x"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.install("/sdcard/a.zip"))
        assertIs<ToolboxBuild.Rejected>(ToolboxCommands.shell("   "))
    }

    @Test
    fun `read-only actions are not destructive and reboot targets are exact`() {
        assertFalse(ToolboxCommands.listPackages(PackageFilter.USER).destructive)
        assertEquals(listOf("pm", "list", "packages", "-3"), ToolboxCommands.listPackages(PackageFilter.USER).argv)
        assertEquals(listOf("settings", "get", "secure", "android_id"), ToolboxCommands.getSetting(SettingsNamespace.SECURE, "android_id").ok().argv)
        assertEquals(listOf("reboot"), ToolboxCommands.reboot(RebootTarget.SYSTEM).argv)
        assertEquals(listOf("reboot", "bootloader"), ToolboxCommands.reboot(RebootTarget.BOOTLOADER).argv)
        assertEquals("*:W", ToolboxCommands.logcat(LogLevel.WARN, 999_999).argv.last())
        assertEquals("5000", ToolboxCommands.logcat(LogLevel.WARN, 999_999).argv[5])
        assertTrue(ToolboxCommands.listDir("/data").ok().rootOnly)
    }

    @Test
    fun `display is shell-quoted and root wraps in su -c`() {
        val cmd = ToolboxCommands.putSetting(SettingsNamespace.GLOBAL, "device_name", "Don's phone").ok()
        assertEquals("settings put global device_name 'Don'\\''s phone'", cmd.display)
        val root = RootToolboxBackend({ true }) { _, _ -> PrivilegedResult.Success(0, "", "", false) }
        assertEquals(listOf("su", "-c", cmd.display), root.suArgv(cmd))
    }

    @Test
    fun `backup copies the first apk path into the backup folder`() {
        val paths = ToolboxCommands.parseApkPaths("package:/data/app/x/base.apk\npackage:/data/app/x/split.apk\n")
        assertEquals(listOf("/data/app/x/base.apk", "/data/app/x/split.apk"), paths)
        val cmd = ToolboxCommands.backupApk("com.example.app", paths.first(), "/sdcard/Download/backup/").ok()
        assertEquals(listOf("cp", "/data/app/x/base.apk", "/sdcard/Download/backup/com.example.app.apk"), cmd.argv)
    }

    @Test
    fun `permission parser and logcat filter`() {
        val dump = """
            install permissions:
              android.permission.INTERNET: granted=true
            runtime permissions:
              android.permission.CAMERA: granted=false, flags=[ USER_SET ]
              android.permission.RECORD_AUDIO: granted=true
        """.trimIndent()
        val parsed = PermissionParser.parse(dump)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.INTERNET"), parsed.map { it.name })
        assertFalse(parsed.first().granted)
        assertTrue(parsed.first().runtime)
        assertEquals("b Error", LogcatFilter.apply("a info\nb Error\nc", "error"))
        assertNull(ToolboxCommands.pathProblem("/sdcard/x.apk"))
    }

    @Test
    fun `shizuku backend refuses root-only commands and runs others`() {
        val shell = FakeShell()
        val backend = ShizukuToolboxBackend(shell)
        assertIs<PrivilegedResult.Failure>(backend.run(ToolboxCommands.listDir("/data").ok()))
        assertTrue(shell.ran.isEmpty())
        backend.run(ToolboxCommands.forceStop("com.example.app").ok())
        assertEquals(listOf("am", "force-stop", "com.example.app"), shell.ran.single())
    }
}

class SystemToolboxToolTest {
    private val shell = FakeShell()

    @Test
    fun `gate off never asks and never runs`() {
        var asked = 0
        val tool = SystemToolboxTool(
            { ShizukuToolboxBackend(shell) }, { false },
            ApprovalPrompt {
                asked++
                true
            },
        )
        val r = tool.execute(mapOf("action" to "force_stop", "package" to "com.example.app"))
        assertIs<ToolResult.Failure>(r)
        assertEquals(0, asked)
        assertTrue(shell.ran.isEmpty())
    }

    @Test
    fun `gate on still asks every call with the exact command and no means nothing runs`() {
        val shown = mutableListOf<String>()
        var answer = false
        val prompt = object : ApprovalPrompt {
            override fun requestApproval(reason: String) = error("3-arg form must be used")
            override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
                shown += input.getValue("command")
                return answer
            }
        }
        val tool = SystemToolboxTool({ ShizukuToolboxBackend(shell) }, { true }, prompt)
        assertIs<ToolResult.Failure>(tool.execute(mapOf("action" to "force_stop", "package" to "com.example.app")))
        assertTrue(shell.ran.isEmpty())
        answer = true
        assertIs<ToolResult.Success>(tool.execute(mapOf("action" to "force_stop", "package" to "com.example.app")))
        assertIs<ToolResult.Success>(tool.execute(mapOf("action" to "force_stop", "package" to "com.example.app")))
        assertEquals(List(3) { "am force-stop com.example.app" }, shown)
        assertEquals(2, shell.ran.size)
    }

    @Test
    fun `assistant cannot reach shell or reboot`() {
        val tool = SystemToolboxTool({ ShizukuToolboxBackend(shell) }, { true }, ApprovalPrompt { true })
        assertIs<ToolResult.Failure>(tool.execute(mapOf("action" to "shell", "command" to "reboot")))
        assertIs<ToolResult.Failure>(tool.execute(mapOf("action" to "reboot")))
        assertTrue(shell.ran.isEmpty())
        assertTrue("shizuku" in SystemToolboxTool.NAME && "root" in SystemToolboxTool.NAME)
    }
}
