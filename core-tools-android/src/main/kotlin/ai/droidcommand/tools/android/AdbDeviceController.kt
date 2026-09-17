package ai.droidcommand.tools.android

import ai.droidcommand.shell.ShellBinaryExecutionResult
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.nio.file.Files
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
 * **Deliberately implements only 6 of [DeviceController]'s 29 methods for real** —
 * [getDeviceInfo]/[getBatteryStatus]/[getStorageInfo]/[listInstalledApps]/[getUiTree]/[takeScreenshot] —
 * every one read-only and non-mutating. None of the other 23 (anything that taps, types, launches an
 * app, sends an SMS, makes a call, or writes/deletes a file) are implemented here: this device is a
 * real personal phone, and actually exercising a mutating action against it is not a call this class
 * makes unilaterally. Every unimplemented method fails with an explicit "not yet implemented" reason —
 * a **different** honest reason from [NullDeviceController]'s "no real device is connected," since a
 * device genuinely is connected in this topology; conflating "missing capability" with "missing
 * device" would misreport why the call failed. [takeScreenshot] was excluded for a separate, technical
 * reason until [ShellExecutor.executeBinary] existed: `adb exec-out screencap -p` writes a raw PNG to
 * stdout, and [ShellExecutor.execute]'s line-oriented text capture would have corrupted it — see
 * `docs/AUDIT_2026-09-05.md`'s "core-shell binary-safe process I/O" addendum for the `core-shell` side
 * of this fix.
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

    /**
     * Captures the real screen via `adb exec-out screencap -p` — `exec-out`, not `shell`, specifically
     * because it streams the command's raw stdout back over the adb data channel with no pty/line-ending
     * translation in between, the same reason [ShellExecutor.executeBinary] itself exists rather than
     * [ShellExecutor.execute]. The PNG is saved to a fresh JVM temp file (no caller-supplied output
     * directory to authorize, unlike `core-build.WorkspaceManager` — a single self-contained file, not a
     * tree this class would need to clean up or guard against escaping) and its real width/height are
     * read back out of the PNG's own IHDR chunk rather than a separate `wm size` call, which reports the
     * logical display size and isn't guaranteed to match a screenshot's actual pixel dimensions.
     */
    override fun takeScreenshot(): ScreenshotResult {
        val argv = adbArgv("exec-out", "screencap", "-p")
        val result = shell.executeBinary(
            ShellCommand(
                executable = argv.first(),
                args = argv.drop(1),
                timeoutMillis = SCREENSHOT_TIMEOUT_MILLIS,
                maxOutputBytes = MAX_SCREENSHOT_BYTES,
            ),
        )
        val png = when (result) {
            is ShellBinaryExecutionResult.Failure -> return ScreenshotResult.Failure("Cannot take a screenshot: ${result.reason}")
            is ShellBinaryExecutionResult.Success -> {
                if (result.exitCode != 0) {
                    return ScreenshotResult.Failure("Cannot take a screenshot: 'adb exec-out screencap -p' exited ${result.exitCode}: ${result.stderr}")
                }
                result.stdout
            }
        }
        val dimensions = parsePngDimensions(png)
            ?: return ScreenshotResult.Failure("Cannot take a screenshot: captured output (${png.size} bytes) is not a valid PNG")

        val savedPath = try {
            val file = Files.createTempFile("droidcommand-screenshot-", ".png")
            Files.write(file, png)
            file
        } catch (e: IOException) {
            return ScreenshotResult.Failure("Cannot take a screenshot: failed to save it to disk: ${e.message}")
        }

        return ScreenshotResult.Success(
            path = savedPath.toString(),
            widthPx = dimensions.widthPx,
            heightPx = dimensions.heightPx,
            sizeBytes = png.size.toLong(),
        )
    }

    private data class PngDimensions(val widthPx: Int, val heightPx: Int)

    /** Reads width/height directly out of a PNG's mandatory-first IHDR chunk — no image-decoding library needed for just this. */
    private fun parsePngDimensions(bytes: ByteArray): PngDimensions? {
        if (bytes.size < 24) return null
        if (!bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null

        fun beUInt32(offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        return PngDimensions(widthPx = beUInt32(16), heightPx = beUInt32(20))
    }

    private fun failure(action: String) = DeviceActionResult.Failure(notImplemented(action))

    override fun tap(x: Int, y: Int) = failure("tap")
    override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long) = failure("swipe")
    override fun typeText(text: String) = failure("type text")
    override fun pressKey(key: DeviceKey) = failure("press key")
    override fun launchApp(packageName: String) = failure("launch app")
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
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val SCREENSHOT_TIMEOUT_MILLIS = 15_000L
        const val MAX_SCREENSHOT_BYTES = 20_000_000L
    }
}
