package ai.droidcommand.shizuku.bridge

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeTransport(
    var status: BridgeStatus,
    var respond: (BridgeRequestSpec) -> BridgeResponse? = { BridgeResponse(it.requestId, "OK", data = mapOf("value" to "1")) },
) : BridgeTransport {
    val submitted = ArrayList<BridgeRequestSpec>()
    var cancelled = 0
    override fun negotiate() = status
    override fun submit(request: BridgeRequestSpec): BridgeCall {
        submitted += request
        return object : BridgeCall {
            override val requestId = request.requestId
            override fun await(timeoutMs: Long) = respond(request)
            override fun cancel(): Boolean {
                cancelled++
                return true
            }
        }
    }
}

private fun granted(vararg caps: BridgeCapability, policy: BridgeBackend = BridgeBackend.ANY) = BridgeStatus(
    available = true, apiVersion = 1, execMode = "adb", access = BridgeAccess.SCOPED, held = caps.toSet(),
    supportedOperations = BridgeOperation.entries.map { it.id }.toSet(), servicePolicy = policy,
)

class BridgeToolsTest {
    private fun tool(t: BridgeTransport, op: BridgeOperation, backend: BridgeBackend = BridgeBackend.ANY) =
        BridgeTool(t, op, backend, timeoutMs = 50) { "req-1" }

    @Test fun `unavailable service fails with a start hint and sends nothing`() {
        val t = FakeTransport(BridgeStatus.UNAVAILABLE)
        val r = tool(t, BridgeOperation.DIAGNOSTICS).execute(emptyMap())
        assertIs<ToolResult.Failure>(r)
        assertTrue("not running" in r.reason)
        assertTrue(t.submitted.isEmpty())
    }

    @Test fun `capability not held is reported as not granted and never submitted`() {
        val t = FakeTransport(granted(BridgeCapability.FILES_READ))
        val r = tool(t, BridgeOperation.SHELL_EXEC).execute(mapOf("command" to "id"))
        assertIs<ToolResult.Failure>(r)
        assertTrue("not granted" in r.reason && "shell.exec" in r.reason)
        assertTrue(t.submitted.isEmpty())
    }

    @Test fun `scoped grant never makes shell reachable even with every other capability`() {
        val all = BridgeCapability.entries.filter { it != BridgeCapability.SHELL_EXEC }.toTypedArray()
        val t = FakeTransport(granted(*all))
        assertIs<ToolResult.Failure>(tool(t, BridgeOperation.SHELL_EXEC).execute(mapOf("command" to "id")))
        assertTrue(t.submitted.isEmpty())
    }

    @Test fun `unsupported operation on an older service is explained`() {
        val t = FakeTransport(granted(BridgeCapability.DIAGNOSTICS_READ).copy(supportedOperations = emptySet()))
        val r = tool(t, BridgeOperation.DIAGNOSTICS).execute(emptyMap())
        assertIs<ToolResult.Failure>(r)
        assertTrue("does not support" in r.reason)
    }

    @Test fun `wrong contract version is refused`() {
        val t = FakeTransport(granted(BridgeCapability.DIAGNOSTICS_READ).copy(apiVersion = 2))
        assertTrue((tool(t, BridgeOperation.DIAGNOSTICS).execute(emptyMap()) as ToolResult.Failure).reason.contains("Unsupported"))
    }

