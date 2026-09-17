package ai.droidcommand.tools.android

import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellSecurityPolicy
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real hardware verification for [AdbDeviceController]'s 6 read-only methods, mirroring
 * `core-root.AdbRootExecutorRealDeviceIntegrationTest`'s exact pattern: skips cleanly (reported
 * SKIPPED, never a false PASS) wherever no real authorized adb device is present — every CI run
 * included. Every query here is read-only by construction (see [AdbDeviceController]'s own doc
 * comment) — nothing in this class taps, types, or otherwise acts on the real device it verifies
 * against. **The `real screenshot` case below was added 2026-09-17, alongside `takeScreenshot`'s
 * own implementation, in a session with no real device attached** — it is genuinely untested
 * against real hardware so far (honestly reflected in `docs/ARCHITECTURE.md`'s `AdbDeviceController`
 * row), and will only actually run — not just compile — the next time this suite executes with a
 * real authorized device present.
 */
class AdbDeviceControllerRealDeviceIntegrationTest {
    private val shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("adb")))
    private val device = AdbDeviceController(shell = shell)

    @BeforeTest
    fun requireRealDevice() {
        val result = shell.execute(ShellCommand(executable = "adb", args = listOf("get-state")))
        val connected = result is ShellExecutionResult.Success && result.exitCode == 0 && result.stdout.trim() == "device"
        assumeTrue(connected, "No authorized adb device connected — skipping real-device verification")
    }

    @Test
    fun `real device info is reported`() {
        val result = assertIs<DeviceInfoResult.Success>(device.getDeviceInfo())
        assertTrue(!result.info.manufacturer.isNullOrBlank(), "expected a real manufacturer")
        assertTrue(!result.info.model.isNullOrBlank(), "expected a real model")
        assertTrue(!result.info.osVersion.isNullOrBlank(), "expected a real OS version")
        assertTrue((result.info.screenWidthPx ?: 0) > 0, "expected a real screen width")
        assertTrue((result.info.screenHeightPx ?: 0) > 0, "expected a real screen height")
    }

    @Test
    fun `real battery status is plausible`() {
        val result = assertIs<BatteryStatusResult.Success>(device.getBatteryStatus())
        assertTrue(result.status.levelPercent in 0..100, "expected a plausible battery level, got ${result.status.levelPercent}")
    }

    @Test
    fun `real storage info is plausible`() {
        val result = assertIs<StorageInfoResult.Success>(device.getStorageInfo())
        assertTrue(result.info.totalBytes > 0, "expected a positive total storage size")
        assertTrue(result.info.freeBytes in 0..result.info.totalBytes, "free bytes should be between 0 and total bytes")
    }

    @Test
    fun `real installed apps are listed`() {
        val result = assertIs<InstalledAppsResult.Success>(device.listInstalledApps())
        // A real device may genuinely have zero third-party apps installed — assert only that the
        // real query itself succeeded and returned real InstalledApp values, not that any exist.
        for (app in result.apps) {
            assertTrue(app.packageName.isNotBlank())
        }
    }

    @Test
    fun `real UI tree has at least one real node under the root`() {
        val result = assertIs<UiTreeResult.Success>(device.getUiTree())
        assertTrue(result.tree.root.children.isNotEmpty(), "expected a real home/lock screen to have at least one child node")
    }

    @Test
    fun `real screenshot is captured, saved, and reports plausible dimensions`() {
        val result = assertIs<ScreenshotResult.Success>(device.takeScreenshot())
        val savedFile = File(result.path)
        try {
            assertTrue(savedFile.exists(), "expected the screenshot to actually be saved to disk")
            assertTrue(result.widthPx > 0 && result.heightPx > 0, "expected a plausible real screen resolution")
            assertTrue(result.sizeBytes > 0, "expected a non-empty real PNG")
        } finally {
            savedFile.delete() // this is the real device's home/lock screen at capture time — don't leave it on disk
        }
    }
}
