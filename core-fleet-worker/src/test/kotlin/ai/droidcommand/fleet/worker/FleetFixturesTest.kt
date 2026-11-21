package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the SAME golden fixtures as VictorLinux's Python coordinator tests, so contract drift on either side is caught here. */
class FleetFixturesTest {
    private fun res(path: String) = FleetFixturesTest::class.java.getResourceAsStream("/fleet-fixtures/v1/$path")!!.readBytes()
    private fun manifest() = res("MANIFEST.sha256").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.associate { it.substringAfter("  ") to it.substringBefore("  ") }
    private fun obj(path: String) = FleetProtocol.json.parseToJsonElement(res(path).toString(Charsets.UTF_8)) as JsonObject

    @Test fun `copies are byte-identical to the coordinator's fixtures`() {
        val m = manifest()
        assertTrue(m.size >= 33, "expected the full fixture set, got ${m.size}")
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
        }
    }

    @Test fun `every invalid fixture is rejected with the expected structured code`() {
        val invalid = manifest().keys.filter { it.startsWith("invalid/") }
        assertTrue(invalid.size >= 9)
        for (f in invalid) {
            val case = obj(f)
            val expected = (case["expect_error"] as JsonPrimitive).content
            val ex = assertFailsWith<FleetProtocolException>(f) {
                val e = FleetProtocol.validateEnvelope(case["envelope"] as JsonObject)
                val type = (e["type"] as JsonPrimitive).content
                if (type in FleetProtocol.WORKER_TYPES) FleetProtocol.validateWorkerPayload(type, e["payload"] as JsonObject)
            }
            assertEquals(expected, ex.code, f)
        }
    }

    @Test fun `an assignment with a plan manifest parses it, and the assigned job is in it with the same arguments`() {
        val payload = obj("assignment_with_plan.json")["payload"] as JsonObject
        FleetProtocol.validateAssignment(payload)
        val plan = assertNotNull(FleetProtocol.parsePlanManifest(payload))
        assertEquals("plan-x", plan.planId)
        assertEquals(3, plan.jobs.size)
        val mine = plan.jobs.single { it.jobId == (payload["job_id"] as JsonPrimitive).content }
        assertEquals(payload["args"], mine.args)
        assertTrue(plan.jobs.none { it.requiresRoot })
    }

    @Test fun `an assignment with no plan has no manifest`() {
        assertNull(FleetProtocol.parsePlanManifest(obj("assignment.json")["payload"] as JsonObject))
    }

    @Test fun `every ignored-plan fixture is still a valid assignment but yields no manifest`() {
        val cases = manifest().keys.filter { it.startsWith("plan_ignored/") }
        assertTrue(cases.size >= 8, "expected the full set, got ${cases.size}")
        for (f in cases) {
            val payload = FleetProtocol.validateEnvelope(obj(f))["payload"] as JsonObject
            FleetProtocol.validateAssignment(payload) // the job itself is fine and must still run
            assertNull(FleetProtocol.parsePlanManifest(payload), f)
        }
    }

    @Test fun `unknown optional fields are ignored for forward compatibility`() {
        val e = FleetProtocol.json.parseToJsonElement(res("heartbeat.json").toString(Charsets.UTF_8).replace("\"type\"", "\"future_optional\": 1, \"type\"")) as JsonObject
        FleetProtocol.validateEnvelope(e)
    }
}