    @Test fun `arguments are typed and validated before submit`() {
        val t = FakeTransport(granted(BridgeCapability.FILES_WRITE))
        val w = tool(t, BridgeOperation.FILES_WRITE)
        assertIs<ToolResult.Failure>(w.execute(mapOf("path" to "/x"))) // missing data
        assertIs<ToolResult.Failure>(w.execute(mapOf("path" to "/x", "data" to "***"))) // not base64
        assertIs<ToolResult.Failure>(w.execute(mapOf("path" to "/x", "data" to "AA==", "offset" to "abc"))) // not integer
        assertTrue(t.submitted.isEmpty())
        assertIs<ToolResult.Success>(w.execute(mapOf("path" to "/x", "data" to Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), "offset" to "4", "truncate" to "true")))
        val sent = t.submitted.single()
        assertEquals("files.write", sent.operation)
        assertEquals(4L, sent.args["offset"])
        assertEquals(true, sent.args["truncate"])
        assertTrue((sent.args["data"] as ByteArray).contentEquals(byteArrayOf(1, 2)))
    }

    @Test fun `remote task id is passed as correlation only and does not authorise`() {
        val t = FakeTransport(granted(BridgeCapability.DIAGNOSTICS_READ))
        tool(t, BridgeOperation.DIAGNOSTICS).execute(mapOf("remote_task_id" to "task-9"))
        assertEquals("task-9", t.submitted.single().remoteTaskId)
        val denied = FakeTransport(BridgeStatus(true, 1, "adb", BridgeAccess.NONE, emptySet(), BridgeOperation.entries.map { it.id }.toSet()))
        assertIs<ToolResult.Failure>(tool(denied, BridgeOperation.DIAGNOSTICS).execute(mapOf("remote_task_id" to "task-9")))
        assertTrue(denied.submitted.isEmpty())
    }

    @Test fun `structured success carries request id duration and data`() {
        val t = FakeTransport(granted(BridgeCapability.SETTINGS_READ)) { BridgeResponse(it.requestId, "OK", data = mapOf("value" to "5", "n" to 3L), durationMs = 12, execMode = "adb") }
        val r = tool(t, BridgeOperation.SETTINGS_GET).execute(mapOf("namespace" to "system", "key" to "screen_brightness"))
        assertIs<ToolResult.Success>(r)
        assertTrue("\"request_id\":\"req-1\"" in r.output && "\"duration_ms\":12" in r.output && "\"value\":\"5\"" in r.output && "\"exec_mode\":\"adb\"" in r.output)
    }

    @Test fun `bridge errors keep their category and request id`() {
        for (e in listOf(BridgeError.REVOKED, BridgeError.EXPIRED, BridgeError.PATH_OUT_OF_SCOPE, BridgeError.APPROVAL_DENIED, BridgeError.BACKEND_POLICY)) {
            val t = FakeTransport(granted(BridgeCapability.FILES_READ)) { BridgeResponse(it.requestId, "ERROR", e, "m") }
            val r = tool(t, BridgeOperation.FILES_STAT).execute(mapOf("path" to "/x"))
            assertIs<ToolResult.Failure>(r)
            assertTrue(e.name in r.reason && "req-1" in r.reason)
        }
    }

    @Test fun `cancelled result is reported as cancelled`() {
        val t = FakeTransport(granted(BridgeCapability.FILES_READ)) { BridgeResponse(it.requestId, "CANCELLED", BridgeError.REVOKED) }
        val r = tool(t, BridgeOperation.FILES_STAT).execute(mapOf("path" to "/x"))
        assertIs<ToolResult.Failure>(r)
        assertTrue("Cancelled" in r.reason && "REVOKED" in r.reason)
    }

    @Test fun `local timeout requests remote cancellation`() {
        val t = FakeTransport(granted(BridgeCapability.FILES_READ)) { null }
        val r = tool(t, BridgeOperation.FILES_STAT).execute(mapOf("path" to "/x"))
        assertIs<ToolResult.Failure>(r)
        assertTrue("Timed out" in r.reason)
        assertEquals(1, t.cancelled)
    }

    @Test fun `service policy that conflicts with the requested backend fails before submit`() {
        val t = FakeTransport(granted(BridgeCapability.DIAGNOSTICS_READ, policy = BridgeBackend.ADB_ONLY))
        val r = tool(t, BridgeOperation.DIAGNOSTICS, BridgeBackend.ROOT_ONLY).execute(emptyMap())
        assertIs<ToolResult.Failure>(r)
        assertTrue("policy" in r.reason)
        assertTrue(t.submitted.isEmpty())
    }

    @Test fun `specs - mutating and shell tools always confirm and reads of files confirm too`() {
        BridgeTool.all(UnavailableBridgeTransport).forEach {
            val op = it.operation
            assertTrue(it.spec.name.startsWith("victorious_bridge_"), it.spec.name)
            if (op.mutating) {
                assertEquals(SecurityLevel.SENSITIVE, it.spec.securityLevel)
                assertTrue(it.spec.requiresConfirmation)
            }
            if (op.capability == BridgeCapability.FILES_READ) assertTrue(it.spec.requiresConfirmation)
            if (op == BridgeOperation.SHELL_EXEC) {
                assertEquals(PermissionCategory.TERMINAL, it.spec.permissionCategory)
                assertTrue("shell" in it.spec.name)
            }
        }
        assertEquals(BridgeOperation.entries.size, BridgeTool.all(UnavailableBridgeTransport).map { it.spec.name }.toSet().size)
    }

    @Test fun `unavailable transport yields a clean failure through the tool`() {
        val r = BridgeTool(UnavailableBridgeTransport, BridgeOperation.PACKAGES_LIST).execute(emptyMap())
        assertIs<ToolResult.Failure>(r)
    }
}
