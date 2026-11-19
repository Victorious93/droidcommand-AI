package ai.droidcommand.securitybackend

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every JSON fixture below is a byte-for-byte copy of a REAL response captured from a running
 * `secbackend.py` instance on 2026-11-19 (a real `msfrpcd` and a real installed `setoolkit`
 * behind it, driven via `victor-secbackend submit/status/cancel` — see VictorLinux's
 * `docs/security-backend.md#full-service-verification` for the exact session this was copied
 * from), not invented shapes. Decoding real captured output is what actually proves this
 * client's data classes match the server, not just that they are internally consistent.
 */
private class FakeHttpTransport(private val respond: (HttpRequestSpec) -> HttpResponseSpec) : HttpTransport {
    val requests = mutableListOf<HttpRequestSpec>()
    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        return respond(request)
    }
}

private fun config() = SecurityBackendConfig(baseUrl = "http://127.0.0.1:18099", apiKey = "victor-test-key")

private const val CAPABILITIES_FIXTURE = """
{
  "protocol": {"major": 1, "minor": 0},
  "operations": {
    "metasploit.module": {
      "available": true, "reason": null,
      "version": {"version": "6.5.12-dev-a5d29613", "ruby": "3.3.6 x86_64-linux 2024-11-05", "api": "1.0"}
    },
    "setoolkit.attack": {
      "available": true, "reason": null,
      "supported_vectors": ["QRCODE_GENERATOR"],
      "unsupported_vectors": {
        "SMS_SPOOFING": "SET removed SMS Spoofing from its main attack-vector menu; the vector no longer exists in the installed version."
      }
    }
  }
}
"""

private const val SET_JOB_COMPLETED_FIXTURE = """
{
  "job_id": "job_f2759fb0b779410fb79265beed67bb12",
  "op": "setoolkit.attack",
  "state": "completed",
  "created_at": 1795056843527,
  "updated_at": 1795056851819,
  "timeout_ms": 180000,
  "result": {"summary": "QR code generated", "artifact_path": "/root/.set/reports/qrcode_attack.png"},
  "output": [{"seq": 0, "text": "QRCode has been generated under /root/.set/reports/qrcode_attack.png"}],
  "next_cursor": 1
}
"""

private const val METASPLOIT_JOB_COMPLETED_FIXTURE = """
{
  "job_id": "job_31225a4d50414a31be31fd86dd017518",
  "op": "metasploit.module",
  "state": "completed",
  "created_at": 1795056830725,
  "updated_at": 1795056832289,
  "timeout_ms": 180000,
  "result": {"summary": "module completed", "exit_evidence": "completion marker observed in console output"},
  "output": [
    {"seq": 0, "text": "PORTS => 1-100\nTHREADS => 5\nRHOSTS => 127.0.0.1\n"},
    {"seq": 1, "text": "[*] 127.0.0.1             - Scanned 1 of 1 hosts (100% complete)\n[*] Auxiliary module execution completed\n"}
  ],
  "next_cursor": 2
}
"""

private const val CANCELLED_JOB_FIXTURE = """
{
  "job_id": "job_08422504fa3d42eaa119b46d76e93395",
  "op": "metasploit.module",
  "state": "cancelled",
  "created_at": 1795056868279,
  "updated_at": 1795056870731,
  "timeout_ms": 60000,
  "error": {"code": "cancelled", "message": "job cancelled"},
  "output": [{"seq": 0, "text": "PORTS => 1-65000\nTHREADS => 1\nRHOSTS => 127.0.0.1\n"}],
  "next_cursor": 1
}
"""

private const val INTERRUPTED_JOB_FIXTURE = """
{
  "job_id": "job_4dc5d9ef6b3840f2af930139cb917460",
  "op": "metasploit.module",
  "state": "interrupted",
  "created_at": 1795056882092,
  "updated_at": 1795056884227,
  "timeout_ms": 60000,
  "error": {"code": "internal", "message": "the security backend restarted while this job was in flight; its real outcome on the tool side is unknown"},
  "output": [],
  "next_cursor": 0
}
"""

private const val UNAUTHORIZED_FIXTURE = """{"error": {"code": "unauthorized", "message": "Missing or invalid security API key"}}"""
private const val UNKNOWN_JOB_FIXTURE = """{"error": {"code": "unknown_job", "message": "no job 'job_doesnotexist'"}}"""
private const val IDEMPOTENCY_CONFLICT_FIXTURE =
    """{"error": {"code": "idempotency_conflict", "message": "idempotency_key 'qr-test-1' was already used with different parameters"}}"""

class SecurityBackendClientTest {
    @Test
    fun `decodes a real capabilities response`() {
        val transport = FakeHttpTransport { HttpResponseSpec(200, emptyMap(), CAPABILITIES_FIXTURE) }
        val caps = SecurityBackendClient(transport, config()).capabilities()
        assertEquals(1, caps.protocol.major)
        assertTrue(caps.operations["metasploit.module"]!!.available)
        assertEquals(listOf("QRCODE_GENERATOR"), caps.operations["setoolkit.attack"]!!.supported_vectors)
        assertTrue(caps.operations["setoolkit.attack"]!!.unsupported_vectors.containsKey("SMS_SPOOFING"))
    }

