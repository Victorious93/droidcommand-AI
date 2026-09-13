package ai.droidcommand.tools.android

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A real, read-only [DeviceController] reaching a device over `adb shell` — the same PC-drives-a-
 * tethered-phone topology `core-root.AdbRootExecutor` uses for root execution, applied here to device
 * queries instead. [shell] is an already-configured [ShellExecutor] (e.g.
 * `ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("adb")))`) — this class
 * has no opinion on the security policy gating it, matching `core-build-local.LocalProcessBuildExecutor`'s
 * identical relationship to [ShellExecutor].
 *
 * **Deliberately implements only 5 of [DeviceController]'s 29 methods for real** —
 * [getDeviceInfo]/[getBatteryStatus]/[getStorageInfo]/[listInstalledApps]/[getUiTree] — every one
 * read-only and non-mutating. None of the other 24 (anything that taps, types, launches an app, sends
 * an SMS, makes a call, or writes/deletes a file) are implemented here: this device is a real personal
 * phone, and actually exercising a mutating action against it is not a call this class makes
 * unilaterally. Every unimplemented method fails with an explicit "not yet implemented" reason — a
 * **different** honest reason from [NullDeviceController]'s "no real device is connected," since a
 * device genuinely is connected in this topology; conflating "missing capability" with "missing
 * device" would misreport why the call failed. [takeScreenshot] is excluded for a different, technical
 * reason: it needs binary-safe stdout capture, and [ShellExecutor]'s line-oriented process execution
 * would corrupt a real PNG — a real `core-shell` capability gap, not a mutation-safety concern.
 */
