package ai.droidcommand.cli

import ai.droidcommand.remote.pairing.PairingSecret
import ai.droidcommand.security.ApprovalPrompt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs a real device-serve server and device-send client over loopback TCP. */
class DeviceCommandsTest {
    private val approvals = AtomicInteger()
    private var approve = true
    private val running = startDeviceServer(
        buildSession(
            ApprovalPrompt {
                approvals.incrementAndGet()
                approve
            },
        ),
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        "Test laptop",
        ttl = null,
    )
    private val target = "${running.address.hostString}:${running.address.port}"
    private val secretEnv = { name: String -> if (name == SECRET_ENV) running.pairing.secret.encode() else null }

    @AfterTest
    fun stop() {
        running.server.close()
    }

    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        val exitCode = try {
            block()
        } finally {
            System.setOut(original)
        }
        return exitCode to buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `a paired controller runs a Pilot tool on the device and gets the result`() {
        val (exitCode, output) = captureStdout {
            runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=hello from the laptop"), secretEnv)
        }
        assertEquals(0, exitCode)
        assertTrue(output.contains("hello from the laptop"), output)
    }

    @Test
    fun `a sensitive tool still needs approval on the device, and a refusal fails the request`() {
        approve = false
        val (exitCode, _) = captureStdout {
            runDeviceSend(listOf(target, running.pairing.device.id, "run_shell_command", "executable=echo"), secretEnv)
        }
        assertEquals(1, exitCode)
        assertEquals(1, approvals.get(), "the device-side approval prompt was asked exactly once")
    }

    @Test
    fun `the wrong secret is refused`() {
        val wrong = PairingSecret.generate().encode()
        val exitCode = runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=x")) { wrong }
        assertEquals(1, exitCode)
    }

    @Test
    fun `an unknown controller id is refused`() {
        val exitCode = runDeviceSend(listOf(target, "someone-else", "echo", "text=x"), secretEnv)
        assertEquals(1, exitCode)
    }

    @Test
    fun `a missing or malformed secret fails before connecting`() {
        assertEquals(1, runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=x")) { null })
        assertEquals(1, runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=x")) { "not-a-secret" })
    }

    @Test
    fun `malformed arguments fail cleanly`() {
        assertEquals(1, runDeviceSend(listOf(target, running.pairing.device.id), secretEnv))
        assertEquals(1, runDeviceSend(listOf("no-port", running.pairing.device.id, "echo"), secretEnv))
        assertEquals(1, runDeviceSend(listOf(target, running.pairing.device.id, "echo", "missing-separator"), secretEnv))
    }

    @Test
    fun `host and port parsing`() {
        assertEquals(7100, parseHostPort("192.168.1.20:7100")?.port)
        assertEquals(InetAddress.getByName("::1"), parseHostPort("[::1]:7100")?.address)
        assertNull(parseHostPort("host"))
        assertNull(parseHostPort("host:99999"))
        assertNull(parseHostPort(":7100"))
    }
}
