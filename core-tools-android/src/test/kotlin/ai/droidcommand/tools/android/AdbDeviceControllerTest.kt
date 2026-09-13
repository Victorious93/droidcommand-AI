package ai.droidcommand.tools.android

import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [AdbDeviceController]'s 5 real methods against a real, scripted `adb` shell script spawned
 * as a real subprocess (via a real [ProcessBuilderShellExecutor]) — mirroring
 * `core-root.AdbRootExecutorTest`'s "prove the boundary is real" convention. Real on-device
 * verification lives in `AdbDeviceControllerRealDeviceIntegrationTest`, which skips cleanly wherever
 * no real device is attached — including here.
 */
class AdbDeviceControllerTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("adb-device-controller-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeScript(name: String, content: String): String {
        val file = File(tempDir, name)
        file.writeText(content)
        file.setExecutable(true)
        return file.absolutePath
    }

    private val uiTreeXml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.launcher3" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">
            <node index="0" text="Hello" resource-id="com.example:id/label" class="android.widget.TextView" package="com.example" content-desc="Hello label" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[100,200][300,260]" />
            <node index="1" text="" resource-id="com.example:id/toggle" class="android.widget.Switch" package="com.example" content-desc="" checkable="true" checked="true" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[400,200][460,260]" />
          </node>
        </hierarchy>
    """.trimIndent()

    private fun adbScript(xmlFixturePath: String): String = """
        #!/bin/sh
        if [ "${'$'}1" != "shell" ]; then
          exit 1
        fi
        shift
        case "${'$'}*" in
          "getprop ro.product.manufacturer")
            echo "TestManufacturer" ;;
          "getprop ro.product.model")
            echo "TestModel" ;;
          "getprop ro.build.version.release")
            echo "16" ;;
          "wm size")
            echo "Physical size: 1080x2400" ;;
          "dumpsys battery")
            echo "Current Battery Service state:"
            echo "  AC powered: false"
            echo "  USB powered: true"
            echo "  Wireless powered: false"
            echo "  status: 2"
            echo "  level: 77"
            echo "  scale: 100"
            ;;
          "df /data")
            echo "Filesystem     1K-blocks    Used Available Use% Mounted on"
            echo "/dev/block/dm-7 111935132 45678900 62345678 43% /data"
            ;;
          "pm list packages -3")
            echo "package:com.example.foo"
            echo "package:com.example.bar"
            ;;
          "uiautomator dump /sdcard/window_dump.xml")
            echo "UI hierarchy dumped to: /sdcard/window_dump.xml" ;;
          "cat /sdcard/window_dump.xml")
            cat "$xmlFixturePath" ;;
          "rm /sdcard/window_dump.xml")
            : ;;
          *)
            exit 1 ;;
        esac
        exit 0
        """.trimIndent()

    private fun newController(adbPath: String) = AdbDeviceController(
        shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(adbPath))),
        adbExecutable = adbPath,
    )

    private fun newController(): AdbDeviceController {
        val xmlPath = File(tempDir, "window_dump.xml").apply { writeText(uiTreeXml) }.absolutePath
        return newController(writeScript("adb", adbScript(xmlPath)))
    }

    @Test
    fun `getDeviceInfo reads real manufacturer, model, os version, and screen size`() {
        val result = assertIs<DeviceInfoResult.Success>(newController().getDeviceInfo())
        assertEquals(DeviceInfo(manufacturer = "TestManufacturer", model = "TestModel", osVersion = "16", screenWidthPx = 1080, screenHeightPx = 2400), result.info)
    }

    @Test
    fun `getDeviceInfo leaves fields null when the underlying query fails, rather than fabricating them`() {
        val adbPath = writeScript(
            "adb-broken",
            """
            #!/bin/sh
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<DeviceInfoResult.Success>(newController(adbPath).getDeviceInfo())
        assertEquals(DeviceInfo(), result.info)
    }

    @Test
    fun `getBatteryStatus parses level and charging state from real dumpsys output`() {
        val result = assertIs<BatteryStatusResult.Success>(newController().getBatteryStatus())
        assertEquals(BatteryStatus(levelPercent = 77, isCharging = true), result.status)
    }

    @Test
    fun `getStorageInfo parses total and free bytes from real df output`() {
        val result = assertIs<StorageInfoResult.Success>(newController().getStorageInfo())
        assertEquals(111935132L * 1024, result.info.totalBytes)
        assertEquals(62345678L * 1024, result.info.freeBytes)
    }

    @Test
    fun `listInstalledApps parses real pm list packages output`() {
        val result = assertIs<InstalledAppsResult.Success>(newController().listInstalledApps())
        assertEquals(
            listOf(
                InstalledApp(packageName = "com.example.foo", appName = "com.example.foo", versionName = null),
                InstalledApp(packageName = "com.example.bar", appName = "com.example.bar", versionName = null),
            ),
            result.apps,
        )
    }

    @Test
    fun `getUiTree parses a real uiautomator dump into a UiNode tree`() {
        val result = assertIs<UiTreeResult.Success>(newController().getUiTree())
        val root = result.tree.root

        assertEquals("android.widget.FrameLayout", root.className)
        assertEquals(Rect(0, 0, 1080, 2400), root.bounds)
        assertEquals(2, root.children.size)

        val label = root.children[0]
        assertEquals("Hello", label.text)
        assertEquals("Hello label", label.contentDescription)
        assertEquals("com.example:id/label", label.resourceId)
        assertTrue(label.clickable)
        assertTrue(label.focused)
        assertNull(label.checked)
        assertEquals(Rect(100, 200, 300, 260), label.bounds)

        val toggle = root.children[1]
        assertEquals(true, toggle.checked)
    }

    @Test
    fun `getUiTree fails cleanly when adb cannot be started`() {
        val result = assertIs<UiTreeResult.Failure>(
            AdbDeviceController(
                shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(File(tempDir, "no-such-adb").absolutePath))),
                adbExecutable = File(tempDir, "no-such-adb").absolutePath,
            ).getUiTree(),
        )
        assertTrue(result.reason.contains("uiautomator dump"))
    }

    @Test
    fun `every unimplemented method fails with a distinct 'not yet implemented' reason, never touching adb`() {
        val markerFile = File(tempDir, "touched")
        val adbPath = writeScript(
            "adb-marker",
            """
            #!/bin/sh
            touch '${markerFile.absolutePath}'
            exit 1
            """.trimIndent(),
        )
        val device = newController(adbPath)

        val results = listOf(
            device.tap(0, 0),
            device.swipe(0, 0, 1, 1, 100),
            device.typeText("x"),
            device.pressKey(DeviceKey.BACK),
            device.launchApp("com.example"),
            device.writeFile("/x", "y", false),
            device.moveFile("/a", "/b"),
            device.copyFile("/a", "/b"),
            device.deleteFile("/x"),
            device.setClipboardText("x"),
            device.sendSms("123", "hi"),
            device.makeCall("123"),
            device.createCalendarEvent("t", 0, 1),
            device.setAlarm(1, 0, null),
            device.setTimer(10, null),
            device.setReminder("x", 0),
            device.mediaPlayPause(),
            device.mediaNext(),
            device.mediaPrevious(),
            device.setVolume(50),
            device.launchNavigation("home", NavigationMode.WALKING),
        )
        for (result in results) {
            val failure = assertIs<DeviceActionResult.Failure>(result)
            assertTrue(failure.reason.contains("not yet implemented"))
        }

        assertIs<ScreenshotResult.Failure>(device.takeScreenshot()).also { assertTrue(it.reason.contains("not yet implemented")) }
        assertIs<FileReadResult.Failure>(device.readFile("/x")).also { assertTrue(it.reason.contains("not yet implemented")) }
        assertIs<FileListResult.Failure>(device.listDirectory("/x")).also { assertTrue(it.reason.contains("not yet implemented")) }
        assertIs<NetworkStateResult.Failure>(device.getNetworkState()).also { assertTrue(it.reason.contains("not yet implemented")) }
        assertIs<ClipboardReadResult.Failure>(device.getClipboardText()).also { assertTrue(it.reason.contains("not yet implemented")) }
        assertIs<ContactsResult.Failure>(device.listContacts()).also { assertTrue(it.reason.contains("not yet implemented")) }

        assertTrue(!markerFile.exists(), "an unimplemented method must never invoke adb at all")
    }
}
