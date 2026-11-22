package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.security.BatchApprovals
import ai.droidcommand.security.BatchJobContext
import ai.droidcommand.security.BatchJobScope
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REAL end-to-end: this test launches VictorLinux's Python coordinator (`fleet/bin/victor-fleet serve`), pairs two Kotlin
 * workers over pinned HTTPS, submits the built-in batch-hash workflow, and checks the coordinator's combined manifest.
 * It needs the VictorLinux checkout: set VICTOR_FLEET_LIB=/path/to/VictorLinux/fleet (skipped otherwise, and says so).
 * Phones are SIMULATED by JVM workers; this proves protocol/auth/scheduling interop, not Android behaviour.
 */
class CoordinatorIntegrationTest {
    private val lib = System.getenv("VICTOR_FLEET_LIB").orEmpty()
    private val data = createTempDirectory("fleet-int").toFile()
    private var proc: Process? = null
    private var base = ""
    private var spki = ""

    private fun env() = mapOf("VICTOR_FLEET_DIR" to data.path, "VICTOR_FLEET_LIB" to lib, "PYTHONPATH" to lib)
    private fun cli(vararg a: String): String {
        val p = ProcessBuilder(listOf("python3", "$lib/bin/victor-fleet") + a).redirectErrorStream(true).also { it.environment().putAll(env()) }.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), "victor-fleet ${a.toList()} failed: $out")
        return out
    }
    private fun jsonOf(s: String) = FleetProtocol.json.parseToJsonElement(s)

    private fun startCoordinator(sharePlan: Boolean = false) {
        File(data, "config.json").writeText("""{"port": 0, "plan_approval": true, "share_plan_with_phones": $sharePlan}""")
        proc = ProcessBuilder("python3", "$lib/bin/victor-fleet", "serve").redirectErrorStream(true).also { it.environment().putAll(env()) }.start()
        val r = proc!!.inputStream.bufferedReader()
        var line: String? = null
        repeat(20) { if (line == null || !line!!.contains("coordinator on")) line = r.readLine() ?: error("coordinator exited before announcing its address") }
        base = Regex("https://[0-9.]+:\\d+").find(line!!)!!.value
        spki = "" // taken from the pairing payload below
    }

    @AfterTest fun stop() {
        proc?.destroy()
        proc?.waitFor(10, TimeUnit.SECONDS)
        data.deleteRecursively()
    }

    private fun pairing(): JsonObject = jsonOf(cli("pair")) as JsonObject
    private fun s(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content

    private fun phone(name: String, sourceTmp: File, pair: JsonObject, wrap: (ToolRunner) -> ToolRunner = { it }, batch: BatchApprovals? = null, batchable: Boolean = false): Pair<FleetWorker, InMemoryWorkerStateStore> {
        val transport = HttpsFleetTransport(s(pair, "coordinator_url"), s(pair, "coordinator_spki_sha256"))
        val store = InMemoryWorkerStateStore()
        val reg = ToolRegistry().also { it.register(HashFilesOperation().tool()) }
        val sm = AgentStateMachine()
        val runner = wrap(SecureToolExecutor(reg, ToolExecutor(reg, sm, sleep = { }), sm, SecurityPolicyEnforcer(SecurityPolicy()), { true }))
        val op = if (batchable) {
            object : FleetOperation by HashFilesOperation() {
                override val batchable = true
            }
        } else {
            HashFilesOperation()
        }
        val probe = CapabilityProbe { CapabilityReport(mapOf("hash_files" to OperationState(true, true)), RootState(), DeviceState(85, true, "none", 8L shl 30)) }
        val w = FleetWorker(
            SoftwareWorkerIdentity(), transport, probe, runner, store, listOf(op), File(sourceTmp, name).apply { mkdirs() },
            WorkerConfig(name, "it-1", longPollSeconds = 1, activePollSeconds = 1), executor = { it.run() }, batch = batch,
        )
        val r = w.enroll(PairingPayload(s(pair, "coordinator_url"), s(pair, "coordinator_fingerprint_sha256"), s(pair, "coordinator_spki_sha256"), s(pair, "pairing_secret")))
        assertTrue(r is FleetWorker.PairingResult.Paired, "enrollment must succeed against the real coordinator: $r")
        return w to store
    }

    @Test fun `two kotlin workers hash a folder through the real coordinator and the combined manifest verifies`() {
        assumeTrue(lib.isNotBlank() && File("$lib/bin/victor-fleet").exists(), "VICTOR_FLEET_LIB not set: real-coordinator integration test NOT RUN")
        startCoordinator()
        val src = createTempDirectory("src").toFile()
        val expected = mutableMapOf<String, String>()
        for (i in 0 until 8) {
            val b = ByteArray(500 + i * 211) { (it * 31 + i).toByte() }
            File(src, "d${i % 2}").mkdirs()
            File(src, "d${i % 2}/f$i.bin").writeBytes(b)
            expected["d${i % 2}/f$i.bin"] = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        }
        val tmp = createTempDirectory("wk").toFile()
        val (w1, _) = phone("phoneA", tmp, pairing())
        val (w2, _) = phone("phoneB", tmp, pairing())
        // pinned TLS: a wrong key pin must be refused
        val bad = HttpsFleetTransport(base, "0".repeat(64))
        assertFailsWith<FleetTransportException> { bad.exchange("x", JsonObject(emptyMap())) }
        // hello so both are online, then submit the workflow and approve (plan_approval = on)
        w1.step()
        w2.step()
        val submitted = jsonOf(cli("workflow", "batch-hash", "--root", src.path, "--shards", "2")) as JsonObject
        val plan = s(submitted, "plan")
        cli("approve", plan)
        val deadline = System.currentTimeMillis() + 60_000
        var overall = ""
        while (System.currentTimeMillis() < deadline) {
            w1.step()
            w2.step()
            overall = s(jsonOf(cli("report", plan)) as JsonObject, "overall")
            if (overall == "succeeded" || overall == "needs_review") break
        }
        val report = jsonOf(cli("report", plan)) as JsonObject
        assertEquals("succeeded", overall, report.toString().take(1500))
        val jobs = (report["jobs"] as JsonArray).map { it as JsonObject }
        val devices = jobs.filter { s(it, "op") == "hash_files" }.map { ((it["attempts"] as JsonArray)[0] as JsonObject)["device_id"].toString() }.toSet()
        assertEquals(2, devices.size, "work was spread across both phones")
        val merge = jobs.first { s(it, "op") == "coordinator.merge_manifests" }
        assertEquals(8, ((merge["result"] as JsonObject)["files"] as JsonPrimitive).content.toInt())
    }

    @Test fun `with plan sharing on, the coordinator's manifest reaches the worker with the very arguments of the assignment`() {
        assumeTrue(lib.isNotBlank() && File("$lib/bin/victor-fleet").exists(), "VICTOR_FLEET_LIB not set: real-coordinator integration test NOT RUN")
        startCoordinator(sharePlan = true)
        val src = createTempDirectory("src").toFile()
        for (i in 0 until 6) {
            File(src, "d${i % 2}").mkdirs()
            File(src, "d${i % 2}/f$i.bin").writeBytes(ByteArray(300 + i * 97) { (it * 17 + i).toByte() })
        }
        val seen = java.util.Collections.synchronizedList(mutableListOf<BatchJobContext?>())
        val capture: (ToolRunner) -> ToolRunner = { inner ->
            object : ToolRunner {
                override fun run(toolName: String, input: Map<String, String>, retryPolicy: ai.droidcommand.agent.RetryPolicy, isCancelled: () -> Boolean, mode: ai.droidcommand.agent.AgentMode?, initiator: ai.droidcommand.agent.Initiator?, grantId: String?): ToolResult {
                    seen += BatchJobScope.current()
                    return inner.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
                }
            }
        }
        val approvals = BatchApprovals(ai.droidcommand.security.BatchEligibility { null }, enabled = { true })
        val tmp = createTempDirectory("wk").toFile()
        val (w1, _) = phone("phoneA", tmp, pairing(), capture, approvals, batchable = true)
        val (w2, _) = phone("phoneB", tmp, pairing(), capture, approvals, batchable = true)
        w1.step()
        w2.step()
        val plan = s(jsonOf(cli("workflow", "batch-hash", "--root", src.path, "--shards", "2")) as JsonObject, "plan")
        cli("approve", plan)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && seen.size < 2) {
            w1.step()
            w2.step()
        }
        assertEquals(2, seen.size, "both hash jobs were run")
        for (ctx in seen) {
            val c = assertNotNull(ctx, "a batch context was set around the tool call")
            val offer = assertNotNull(c.offer, "the real coordinator's manifest parsed into an offer for ${c.jobId}")
            assertEquals(plan, offer.planId)
            assertEquals(2, offer.jobs.size, "the two phone jobs; the coordinator-local stage and merge jobs are not listed")
            assertTrue(offer.jobs.all { it.toolName == "fleet.hash_files" })
            assertEquals(c.argsDigest, offer.jobs.single { it.jobId == c.jobId }.argsDigest, "the manifest listed exactly the arguments this phone was assigned")
        }
    }

    @Test fun `with plan sharing off, the default, no assignment carries a manifest`() {
        assumeTrue(lib.isNotBlank() && File("$lib/bin/victor-fleet").exists(), "VICTOR_FLEET_LIB not set: real-coordinator integration test NOT RUN")
        startCoordinator()
        val src = createTempDirectory("src").toFile()
        for (i in 0 until 4) {
            File(src, "d${i % 2}").mkdirs()
            File(src, "d${i % 2}/f$i.bin").writeBytes(ByteArray(200 + i * 53) { (it + i).toByte() })
        }
        val seen = java.util.Collections.synchronizedList(mutableListOf<BatchJobContext?>())
        val capture: (ToolRunner) -> ToolRunner = { inner ->
            object : ToolRunner {
                override fun run(toolName: String, input: Map<String, String>, retryPolicy: ai.droidcommand.agent.RetryPolicy, isCancelled: () -> Boolean, mode: ai.droidcommand.agent.AgentMode?, initiator: ai.droidcommand.agent.Initiator?, grantId: String?): ToolResult {
                    seen += BatchJobScope.current()
                    return inner.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
                }
            }
        }
        val approvals = BatchApprovals(ai.droidcommand.security.BatchEligibility { null }, enabled = { true })
        val tmp = createTempDirectory("wk").toFile()
        val (w1, _) = phone("phoneA", tmp, pairing(), capture, approvals, batchable = true)
        val (w2, _) = phone("phoneB", tmp, pairing(), capture, approvals, batchable = true)
        w1.step()
        w2.step()
        val plan = s(jsonOf(cli("workflow", "batch-hash", "--root", src.path, "--shards", "2")) as JsonObject, "plan")
        cli("approve", plan)
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && seen.size < 2) {
            w1.step()
            w2.step()
        }
        assertEquals(2, seen.size)
        assertTrue(seen.all { it != null && it.offer == null }, "jobs ran, with no manifest to offer")
    }
}
