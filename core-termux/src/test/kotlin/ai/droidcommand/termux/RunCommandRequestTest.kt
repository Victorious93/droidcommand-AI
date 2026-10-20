package ai.droidcommand.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunCommandRequestTest {
    @Test
    fun `from maps a TermuxCommand onto VictorSuite extras and forces background`() {
        val request = RunCommandRequest.from(
            TermuxCommand(
                executable = "proot-distro",
                args = listOf("login", "kali", "--", "nmap", "-sV", "10.0.0.5"),
                workingDirectory = null,
            ),
            commandLabel = "kali-nmap",
        )
        assertEquals("/data/data/com.termux/files/usr/bin/proot-distro", request.path)
        assertEquals(listOf("login", "kali", "--", "nmap", "-sV", "10.0.0.5"), request.arguments)
        assertEquals(RunCommandContract.DEFAULT_WORKDIR, request.workdir)
        assertTrue(request.background)
        val extras = request.extrasWithoutPendingIntent()
        assertEquals(true, extras[RunCommandContract.EXTRA_BACKGROUND])
        assertEquals(RunCommandContract.RUNNER_APP_SHELL, extras[RunCommandContract.EXTRA_RUNNER])
        assertEquals("kali-nmap", extras[RunCommandContract.EXTRA_COMMAND_LABEL])
        assertTrue(RunCommandContract.EXTRA_PENDING_INTENT !in extras.keys)
    }

    @Test
    fun `keeps an absolute executable path`() {
        val request = RunCommandRequest.from(
            TermuxCommand(executable = RunCommandContract.PROOT_DISTRO_PATH, args = listOf("login", "kali", "--", "id")),
        )
        assertEquals(RunCommandContract.PROOT_DISTRO_PATH, request.path)
    }

    @Test
    fun `refuses background false`() {
        assertFailsWith<IllegalArgumentException> {
            RunCommandRequest(path = RunCommandContract.PROOT_DISTRO_PATH, arguments = listOf("login"), background = false)
        }
    }

    @Test
    fun `runtime permission prompt is skipped when VictorSuite is not installed`() {
        assertFalse(RunCommandContract.shouldRequestRuntimePermission(packageInstalled = false, permissionGranted = false))
        assertTrue(RunCommandContract.shouldRequestRuntimePermission(packageInstalled = true, permissionGranted = false))
        assertFalse(RunCommandContract.shouldRequestRuntimePermission(packageInstalled = true, permissionGranted = true))
    }
}
