package ai.droidcommand.tools.android

import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellSecurityPolicy
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real hardware verification for [AdbDeviceController]'s 5 read-only methods, mirroring
 * `core-root.AdbRootExecutorRealDeviceIntegrationTest`'s exact pattern: skips cleanly (reported
 * SKIPPED, never a false PASS) wherever no real authorized adb device is present — every CI run
 * included. Every query here is read-only by construction (see [AdbDeviceController]'s own doc
 * comment) — nothing in this class taps, types, or otherwise acts on the real device it verifies
 * against.
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
}
