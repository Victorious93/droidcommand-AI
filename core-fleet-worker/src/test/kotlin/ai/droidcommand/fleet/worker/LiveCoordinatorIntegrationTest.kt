package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REAL coordinator (VictorLinux python) + REAL Kotlin worker over pinned HTTPS: terminal one-shot via `victor-fleet exec`, interactive
 * relay input/resize/exit through the CLI's relay client, and desktop->phone draft hand-off. The broker and VictorSMS are FAKES (a phone's
 * VictorSuite/VictorSMS are not available here): this proves the cross-implementation contract, not Android.
 */
class LiveCoordinatorIntegrationTest {
    private val lib = System.getenv("VICTOR_FLEET_LIB").orEmpty()
    private val data = createTempDirectory("fleet-live").toFile()
    private var proc: Process? = null
    private fun env() = mapOf("VICTOR_FLEET_DIR" to data.path, "VICTOR_FLEET_LIB" to lib, "PYTHONPATH" to lib)
    private fun run(vararg a: String, timeout: Long = 90): Triple<Int, String, String> {
        val p = ProcessBuilder(listOf("python3", "$lib/bin/victor-fleet") + a).also { it.environment().putAll(env()) }.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(timeout, TimeUnit.SECONDS))
        return Triple(p.exitValue(), out, err)
    }
    private fun s(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content
    private fun obj(t: String) = FleetProtocol.json.parseToJsonElement(t) as JsonObject

    class Broker : ShellBrokerClient {
        val inputs = CopyOnWriteArrayList<String>()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        var approve = true
        private var l: BrokerListener? = null
        private var mode = ""
        var opened = 0
        override fun status() = BrokerStatus.ready()
        override fun open(req: BrokerRequest, listener: BrokerListener): BrokerOpen {
            opened++
            l = listener
            mode = req.mode
            thread {
                listener.onState("awaiting_approval", null, null)
                if (!approve) {
                    listener.onState("denied", "the owner declined", null)
                    return@thread
                }
                listener.onState("open", null, req.binding)
                if (req.mode == "oneshot") {
                    listener.onOutput("hello from the phone: ${req.command}\n".toByteArray(), "stdout")
                    listener.onOutput("warn\n".toByteArray(), "stderr")
                    listener.onExit(3)
                    listener.onState("closed", "command finished", null)
                } else {
                    listener.onOutput("PHONE-PROMPT$ ".toByteArray(), "tty")
                }
            }
            return BrokerOpen.Accepted
        }
        override fun input(sessionId: String, data: ByteArray) {
            inputs += String(data)
            if (String(data).contains("exit")) {
                thread {
                    l!!.onOutput("bye\n".toByteArray(), "tty")
                    l!!.onExit(7)
                    l!!.onState("closed", "shell exited", null)
                }
            }
        }
        override fun resize(sessionId: String, cols: Int, rows: Int) {
            sizes += cols to rows
        }
        override fun signal(sessionId: String, name: String) {}
        override fun close(sessionId: String, reason: String) {}
    }

    class Drafts : DraftHandoffClient {
        val seen = LinkedHashMap<String, DraftState>()
        val created = CopyOnWriteArrayList<String>()
        var listener: ((String, DraftState) -> Unit)? = null
        override fun status(channel: String) = BrokerStatus.ready()
        override fun deliver(d: IncomingDraft) = seen.getOrPut(d.draftId) {
            created += d.draftId
            DraftState("received")
        }
        override fun setOutcomeListener(l: (String, DraftState) -> Unit) {
            listener = l
        }
    }

    @AfterTest fun stop() {
        proc?.destroy()
        proc?.waitFor(10, TimeUnit.SECONDS)
        data.deleteRecursively()
    }

    private fun start(broker: Broker, drafts: Drafts): Pair<FleetWorker, () -> String> {
        File(data, "config.json").writeText("""{"port": 0, "plan_approval": false}""")
        proc = ProcessBuilder("python3", "$lib/bin/victor-fleet", "serve").redirectErrorStream(true).also { it.environment().putAll(env()) }.start()
        val r = proc!!.inputStream.bufferedReader()
        var line: String? = null
        repeat(20) { if (line == null || !line!!.contains("coordinator on")) line = r.readLine() ?: error("coordinator exited") }
        val pair = obj(run("pair").second)
        val transport = HttpsFleetTransport(s(pair, "coordinator_url"), s(pair, "coordinator_spki_sha256"))
        val store = InMemoryWorkerStateStore()
        val reg = ToolRegistry()
        val sm = AgentStateMachine()
        val runner = SecureToolExecutor(reg, ToolExecutor(reg, sm, sleep = { }), sm, SecurityPolicyEnforcer(SecurityPolicy()), { true })
        val probe = CapabilityProbe { CapabilityReport(LiveCapabilities.operations(BrokerStatus.ready(), BrokerStatus.ready(), BrokerStatus.ready()), RootState(), DeviceState(85, true, "none", 8L shl 30)) }
        val w = FleetWorker(
            SoftwareWorkerIdentity(), transport, probe, runner, store, emptyList(), File(createTempDirectory("wk").toFile(), "p").apply { mkdirs() },
            WorkerConfig("itphone", "it-1", longPollSeconds = 1, activePollSeconds = 1), executor = { it.run() },
            liveFactory = { send, relay -> LiveFeatures({ store.load().deviceId }, broker, drafts, relay, send) },
        )
        assertTrue(w.enroll(PairingPayload(s(pair, "coordinator_url"), s(pair, "coordinator_fingerprint_sha256"), s(pair, "coordinator_spki_sha256"), s(pair, "pairing_secret"))) is FleetWorker.PairingResult.Paired)
        w.step()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        thread(isDaemon = true) { w.runLoop({ stop.get() }, { Thread.sleep(50) }) }
        return w to { store.load().deviceId!! }
    }

    private fun assume() = assumeTrue(lib.isNotBlank() && File("$lib/bin/victor-fleet").exists(), "VICTOR_FLEET_LIB not set: real-coordinator live test NOT RUN")

    @Test fun `one-shot through the real coordinator returns the phone's output and exit status, denial runs nothing`() {
        assume()
        val b = Broker()
        val (_, dev) = start(b, Drafts())
        val id = dev()
        val ok = run("exec", id, "--shell", "echo hi")
        assertEquals(3, ok.first, ok.third)
        assertTrue(ok.second.contains("hello from the phone: echo hi"), ok.second)
        assertTrue(ok.third.contains("warn"))
        val devs = run("devices").second
        assertTrue(devs.contains("\"terminal\"") && devs.contains("\"ready\""))
        b.approve = false
        val denied = run("exec", id, "--shell", "touch /nope")
        assertEquals(4, denied.first, denied.third)
        assertTrue(denied.third.contains("denied"))
    }

    @Test fun `message draft reaches the phone, is idempotent on retry, and the owner's outcome flows back`() {
        assume()
        val d = Drafts()
        val (_, dev) = start(Broker(), d)
        val id = dev()
        val r = obj(run("message", "draft-email", id, "--to", "alex@example.test", "--subject", "Hi", "--body", "Body text").second)
        val did = s(r, "id")
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && s(obj(run("message", "status", did).second), "state") != "received") Thread.sleep(300)
        assertEquals("received", s(obj(run("message", "status", did).second), "state"))
        assertEquals(listOf(did), d.created)
        d.listener!!(did, DraftState("submitted")) // the owner pressed Send on the phone
        val end2 = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end2 && s(obj(run("message", "status", did).second), "state") != "submitted") Thread.sleep(300)
        assertEquals("submitted", s(obj(run("message", "status", did).second), "state"))
        assertEquals(1, d.created.size)
    }
}
