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
 * Proves [AdbDeviceController]'s real read-only methods against a real, scripted `adb` shell script spawned
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

    private fun adbScript(xmlFixturePath: String, pngFixturePath: String): String = """
        #!/bin/sh
        case "${'$'}1" in
          exec-out)
            shift
            case "${'$'}*" in
              "screencap -p")
                cat "$pngFixturePath" ;;
              "cat '/sdcard/testfile.txt'")
                printf 'Hello, DroidCommand!' ;;
              "cat '/sdcard/missing.txt'")
                echo "cat: /sdcard/missing.txt: No such file or directory" 1>&2
                exit 1 ;;
              "ls -1Ap '/sdcard/testdir/'")
                printf 'Documents/\nnotes.txt\nmy file.txt\nlink-to-dir\n.hidden\n' ;;
              "cd '/sdcard/testdir/' && stat -L -c '%F|%s|%n' --"*)
                echo "regular file|1234|notes.txt"
                echo "regular file|7|my file.txt"
                echo "directory|4096|link-to-dir"
                echo "stat: can't stat '.hidden': Permission denied" 1>&2
                exit 1 ;;
              "content query --uri content://com.android.contacts/data/phones --projection contact_id:display_name:data1")
                echo "Row: 0 contact_id=1, display_name=Alice Smith, data1=+1 555-0100"
                echo "Row: 1 contact_id=1, display_name=Alice Smith, data1=+1 555-0101"
                echo "Row: 2 contact_id=2, display_name=Bob, Jr., data1=0044 20 7946 0000"
                echo "Row: 3 contact_id=3, display_name=NULL, data1=555-0199"
                echo "Row: 4 contact_id=4, display_name=Alice Smith, data1=+1 555-0200"
                echo "Row: 5 contact_id=5, display_name=No Number, data1=NULL" ;;
              "cat '/sdcard/it'\''s a file.txt'")
                printf 'quoted ok' ;;
              "ls -1Ap '/sdcard/emptydir/'")
                : ;;
              "ls -1Ap '/sdcard/nope/'")
                echo "ls: /sdcard/nope/: No such file or directory" 1>&2
                exit 1 ;;
              "ls -1Ap 'it'\''s here/'")
                printf 'a.txt\n' ;;
              "cd 'it'\''s here/' && stat -L -c '%F|%s|%n' -- 'a.txt'")
                echo "regular file|3|a.txt" ;;
              *)
                exit 1 ;;
            esac
            exit 0
            ;;
          shell)
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
              "dumpsys notification")
                echo "Current Notification Manager state:"
                echo "  NotificationRecord{0x1a2b3c4d  pkg=com.example.messenger  id=5  tag=null  userId=0}"
                echo "    some nested field"
                echo "  NotificationRecord{0x5e6f7a8b  pkg=com.google.android.gm  id=12  tag=inbox  userId=0}"
                echo "    some nested field"
                ;;
              "ip route get 1.1.1.1")
                echo "1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.42 uid 0"
                echo "    cache"
                ;;
              *)
                exit 1 ;;
            esac
            exit 0
            ;;
          *)
            exit 1
            ;;
        esac
        """.trimIndent()

    private fun newController(adbPath: String) = AdbDeviceController(
        shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(adbPath))),
        adbExecutable = adbPath,
    )

    /** A real PNG, encoded by the JDK's own javax.imageio — no hand-crafted byte fixture needed. */
    private fun writePngFixture(name: String, widthPx: Int, heightPx: Int): String {
        val file = File(tempDir, name)
        val image = java.awt.image.BufferedImage(widthPx, heightPx, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(image, "png", file)
        return file.absolutePath
    }

    private fun newController(): AdbDeviceController {
        val xmlPath = File(tempDir, "window_dump.xml").apply { writeText(uiTreeXml) }.absolutePath
        val pngPath = writePngFixture("screenshot-fixture.png", widthPx = 4, heightPx = 3)
        return newController(writeScript("adb", adbScript(xmlPath, pngPath)))
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
    fun `takeScreenshot saves a real PNG captured via adb exec-out and reports its true dimensions`() {
        val result = assertIs<ScreenshotResult.Success>(newController().takeScreenshot())

        assertEquals(4, result.widthPx)
        assertEquals(3, result.heightPx)
        val savedFile = File(result.path)
        assertTrue(savedFile.exists())
        assertEquals(result.sizeBytes, savedFile.length())
        // Real, decodable PNG bytes came through the pipe unmangled — proving executeBinary's raw
        // capture, not just that some bytes arrived: execute()'s text-oriented capture would have
        // corrupted this (PNG bytes are not valid UTF-8) and javax.imageio would fail to decode it.
        assertTrue(javax.imageio.ImageIO.read(savedFile) != null)
        savedFile.delete() // this test's own responsibility: takeScreenshot() saves outside tempDir by design
    }

    @Test
    fun `takeScreenshot fails cleanly when adb cannot be started`() {
        val result = assertIs<ScreenshotResult.Failure>(
            AdbDeviceController(
                shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(File(tempDir, "no-such-adb").absolutePath))),
                adbExecutable = File(tempDir, "no-such-adb").absolutePath,
            ).takeScreenshot(),
        )
        assertTrue(result.reason.contains("Cannot take a screenshot"))
    }

    @Test
    fun `takeScreenshot fails cleanly when adb exec-out exits non-zero`() {
        val adbPath = writeScript(
            "adb-screenshot-fails",
            """
            #!/bin/sh
            echo "no devices/emulators found" 1>&2
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<ScreenshotResult.Failure>(newController(adbPath).takeScreenshot())
        assertTrue(result.reason.contains("exited 1"))
    }

    @Test
    fun `takeScreenshot fails cleanly when the captured output is not a valid PNG`() {
        val adbPath = writeScript(
            "adb-screenshot-garbage",
            """
            #!/bin/sh
            printf 'not a png'
            exit 0
            """.trimIndent(),
        )
        val result = assertIs<ScreenshotResult.Failure>(newController(adbPath).takeScreenshot())
        assertTrue(result.reason.contains("not a valid PNG"))
    }

    @Test
    fun `readFile reads real content via adb exec-out cat`() {
        val result = assertIs<FileReadResult.Success>(newController().readFile("/sdcard/testfile.txt"))
        assertEquals("Hello, DroidCommand!", result.content)
    }

    @Test
    fun `readFile single-quotes a path containing spaces and a single quote for the device shell`() {
        val result = assertIs<FileReadResult.Success>(newController().readFile("/sdcard/it's a file.txt"))
        assertEquals("quoted ok", result.content)
    }

    @Test
    fun `readFile fails cleanly when the remote file does not exist`() {
        val result = assertIs<FileReadResult.Failure>(newController().readFile("/sdcard/missing.txt"))
        assertTrue(result.reason.contains("No such file or directory"))
    }

    @Test
    fun `readFile fails cleanly when adb cannot be started`() {
        val result = assertIs<FileReadResult.Failure>(
            AdbDeviceController(
                shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(File(tempDir, "no-such-adb").absolutePath))),
                adbExecutable = File(tempDir, "no-such-adb").absolutePath,
            ).readFile("/sdcard/testfile.txt"),
        )
        assertTrue(result.reason.contains("Cannot read file"))
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
            device.sendBroadcast("com.example.TEST", null, emptyMap()),
        )
        for (result in results) {
            val failure = assertIs<DeviceActionResult.Failure>(result)
            assertTrue(failure.reason.contains("not yet implemented"))
        }

        assertIs<ClipboardReadResult.Failure>(device.getClipboardText()).also { assertTrue(it.reason.contains("not yet implemented")) }

        assertTrue(!markerFile.exists(), "an unimplemented method must never invoke adb at all")
    }

    @Test
    fun `listNotifications parses real dumpsys notification output into NotificationSummary records`() {
        val result = assertIs<NotificationsResult.Success>(newController().listNotifications())
        assertEquals(2, result.notifications.size)
        assertEquals(NotificationSummary(packageName = "com.example.messenger", id = 5, tag = null), result.notifications[0])
        assertEquals(NotificationSummary(packageName = "com.google.android.gm", id = 12, tag = "inbox"), result.notifications[1])
    }

    @Test
    fun `listNotifications returns empty list when dumpsys has no NotificationRecord lines`() {
        val adbPath = writeScript(
            "adb-no-notifs",
            """
            #!/bin/sh
            echo "Current Notification Manager state:"
            echo "  mNotificationList:"
            exit 0
            """.trimIndent(),
        )
        val result = assertIs<NotificationsResult.Success>(newController(adbPath).listNotifications())
        assertEquals(emptyList(), result.notifications)
    }

    @Test
    fun `listNotifications fails cleanly when adb fails`() {
        val adbPath = writeScript(
            "adb-notif-fails",
            """
            #!/bin/sh
            exit 1
            """.trimIndent(),
        )
        assertIs<NotificationsResult.Failure>(newController(adbPath).listNotifications())
    }

    @Test
    fun `getNetworkState reports wifi connected from a real ip route get interface`() {
        val result = assertIs<NetworkStateResult.Success>(newController().getNetworkState())
        assertEquals(NetworkState(type = NetworkType.WIFI, isConnected = true), result.state)
    }

    @Test
    fun `getNetworkState reports cellular connected for a non-wlan, non-eth interface`() {
        val adbPath = writeScript(
            "adb-cellular",
            """
            #!/bin/sh
            echo "1.1.1.1 via 10.0.0.1 dev rmnet_data0 src 10.20.30.40 uid 0"
            exit 0
            """.trimIndent(),
        )
        val result = assertIs<NetworkStateResult.Success>(newController(adbPath).getNetworkState())
        assertEquals(NetworkState(type = NetworkType.CELLULAR, isConnected = true), result.state)
    }

    @Test
    fun `getNetworkState reports ethernet connected for an eth interface`() {
        val adbPath = writeScript(
            "adb-ethernet",
            """
            #!/bin/sh
            echo "1.1.1.1 via 192.168.0.1 dev eth0 src 192.168.0.5 uid 0"
            exit 0
            """.trimIndent(),
        )
        val result = assertIs<NetworkStateResult.Success>(newController(adbPath).getNetworkState())
        assertEquals(NetworkState(type = NetworkType.ETHERNET, isConnected = true), result.state)
    }

    @Test
    fun `getNetworkState reports a real disconnected result when the kernel has no route, not a failure`() {
        val adbPath = writeScript(
            "adb-no-route",
            """
            #!/bin/sh
            echo "RTNETLINK answers: Network is unreachable" >&2
            exit 2
            """.trimIndent(),
        )
        val result = assertIs<NetworkStateResult.Success>(newController(adbPath).getNetworkState())
        assertEquals(NetworkState(type = NetworkType.NONE, isConnected = false), result.state)
    }

    @Test
    fun `getNetworkState fails cleanly on an unrecognized non-zero exit, distinct from a real no-route result`() {
        val adbPath = writeScript(
            "adb-route-error",
            """
            #!/bin/sh
            echo "some other routing error" >&2
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<NetworkStateResult.Failure>(newController(adbPath).getNetworkState())
        assertTrue(result.reason.contains("some other routing error"))
    }

    @Test
    fun `getNetworkState fails cleanly when adb cannot be started`() {
        val result = assertIs<NetworkStateResult.Failure>(
            AdbDeviceController(
                shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(File(tempDir, "no-such-adb").absolutePath))),
                adbExecutable = File(tempDir, "no-such-adb").absolutePath,
            ).getNetworkState(),
        )
        assertTrue(result.reason.contains("ip route get"))
    }

    @Test
    fun `listDirectory parses ls -1Ap names, marks directories, and enriches sizes from stat`() {
        val result = assertIs<FileListResult.Success>(newController().listDirectory("/sdcard/testdir"))
        assertEquals(
            listOf(
                FileEntry(name = "Documents", path = "/sdcard/testdir/Documents", isDirectory = true, sizeBytes = null),
                FileEntry(name = "notes.txt", path = "/sdcard/testdir/notes.txt", isDirectory = false, sizeBytes = 1234L),
                FileEntry(name = "my file.txt", path = "/sdcard/testdir/my file.txt", isDirectory = false, sizeBytes = 7L),
                // stat -L reveals a symlink-to-directory that ls -p alone does not mark with a slash
                FileEntry(name = "link-to-dir", path = "/sdcard/testdir/link-to-dir", isDirectory = true, sizeBytes = null),
                // stat could not read this one: still listed, size honestly unknown
                FileEntry(name = ".hidden", path = "/sdcard/testdir/.hidden", isDirectory = false, sizeBytes = null),
            ),
            result.entries,
        )
    }

    @Test
    fun `listDirectory accepts a path that already ends in a slash`() {
        val result = assertIs<FileListResult.Success>(newController().listDirectory("/sdcard/testdir/"))
        assertEquals(5, result.entries.size)
        assertEquals("/sdcard/testdir/Documents", result.entries[0].path)
    }

    @Test
    fun `listDirectory returns an empty listing for an empty directory`() {
        val result = assertIs<FileListResult.Success>(newController().listDirectory("/sdcard/emptydir"))
        assertEquals(emptyList(), result.entries)
    }

    @Test
    fun `listDirectory single-quotes a path containing a quote for the device shell`() {
        val result = assertIs<FileListResult.Success>(newController().listDirectory("it's here"))
        assertEquals(listOf(FileEntry(name = "a.txt", path = "it's here/a.txt", isDirectory = false, sizeBytes = 3L)), result.entries)
    }

    @Test
    fun `listDirectory keeps names usable when stat is unavailable, with unknown sizes`() {
        val adbPath = writeScript(
            "adb-ls-no-stat",
            """
            #!/bin/sh
            case "${'$'}*" in
              "exec-out ls -1Ap '/d/'") printf 'sub/\r\nf.bin\r\n' ;;
              *) echo "stat: not found" 1>&2; exit 127 ;;
            esac
            """.trimIndent(),
        )
        val result = assertIs<FileListResult.Success>(newController(adbPath).listDirectory("/d"))
        assertEquals(
            listOf(
                FileEntry(name = "sub", path = "/d/sub", isDirectory = true, sizeBytes = null),
                FileEntry(name = "f.bin", path = "/d/f.bin", isDirectory = false, sizeBytes = null),
            ),
            result.entries,
        )
    }

    @Test
    fun `listDirectory lists the filesystem root without a doubled slash`() {
        val adbPath = writeScript(
            "adb-ls-root",
            """
            #!/bin/sh
            case "${'$'}*" in
              "exec-out ls -1Ap '/'") printf 'sdcard/\n' ;;
              *) exit 1 ;;
            esac
            """.trimIndent(),
        )
        val result = assertIs<FileListResult.Success>(newController(adbPath).listDirectory("/"))
        assertEquals("/sdcard", result.entries.single().path)
    }

    @Test
    fun `listDirectory reports ls's own error when the directory does not exist`() {
        val result = assertIs<FileListResult.Failure>(newController().listDirectory("/sdcard/nope"))
        assertTrue(result.reason.contains("No such file or directory"))
    }

    @Test
    fun `listDirectory fails cleanly when adb cannot be started`() {
        val result = assertIs<FileListResult.Failure>(
            AdbDeviceController(
                shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(File(tempDir, "no-such-adb").absolutePath))),
                adbExecutable = File(tempDir, "no-such-adb").absolutePath,
            ).listDirectory("/sdcard"),
        )
        assertTrue(result.reason.contains("Cannot list directory"))
    }

    @Test
    fun `listContacts groups content query rows by contact id and tolerates commas and NULLs`() {
        val result = assertIs<ContactsResult.Success>(newController().listContacts())
        assertEquals(
            listOf(
                ContactEntry(name = "Alice Smith", phoneNumbers = listOf("+1 555-0100", "+1 555-0101")),
                ContactEntry(name = "Bob, Jr.", phoneNumbers = listOf("0044 20 7946 0000")),
                ContactEntry(name = "", phoneNumbers = listOf("555-0199")),
                // a different contact sharing a display name is not merged into the first Alice
                ContactEntry(name = "Alice Smith", phoneNumbers = listOf("+1 555-0200")),
            ),
            result.contacts,
        )
    }

    @Test
    fun `listContacts handles CRLF line endings and a value spanning lines`() {
        val adbPath = writeScript(
            "adb-contacts-crlf",
            """
            #!/bin/sh
            printf 'Row: 0 contact_id=7, display_name=Line\r\nBreak, data1=111\r\nRow: 1 contact_id=8, display_name=Zed, data1=222\r\n'
            """.trimIndent(),
        )
        val result = assertIs<ContactsResult.Success>(newController(adbPath).listContacts())
        assertEquals(
            listOf(
                ContactEntry(name = "Line\nBreak", phoneNumbers = listOf("111")),
                ContactEntry(name = "Zed", phoneNumbers = listOf("222")),
            ),
            result.contacts,
        )
    }

    @Test
    fun `listContacts returns an empty list when the provider has no rows`() {
        val adbPath = writeScript(
            "adb-contacts-empty",
            """
            #!/bin/sh
            echo "No result found."
            """.trimIndent(),
        )
        assertEquals(emptyList(), assertIs<ContactsResult.Success>(newController(adbPath).listContacts()).contacts)
    }

    @Test
    fun `listContacts fails when the provider reports an error even with exit code zero`() {
        val adbPath = writeScript(
            "adb-contacts-denied",
            """
            #!/bin/sh
            echo "Error while accessing provider:com.android.contacts"
            echo "java.lang.SecurityException: Permission Denial"
            """.trimIndent(),
        )
        val result = assertIs<ContactsResult.Failure>(newController(adbPath).listContacts())
        assertTrue(result.reason.contains("Error while accessing provider"))
    }

    @Test
    fun `listContacts fails cleanly on a non-zero exit and when adb cannot be started`() {
        val failing = writeScript(
            "adb-contacts-fails",
            """
            #!/bin/sh
            echo "device offline" 1>&2
            exit 1
            """.trimIndent(),
        )
        assertTrue(assertIs<ContactsResult.Failure>(newController(failing).listContacts()).reason.contains("device offline"))
        val missing = AdbDeviceController(
            shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(File(tempDir, "no-such-adb").absolutePath))),
            adbExecutable = File(tempDir, "no-such-adb").absolutePath,
        )
        assertTrue(assertIs<ContactsResult.Failure>(missing.listContacts()).reason.contains("Cannot list contacts"))
    }
}
