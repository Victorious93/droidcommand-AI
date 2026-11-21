package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs the SAME golden fixtures as VictorLinux's Python coordinator tests, so contract drift on either side is caught here. */
class FleetFixturesTest {
    private fun res(path: String) = FleetFixturesTest::class.java.getResourceAsStream("/fleet-fixtures/v1/$path")!!.readBytes()
    private fun manifest() = res("MANIFEST.sha256").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
    private fun obj(path: String) = FleetProtocol.json.parseToJsonElement(res(path).toString(Charsets.UTF_8)) as JsonObject

    @Test fun `copies are byte-identical to the coordinator's fixtures`() {
        val m = manifest()
        assertTrue(m.size >= 24, "expected the full fixture set, got ${m.size}")
        for ((f, h) in m) assertEquals(h, MessageDigest.getInstance("SHA-256").digest(res(f)).joinToString("") { "%02x".format(it) }, f)
    }

    @Test fun `every valid fixture passes envelope and payload validation`() {
        val valid = manifest().keys.filter { !it.startsWith("invalid/") }
        for (f in valid) {
            val e = FleetProtocol.validateEnvelope(obj(f))
            val type = (e["type"] as JsonPrimitive).content
            val payload = e["payload"] as JsonObject
            if (type in FleetProtocol.WORKER_TYPES) FleetProtocol.validateWorkerPayload(type, payload)
            if (type == "assignment") FleetProtocol.validateAssignment(payload)
            if (type == "terminal_open") LiveProtocol.validateTerminalOpen(payload)
            if (type == "draft_deliver") LiveProtocol.validateDraftDeliver(payload)
        }
    }

    @Test fun `every invalid fixture is rejected with the expected structured code`() {
        val invalid = manifest().keys.filter { it.startsWith("invalid/") }
        assertTrue(invalid.size >= 18)
        for (f in invalid) {
            val case = obj(f)
            val expected = (case["expect_error"] as JsonPrimitive).content
            val ex = assertFailsWith<FleetProtocolException>(f) {
                val e = FleetProtocol.validateEnvelope(case["envelope"] as JsonObject)
                val type = (e["type"] as JsonPrimitive).content
                if (type in FleetProtocol.WORKER_TYPES) FleetProtocol.validateWorkerPayload(type, e["payload"] as JsonObject)
                if (type == "terminal_open") LiveProtocol.validateTerminalOpen(e["payload"] as JsonObject)
                if (type == "draft_deliver") LiveProtocol.validateDraftDeliver(e["payload"] as JsonObject)
            }
            assertEquals(expected, ex.code, f)
        }
    }

    @Test fun `terminal binding computed in Kotlin equals the coordinator's for the shared fixtures`() {
        for ((f, dev) in listOf("terminal_open_interactive.json" to "dev-0123456789ab", "terminal_open_oneshot.json" to "dev-0123456789ab")) {
            val p = obj(f)["payload"] as JsonObject
            fun g(k: String) = (p[k] as JsonPrimitive).content
            val cmd = (p["command"] as? JsonPrimitive)?.content
            assertEquals(g("binding"), LiveProtocol.terminalBindingHash(g("session_id"), dev, g("desktop"), g("privilege"), g("mode"), cmd, g("nonce")), f)
        }
        assertEquals("f64d73f27af4640887cfff64a0c1f5db3e3d230631f9fbe3409300c4c24b6c70",
            LiveProtocol.terminalBindingHash("term-ü-1", "dev-1", "victorlinux:o\"w\\n@host\n\t", "user", "oneshot", "echo 'héllo \uD83D\uDE00' > /tmp/x\u0001\u007f", "n\u2028"))
    }

    @Test fun `unknown optional fields are ignored for forward compatibility`() {
        val e = FleetProtocol.json.parseToJsonElement(res("heartbeat.json").toString(Charsets.UTF_8).replace("\"type\"", "\"future_optional\": 1, \"type\"")) as JsonObject
        FleetProtocol.validateEnvelope(e)
    }
}
