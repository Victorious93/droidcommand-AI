package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ActionRiskClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolboxCommandsTest {
    @Test
    fun `system-changing commands are flagged and display exactly what runs`() {
        val c = ToolboxCommands.disable("com.example.app")
        assertTrue(c.changesSystem)
        assertEquals("pm disable-user --user 0 com.example.app", c.display)
        assertEquals(listOf("su", "-c", "pm disable-user --user 0 com.example.app"), c.argvFor(ToolboxBackend.ROOT))
        assertEquals("su -c 'pm disable-user --user 0 com.example.app'", c.displayFor(ToolboxBackend.ROOT))
        listOf(
            ToolboxCommands.uninstall("a.b"), ToolboxCommands.enable("a.b"), ToolboxCommands.clearData("a.b"),
            ToolboxCommands.forceStop("a.b"), ToolboxCommands.grant("a.b", "android.permission.CAMERA"),
            ToolboxCommands.revoke("a.b", "android.permission.CAMERA"), ToolboxCommands.install("/sdcard/x.apk"),
            ToolboxCommands.settingsPut(SettingsNamespace.GLOBAL, "adb_enabled", "1"), ToolboxCommands.terminal("id"),
            ToolboxCommands.backupApk("a.b", "/data/app/a.b/base.apk", "/sdcard/Download"),
        ).plus(RebootTarget.entries.map(ToolboxCommands::reboot)).forEach { assertTrue(it.changesSystem, it.title) }
    }

    @Test
    fun `read-only commands are not flagged`() {
        listOf(
            ToolboxCommands.listPackages(), ToolboxCommands.getprop(), ToolboxCommands.battery(), ToolboxCommands.storage(),
            ToolboxCommands.logcat("ActivityManager", "I"), ToolboxCommands.settingsGet(SettingsNamespace.SECURE, "android_id"),
            ToolboxCommands.listDir("/data"), ToolboxCommands.readFile("/system/build.prop"),
        ).forEach { assertFalse(it.changesSystem, it.title) }
    }

    @Test
    fun `reboot targets`() {
        assertEquals(listOf("reboot"), ToolboxCommands.reboot(RebootTarget.SYSTEM).argv)
        assertEquals(listOf("reboot", "recovery"), ToolboxCommands.reboot(RebootTarget.RECOVERY).argv)
        assertEquals(listOf("reboot", "bootloader"), ToolboxCommands.reboot(RebootTarget.BOOTLOADER).argv)
    }

    @Test
    fun `injection attempts are rejected`() {
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.uninstall("a.b; reboot") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.grant("a.b", "x;y") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.settingsGet(SettingsNamespace.GLOBAL, "a b") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.settingsPut(SettingsNamespace.GLOBAL, "k", "a\nb") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.listDir("relative") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.listDir("/data/../etc") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.logcat("tag;rm") }
        assertFailsWith<IllegalArgumentException> { ToolboxCommands.install("/sdcard/x.zip") }
    }

    @Test
    fun `quoting keeps a value as one argument under su`() {
        val c = ToolboxCommands.settingsPut(SettingsNamespace.SYSTEM, "k", "it's on")
        assertEquals("settings put system k 'it'\\''s on'", c.display)
    }

    @Test
    fun `parsers`() {
        assertEquals(listOf("a.b", "c.d"), ToolboxCommands.parsePackages("package:c.d\npackage:a.b\njunk"))
        assertEquals(listOf("/data/app/x/base.apk"), ToolboxCommands.parseApkPaths("package:/data/app/x/base.apk\n"))
        val perms = ToolboxCommands.parsePermissions("  android.permission.CAMERA: granted=true\n  android.permission.READ_SMS: granted=false, flags=[]")
        assertEquals(listOf("android.permission.CAMERA" to true, "android.permission.READ_SMS" to false), perms)
        assertEquals("E/a boom", ToolboxCommands.filterLog("I/x ok\nE/a boom", "BOOM"))
    }
}

class SystemToolboxToolTest {
    private val ran = mutableListOf<ToolboxCommand>()
    private val runner = ToolboxRunner {
        ran += it
        ToolboxRunResult(true, 0, "done")
    }

    @Test
    fun `gate defaults off and blocks everything`() {
        assertFalse(SystemToolboxTool.DEFAULT_AI_ENABLED)
        val tool = SystemToolboxTool(runner) { false }
        val r = tool.execute(mapOf("action" to "battery"))
        assertIs<ToolResult.Failure>(r)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `tool always needs confirmation and is never auto-approved`() {
        val tool = SystemToolboxTool(runner) { true }
        assertTrue(tool.spec.requiresConfirmation)
        assertNotNull(ActionRiskClassifier().hardGate(tool.spec))
    }

    @Test
    fun `terminal and reboot are not offered to the AI`() {
        val tool = SystemToolboxTool(runner) { true }
        assertIs<ToolResult.Failure>(tool.execute(mapOf("action" to "terminal", "line" to "id")))
        assertIs<ToolResult.Failure>(tool.execute(mapOf("action" to "reboot")))
        assertTrue(ran.isEmpty())
        assertIs<ToolResult.Success>(tool.execute(mapOf("action" to "force_stop", "package" to "a.b")))
        assertEquals("am force-stop a.b", ran.single().display)
    }
}
