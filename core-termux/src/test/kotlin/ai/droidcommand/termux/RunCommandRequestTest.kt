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

    @Test
    fun `default extras keep the live com termux prefix`() {
        val extras = RunCommandRequest.from(TermuxCommand(executable = "id", args = emptyList()))
            .extrasWithoutPendingIntent()
        assertTrue(extras.keys.all { it.startsWith("com.termux.") })
        assertEquals(true, extras[RunCommandContract.EXTRA_BACKGROUND])
        assertEquals(RunCommandContract.RUNNER_APP_SHELL, extras[RunCommandContract.EXTRA_RUNNER])
    }

    @Test
    fun `flag-on extras use the suite prefix and package`() {
        val target = RunCommandTargets.VICTORSUITE_REPACKAGE
        val request = RunCommandRequest.from(
            TermuxCommand(executable = "id", args = emptyList()),
            commandLabel = "id",
            target = target,
        )
        assertEquals("com.victorious.suite", request.packageName)
        assertEquals("com.victorious.suite.RUN_COMMAND", request.action)
        assertEquals("com.termux.app.RunCommandService", request.componentClass)
        assertEquals("/data/data/com.victorious.suite/files/usr/bin/id", request.path)
        assertEquals(target.homeDir, request.workdir)
        val extras = request.extrasWithoutPendingIntent()
        assertEquals(target.usrBin("id"), extras[target.extraPath])
        assertEquals(true, extras[target.extraBackground])
        assertEquals("id", extras[target.extraCommandLabel])
        assertTrue(extras.keys.all { it.startsWith("com.victorious.suite.") })
        assertTrue(extras.keys.none { it.startsWith("com.termux.") })
        assertTrue("ai.droidcommand.REMOTE_ENVELOPE_JSON" !in extras.keys)
        assertTrue("ai.droidcommand.REMOTE_RESPONSE_JSON" !in extras.keys)
    }
}