    @Test
    fun `sends the api key as a bearer header`() {
        val transport = FakeHttpTransport { HttpResponseSpec(200, emptyMap(), CAPABILITIES_FIXTURE) }
        SecurityBackendClient(transport, config()).capabilities()
        assertEquals("Bearer victor-test-key", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun `decodes a real completed setoolkit job`() {
        val transport = FakeHttpTransport { HttpResponseSpec(200, emptyMap(), SET_JOB_COMPLETED_FIXTURE) }
        val job = SecurityBackendClient(transport, config()).jobStatus("job_f2759fb0b779410fb79265beed67bb12")
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals("/root/.set/reports/qrcode_attack.png", job.result!!.artifact_path)
    }

    @Test
    fun `decodes a real completed metasploit job with two output chunks`() {
        val transport = FakeHttpTransport { HttpResponseSpec(200, emptyMap(), METASPLOIT_JOB_COMPLETED_FIXTURE) }
        val job = SecurityBackendClient(transport, config()).jobStatus("job_31225a4d50414a31be31fd86dd017518")
        assertEquals(JobState.COMPLETED, job.state)
        assertEquals(2, job.output.size)
        assertTrue(job.output.last().text.contains("Auxiliary module execution completed"))
        assertEquals(2L, job.next_cursor)
    }

    @Test
    fun `decodes a real cancelled job with its structured error`() {
        val transport = FakeHttpTransport { HttpResponseSpec(200, emptyMap(), CANCELLED_JOB_FIXTURE) }
        val job = SecurityBackendClient(transport, config()).jobStatus("job_08422504fa3d42eaa119b46d76e93395")
        assertEquals(JobState.CANCELLED, job.state)
        assertEquals("cancelled", job.error!!.code)
    }

    @Test
    fun `decodes a real interrupted job after a backend restart`() {
        val transport = FakeHttpTransport { HttpResponseSpec(200, emptyMap(), INTERRUPTED_JOB_FIXTURE) }
        val job = SecurityBackendClient(transport, config()).jobStatus("job_4dc5d9ef6b3840f2af930139cb917460")
        assertEquals(JobState.INTERRUPTED, job.state)
        assertTrue(job.state in JobState.TERMINAL)
        assertTrue(job.error!!.message.contains("outcome on the tool side is unknown"))
    }

    @Test
    fun `unauthorized response becomes a SecurityBackendException with code unauthorized`() {
        val transport = FakeHttpTransport { HttpResponseSpec(401, emptyMap(), UNAUTHORIZED_FIXTURE) }
        val e = assertFailsWith<SecurityBackendException> { SecurityBackendClient(transport, config()).capabilities() }
        assertEquals("unauthorized", e.code)
    }

    @Test
    fun `unknown job response becomes unknown_job, not a generic failure`() {
        val transport = FakeHttpTransport { HttpResponseSpec(404, emptyMap(), UNKNOWN_JOB_FIXTURE) }
        val e = assertFailsWith<SecurityBackendException> { SecurityBackendClient(transport, config()).jobStatus("job_doesnotexist") }
        assertEquals("unknown_job", e.code)
    }

    @Test
    fun `idempotency conflict surfaces its real code, never silently resubmitted`() {
        val transport = FakeHttpTransport { HttpResponseSpec(409, emptyMap(), IDEMPOTENCY_CONFLICT_FIXTURE) }
        val e = assertFailsWith<SecurityBackendException> {
            SecurityBackendClient(transport, config()).submitJob(
                "setoolkit.attack", "qr-test-1",
                buildJsonObject { put("attack_vector", "QRCODE_GENERATOR") }, 180_000,
            )
        }
        assertEquals("idempotency_conflict", e.code)
    }

    @Test
    fun `an actual IOException from the transport is reported as transport_error, not thrown raw`() {
        val transport = FakeHttpTransport { throw java.io.IOException("connection refused") }
        val e = assertFailsWith<SecurityBackendException> { SecurityBackendClient(transport, config()).capabilities() }
        assertEquals("transport_error", e.code)
    }

    @Test
    fun `submitJob sends op idempotency_key and params in the body`() {
        var sentBody: String? = null
        val transport = FakeHttpTransport { req ->
            sentBody = req.body
            HttpResponseSpec(202, emptyMap(), SET_JOB_COMPLETED_FIXTURE)
        }
        SecurityBackendClient(transport, config()).submitJob(
            "setoolkit.attack", "qr-test-1",
            buildJsonObject {
                put("attack_vector", "QRCODE_GENERATOR")
                put("target_host", "https://example.com")
            },
            180_000,
        )
        requireNotNull(sentBody)
        assertTrue(sentBody!!.contains("\"op\":\"setoolkit.attack\""))
        assertTrue(sentBody!!.contains("\"idempotency_key\":\"qr-test-1\""))
        assertTrue(sentBody!!.contains("QRCODE_GENERATOR"))
    }

    @Test
    fun `jobStatus resumes from the given cursor`() {
        var requestedPath: String? = null
        val transport = FakeHttpTransport { req ->
            requestedPath = req.url
            HttpResponseSpec(200, emptyMap(), METASPLOIT_JOB_COMPLETED_FIXTURE)
        }
        SecurityBackendClient(transport, config()).jobStatus("job_x", sinceCursor = 7)
        assertTrue(requestedPath!!.endsWith("since=7"))
    }
}
