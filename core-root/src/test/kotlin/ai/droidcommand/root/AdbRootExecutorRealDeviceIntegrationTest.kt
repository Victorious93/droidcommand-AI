package ai.droidcommand.root

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * This repository's first hardware-dependent test: proves [AdbRootExecutor] against a real,
 * authorized `adb` device with real Magisk root granted — not a fixture. Every prior claim of real
 * on-device root behavior in this codebase (`docs/AUDIT_2026-09-05.md`'s Magisk-support addendum)
 * was, honestly, "IMPLEMENTED — NOT RUNTIME VERIFIED"; this is the first actual runtime
 * verification, made possible only because a real device happened to be connected during this
 * session.
 *
 * [assumeTrue] at the top of every test means this class **skips cleanly** (reported as SKIPPED,
 * never as a false PASS or a FAIL) wherever no such device is present — every CI run included, since
 * `ubuntu-latest` GitHub-hosted runners have no `adb`/device attached, and any future device-free
 * session running this suite locally. A skip here is expected and correct, not a regression.
 *
 * Every command this class actually runs against the real device is read-only/harmless by design:
 * `id`, `getprop`, `pwd` (scoped to `/data/local/tmp`), and `echo` of a literal test string. Nothing
 * destructive, nothing outside `/data/local/tmp`, no package or system modification.
 */
class AdbRootExecutorRealDeviceIntegrationTest {
    private val executor = AdbRootExecutor()

    @BeforeTest
    fun requireRealDevice() {
        assumeTrue(executor.isDeviceConnected(), "No authorized adb device connected — skipping real-device verification")
    }

    @Test
    fun `a real android version is reported`() {
        val version = executor.getAndroidVersion()
        assertTrue(!version.isNullOrBlank(), "expected a non-blank Android version from the real device")
    }

    @Test
    fun `real root is available and authorized on the connected device`() {
        assertTrue(executor.isRootAvailable())
        assertTrue(executor.isAuthorized())
        assertEquals(PrivilegeLevel.ROOT, executor.getPrivilegeLevel())
    }

    @Test
    fun `real health check reports AVAILABLE when root is probed`() {
        val health = executor.checkHealth(probeShell = true)
        assertEquals(RootProviderState.AVAILABLE, health.state)
        assertTrue(health.rootAvailable)
        assertTrue(health.rootAuthorized)
    }

    @Test
    fun `executing 'id' as root on the real device reports uid 0`() {
        val result = executor.execute(RootCommand(executable = "id"))
        assertIs<RootExecutionResult.Success>(result)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("uid=0"), "expected uid=0 in real device output, got: ${result.stdout}")
    }

    @Test
    fun `a workingDirectory scoped to data local tmp is honored on the real device`() {
        val result = executor.execute(RootCommand(executable = "pwd", workingDirectory = "/data/local/tmp"))
        assertIs<RootExecutionResult.Success>(result)
        assertEquals("/data/local/tmp", result.stdout.trim())
    }

    @Test
    fun `an environment variable is visible to the real command that runs`() {
        val result = executor.execute(
            RootCommand(
                executable = "sh",
                args = listOf("-c", "echo \$ADB_ROOT_EXECUTOR_REAL_DEVICE_TEST"),
                environment = mapOf("ADB_ROOT_EXECUTOR_REAL_DEVICE_TEST" to "adbroottest"),
            ),
        )
        assertIs<RootExecutionResult.Success>(result)
        assertEquals("adbroottest", result.stdout.trim())
    }
}
