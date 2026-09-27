package ai.droidcommand.cli

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.PlannerDecision
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.remote.pairing.PairedSocketClient
import ai.droidcommand.remote.pairing.PairingSecret
import ai.droidcommand.security.ApprovalPrompt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
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
    private val ownerOnlyRuns = AtomicInteger()

    /** A tool only the device owner may run directly. */
    private val ownerOnlyTool = object : Tool {
        override val spec = ToolSpec(
            name = "owner_only",
            description = "Test tool restricted to the device owner",
            requiredInitiator = setOf(Initiator.DEVICE_OWNER),
        )

        override fun execute(input: Map<String, String>): ToolResult {
            ownerOnlyRuns.incrementAndGet()
            return ToolResult.Success("ran")
        }
    }

    /** Echoes the objective once, then completes. */
    private val scriptedPlanner = object : Planner {
        override fun decide(
            objective: String,
            context: ConversationContext,
            availableTools: List<ToolSpec>,
            lastObservation: ToolResult?,
        ): PlannerDecision = if (lastObservation == null) {
            PlannerDecision.InvokeTool("echo", mapOf("text" to objective))
        } else {
            PlannerDecision.Complete("echoed")
        }
    }
    private var plannerFactory: () -> Planner = { scriptedPlanner }
    private fun newSession() = buildSession(
        ApprovalPrompt {
            approvals.incrementAndGet()
            approve
        },
    ).also { it.registry.register(ownerOnlyTool) }
    private val running = startDeviceServer(
        ::newSession,
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        "Test laptop",
        ttl = null,
        plannerFactory = { plannerFactory() },
    )
    private val target = "${running.address.hostString}:${running.address.port}"
    private val secretEnv = { name: String -> if (name == SECRET_ENV) running.pairing.secret.encode() else null }

    @AfterTest
    fun stop() {
        running.close()
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
    fun `the device keeps serving after a request fails`() {
        approve = false
        assertEquals(1, runDeviceSend(listOf(target, running.pairing.device.id, "run_shell_command", "executable=echo"), secretEnv))
        val (exitCode, output) = captureStdout {
            runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=second request"), secretEnv)
        }
        assertEquals(0, exitCode, output)
        assertTrue(output.contains("second request"), output)
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
    fun `remote Pilot requests run as REMOTE, so an owner-only tool is refused`() {
        val exitCode = runDeviceSend(listOf(target, running.pairing.device.id, "owner_only"), secretEnv)
        assertEquals(1, exitCode)
        assertEquals(0, ownerOnlyRuns.get())
        // The same tool still runs for the device owner locally.
        assertEquals(0, runPilot(newSession(), listOf("owner_only")))
        assertEquals(1, ownerOnlyRuns.get())
    }

    @Test
    fun `a Forge objective runs on the device with the device's planner`() {
        val (exitCode, output) = captureStdout {
            runDeviceForge(listOf(target, running.pairing.device.id, "say", "hello"), secretEnv)
        }
        assertEquals(0, exitCode, output)
        assertTrue(output.contains("Completed"), output)
        // Each step reached the controller before the final result.
        val started = output.indexOf("[device] Started")
        val step = output.indexOf("[device] Step 1: ran echo (Success)")
        assertTrue(started >= 0 && step > started && output.indexOf("Final state") > step, output)
        // Later Pilot requests keep working after a Forge objective has run to completion.
        val (pilotExit, pilotOutput) = captureStdout {
            runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=still pilot"), secretEnv)
        }
        assertEquals(0, pilotExit, pilotOutput)
        assertTrue(pilotOutput.contains("still pilot"), pilotOutput)
    }

    @Test
    fun `a Forge objective fails cleanly when the device has no LLM configured`() {
        plannerFactory = { throw IllegalStateException("no providers configured") }
        val (exitCode, output) = captureStdout {
            runDeviceForge(listOf(target, running.pairing.device.id, "do", "something"), secretEnv)
        }
        assertEquals(1, exitCode)
        assertTrue(output.contains("no providers configured"), output)
        assertEquals(1, runDeviceForge(listOf(target, running.pairing.device.id), secretEnv), "an objective is required")
    }

    @Test
    fun `device-discover finds a discoverable device-serve and its port`() {
        val discoverable = startDeviceServer(
            ::newSession,
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            "Test laptop",
            ttl = null,
            discoveryBind = InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            deviceName = "Kitchen phone",
        )
        try {
            val udp = discoverable.discoveryAddress!!
            val (exitCode, output) = captureStdout {
                runDeviceDiscover(listOf("--target", "${udp.hostString}:${udp.port}", "--timeout-ms", "1000"))
            }
            assertEquals(0, exitCode, output)
            assertTrue(output.contains(":${discoverable.address.port}  Kitchen phone"), output)
            assertTrue(!output.contains(discoverable.pairing.device.id), "discovery must not reveal the controller id")
        } finally {
            discoverable.close()
        }
    }

    @Test
    fun `device-serve is not discoverable unless asked`() {
        assertNull(running.discovery)
        assertEquals(1, runDeviceDiscover(listOf("--target", target, "--timeout-ms", "200")))
    }

    @Test
    fun `discovery options are validated`() {
        assertEquals(1, runDeviceServe(listOf("--discoverable")), "loopback can't be discovered from another device")
        assertEquals(1, runDeviceServe(listOf("--device-name")))
        assertEquals(1, runDeviceDiscover(listOf("--timeout-ms", "0")))
        assertEquals(1, runDeviceDiscover(listOf("--target", "no-port")))
        assertEquals(1, runDeviceDiscover(listOf("--bogus")))
    }

    @Test
    fun `a Forge objective stops when the controller goes away`() {
        val decisions = AtomicInteger()
        plannerFactory = { loopingPlanner(decisions) }
        PairedSocketClient.connect(running.address, running.pairing.device.id, running.pairing.secret).use { connection ->
            connection.send(RemotePilot.encodeForgeRequest("loop forever"))
            val first = RemotePilot.decodeResponse(connection.receive()!!)
            assertTrue(first.progress, first.text)
        }
        // Requests run one at a time, so this one is answered only after the objective has stopped.
        val (exitCode, output) = captureStdout {
            runDeviceSend(listOf(target, running.pairing.device.id, "echo", "text=after"), secretEnv)
        }
        assertEquals(0, exitCode, output)
        assertTrue(decisions.get() < 25, "the objective should stop early, not run all 25 steps (ran ${decisions.get()})")
    }

    /** A planner that never finishes: it keeps invoking echo, slowly, and counts its decisions. */
    private fun loopingPlanner(decisions: AtomicInteger) = object : Planner {
        override fun decide(
            objective: String,
            context: ConversationContext,
            availableTools: List<ToolSpec>,
            lastObservation: ToolResult?,
        ): PlannerDecision {
            decisions.incrementAndGet()
            Thread.sleep(50)
            return PlannerDecision.InvokeTool("echo", mapOf("text" to "again"))
        }
    }

    @Test
    fun `a cancel request stops a running objective and the final state says so`() {
        val decisions = AtomicInteger()
        plannerFactory = { loopingPlanner(decisions) }
        PairedSocketClient.connect(running.address, running.pairing.device.id, running.pairing.secret).use { connection ->
            connection.send(RemotePilot.encodeForgeRequest("loop forever"))
            assertTrue(RemotePilot.decodeResponse(connection.receive()!!).progress)
            connection.send(RemotePilot.encodeCancel())
            var response = RemotePilot.decodeResponse(connection.receive()!!)
            while (response.progress) response = RemotePilot.decodeResponse(connection.receive()!!)
            assertEquals(false, response.ok)
            assertTrue(response.text.contains("Cancelled"), response.text)
            assertTrue(decisions.get() < 25, "stopped after ${decisions.get()} steps")
            // The same connection keeps working afterwards.
            connection.send(RemotePilot.encodeRequest("echo", listOf("text=still here")))
            val next = RemotePilot.decodeResponse(connection.receive()!!)
            assertTrue(next.ok && next.text.contains("still here"), next.text)
        }
    }

    @Test
    fun `a cancel with nothing running is ignored`() {
        PairedSocketClient.connect(running.address, running.pairing.device.id, running.pairing.secret).use { connection ->
            connection.send(RemotePilot.encodeCancel())
            connection.send(RemotePilot.encodeRequest("echo", listOf("text=fine")))
            val response = RemotePilot.decodeResponse(connection.receive()!!)
            assertTrue(response.ok && response.text.contains("fine"), response.text)
        }
    }

    @Test
    fun `cancelAndWait sends a cancel only while the response is still open`() {
        val decisions = AtomicInteger()
        plannerFactory = { loopingPlanner(decisions) }
        PairedSocketClient.connect(running.address, running.pairing.device.id, running.pairing.secret).use { connection ->
            connection.send(RemotePilot.encodeForgeRequest("loop forever"))
            val finished = CountDownLatch(1)
            val printer = Thread.ofVirtual().start {
                var response = RemotePilot.decodeResponse(connection.receive()!!)
                while (response.progress) response = RemotePilot.decodeResponse(connection.receive()!!)
                finished.countDown()
            }
            assertTrue(cancelAndWait(connection, finished, Duration.ofSeconds(10)))
            printer.join()
            assertEquals(0L, finished.count)
            assertEquals(false, cancelAndWait(connection, finished, Duration.ofSeconds(1)), "nothing to cancel once finished")
        }
    }

    @Test
    fun `progress lines carry the step, tool and outcome, never inputs or errors`() {
        val result = LogEvent(LogLevel.INFO, "tool_result", mapOf("iteration" to "3", "tool" to "echo", "outcome" to "Success", "input" to "secret"))
        assertEquals("Step 3: ran echo (Success)", describeProgress(result))
        val threw = LogEvent(LogLevel.ERROR, "tool_threw", mapOf("iteration" to "2", "tool" to "shell"), IllegalStateException("password=hunter2"))
        assertEquals("Step 2: shell threw an error", describeProgress(threw))
        assertNull(describeProgress(LogEvent(LogLevel.DEBUG, "tool_result", mapOf("iteration" to "1"))))
        assertNull(describeProgress(LogEvent(LogLevel.INFO, "objective_completed", mapOf("iteration" to "1"))))
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