class AdbDeviceController(
    private val shell: ShellExecutor,
    private val adbExecutable: String = "adb",
    private val serial: String? = null,
) : DeviceController {
    private fun notImplemented(action: String) =
        "$action is not yet implemented in AdbDeviceController — see docs/AUDIT_2026-09-05.md"

    private fun adbArgv(vararg args: String): List<String> =
        (if (serial != null) listOf(adbExecutable, "-s", serial) else listOf(adbExecutable)) + args

    private fun runAdb(vararg args: String): ShellExecutionResult {
        val argv = adbArgv(*args)
        return shell.execute(ShellCommand(executable = argv.first(), args = argv.drop(1)))
    }

    private fun successfulOutput(vararg args: String): String? {
        val result = runAdb(*args)
        return if (result is ShellExecutionResult.Success && result.exitCode == 0) result.stdout else null
    }

    override fun getDeviceInfo(): DeviceInfoResult {
        val manufacturer = successfulOutput("shell", "getprop", "ro.product.manufacturer")?.trim()?.ifBlank { null }
        val model = successfulOutput("shell", "getprop", "ro.product.model")?.trim()?.ifBlank { null }
        val osVersion = successfulOutput("shell", "getprop", "ro.build.version.release")?.trim()?.ifBlank { null }
        val sizeOutput = successfulOutput("shell", "wm", "size")
        val sizeMatch = sizeOutput?.let { WM_SIZE_PATTERN.find(it) }
        return DeviceInfoResult.Success(
            DeviceInfo(
                manufacturer = manufacturer,
                model = model,
                osVersion = osVersion,
                screenWidthPx = sizeMatch?.groupValues?.get(1)?.toIntOrNull(),
                screenHeightPx = sizeMatch?.groupValues?.get(2)?.toIntOrNull(),
            ),
        )
    }

    override fun getBatteryStatus(): BatteryStatusResult {
        val output = successfulOutput("shell", "dumpsys", "battery")
            ?: return BatteryStatusResult.Failure("Cannot read battery status: 'adb shell dumpsys battery' failed")
        val level = LEVEL_PATTERN.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: return BatteryStatusResult.Failure("Cannot read battery status: no 'level:' line in dumpsys battery output")
        val isCharging = POWERED_PATTERN.findAll(output).any { it.groupValues[1] == "true" }
        return BatteryStatusResult.Success(BatteryStatus(levelPercent = level, isCharging = isCharging))
    }

    override fun getStorageInfo(): StorageInfoResult {
        val output = successfulOutput("shell", "df", "/data")
            ?: return StorageInfoResult.Failure("Cannot read storage info: 'adb shell df /data' failed")
        val dataLine = output.lineSequence().drop(1).firstOrNull { it.isNotBlank() }
            ?: return StorageInfoResult.Failure("Cannot read storage info: no data row in df output")
        val columns = dataLine.trim().split(Regex("\\s+"))
        val totalBlocks = columns.getOrNull(1)?.toLongOrNull()
        val availableBlocks = columns.getOrNull(3)?.toLongOrNull()
        if (totalBlocks == null || availableBlocks == null) {
            return StorageInfoResult.Failure("Cannot read storage info: unexpected df output format: '$dataLine'")
        }
        return StorageInfoResult.Success(StorageInfo(totalBytes = totalBlocks * 1024L, freeBytes = availableBlocks * 1024L))
    }

    override fun listInstalledApps(): InstalledAppsResult {
        val output = successfulOutput("shell", "pm", "list", "packages", "-3")
            ?: return InstalledAppsResult.Failure("Cannot list installed apps: 'adb shell pm list packages -3' failed")
        val apps = output.lineSequence()
            .mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it.isNotBlank() && line.startsWith("package:") } }
            .map { packageName -> InstalledApp(packageName = packageName, appName = packageName, versionName = null) }
            .toList()
        return InstalledAppsResult.Success(apps)
    }

    /**
     * Dumps the real UI hierarchy to a device-side file, reads it back as text (avoiding `/dev/tty`
     * output-redirection quirks), best-effort removes the temp file, then parses the real uiautomator
     * XML with the JDK's built-in [DocumentBuilderFactory] — no new dependency.
     */
    override fun getUiTree(): UiTreeResult {
        val dumpPath = "/sdcard/window_dump.xml"
        val dumpResult = runAdb("shell", "uiautomator", "dump", dumpPath)
        if (dumpResult !is ShellExecutionResult.Success || dumpResult.exitCode != 0) {
            return UiTreeResult.Failure("Cannot read the UI tree: 'adb shell uiautomator dump' failed")
        }
        val xml = successfulOutput("shell", "cat", dumpPath)
            ?: return UiTreeResult.Failure("Cannot read the UI tree: failed to read back '$dumpPath'")
        runAdb("shell", "rm", dumpPath) // best-effort cleanup; failure here doesn't invalidate the tree we already read

        return try {
            val document = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }.newDocumentBuilder().parse(xml.byteInputStream())
            val hierarchyChildren = document.documentElement.childNodes
            val rootElement = (0 until hierarchyChildren.length)
                .map { hierarchyChildren.item(it) }
                .firstOrNull { it.nodeType == Node.ELEMENT_NODE } as? Element
                ?: return UiTreeResult.Failure("Cannot read the UI tree: no root <node> found under <hierarchy>")
            UiTreeResult.Success(UiTree(root = parseUiNode(rootElement), capturedAt = Instant.now()))
        } catch (e: Exception) {
            UiTreeResult.Failure("Cannot read the UI tree: failed to parse uiautomator XML: ${e.message}")
        }
    }

    private fun parseUiNode(element: Element): UiNode {
        val bounds = BOUNDS_PATTERN.find(element.getAttribute("bounds"))
        val children = (0 until element.childNodes.length)
            .map { element.childNodes.item(it) }
            .filterIsInstance<Element>()
            .map { parseUiNode(it) }
        return UiNode(
            className = element.getAttribute("class").ifBlank { null },
            text = element.getAttribute("text").ifBlank { null },
            contentDescription = element.getAttribute("content-desc").ifBlank { null },
            resourceId = element.getAttribute("resource-id").ifBlank { null },
            bounds = bounds?.let {
                Rect(
                    left = it.groupValues[1].toInt(),
                    top = it.groupValues[2].toInt(),
                    right = it.groupValues[3].toInt(),
                    bottom = it.groupValues[4].toInt(),
                )
            } ?: Rect(0, 0, 0, 0),
            clickable = element.getAttribute("clickable") == "true",
            enabled = element.getAttribute("enabled") != "false",
            focused = element.getAttribute("focused") == "true",
            checked = element.getAttribute("checkable").takeIf { it == "true" }?.let { element.getAttribute("checked") == "true" },
            children = children,
        )
    }

    private fun failure(action: String) = DeviceActionResult.Failure(notImplemented(action))

    override fun tap(x: Int, y: Int) = failure("tap")
    override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long) = failure("swipe")
    override fun typeText(text: String) = failure("type text")
    override fun pressKey(key: DeviceKey) = failure("press key")
    override fun launchApp(packageName: String) = failure("launch app")
    override fun takeScreenshot() = ScreenshotResult.Failure(notImplemented("take screenshot"))
    override fun readFile(path: String) = FileReadResult.Failure(notImplemented("read file"))
    override fun writeFile(path: String, content: String, append: Boolean) = failure("write file")
    override fun moveFile(fromPath: String, toPath: String) = failure("move file")
    override fun copyFile(fromPath: String, toPath: String) = failure("copy file")
    override fun deleteFile(path: String) = failure("delete file")
    override fun listDirectory(path: String) = FileListResult.Failure(notImplemented("list directory"))
    override fun getNetworkState() = NetworkStateResult.Failure(notImplemented("read network state"))
    override fun getClipboardText() = ClipboardReadResult.Failure(notImplemented("read clipboard"))
    override fun setClipboardText(text: String) = failure("set clipboard text")
    override fun sendSms(phoneNumber: String, message: String) = failure("send SMS")
    override fun makeCall(phoneNumber: String) = failure("make call")
    override fun listContacts() = ContactsResult.Failure(notImplemented("list contacts"))
    override fun createCalendarEvent(title: String, startEpochMillis: Long, endEpochMillis: Long) = failure("create calendar event")
    override fun setAlarm(hour: Int, minute: Int, label: String?) = failure("set alarm")
    override fun setTimer(durationSeconds: Long, label: String?) = failure("set timer")
    override fun setReminder(text: String, dueEpochMillis: Long) = failure("set reminder")
    override fun mediaPlayPause() = failure("toggle media play/pause")
    override fun mediaNext() = failure("skip to next media track")
    override fun mediaPrevious() = failure("skip to previous media track")
    override fun setVolume(levelPercent: Int) = failure("set volume")
    override fun launchNavigation(destination: String, mode: NavigationMode) = failure("launch navigation")

    private companion object {
        val WM_SIZE_PATTERN = Regex("""Physical size:\s*(\d+)x(\d+)""")
        val LEVEL_PATTERN = Regex("""level:\s*(-?\d+)""")
        val POWERED_PATTERN = Regex("""(?:AC|USB|Wireless) powered:\s*(true|false)""")
        val BOUNDS_PATTERN = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    }
}
