package ai.droidcommand.remotecontrol

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HackerAiEnvelopeTransportTest {
    private val now = 10_000_000L

    private class FakeChannel(
        override val connected: Boolean = true,
        var envelopeReply: String = """{"ok":true,"status":"done"}""",
        var taskReply: String = """{"status":"done","result":"ok"}""",
    ) : HackerAiEnvelopeChannel {
        val envelopes = mutableListOf<String>()
        val taskIds = mutableListOf<String>()

        override fun executeEnvelope(envelopeJson: String): String {
            envelopes += envelopeJson
            if (envelopeReply == "throw") error("binder died")
            return envelopeReply
        }

        override fun getTaskResult(taskId: String): String {
            taskIds += taskId
            if (taskReply == "throw") error("binder died")
            return taskReply
        }
    }

    private fun transport(channel: FakeChannel) =
        HackerAiEnvelopeTransport(channel, nowMs = { now })

    @Test
    fun `disconnected channel is not connected`() {
        val result = transport(FakeChannel(connected = false)).submit(
            envelope(
                actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
                targetNodeRef = HackerAiEnvelopeTransport.TARGET_HACKERAI,
                timestampMs = now,
            ),
        )
        assertIs<HackerAiEnvelopeResult.NotConnected>(result)
        assertTrue(result.reason.contains("not connected"))
        assertIs<ToolResult.Failure>(result.toToolResult())
    }

    @Test
    fun `destructive actionRefs are confirmation_required and do not call executeEnvelope`() {
        val channel = FakeChannel()
        for (actionRef in HackerAiEnvelopeTransport.CONFIRMATION_REQUIRED_ACTION_REFS) {
            val result = transport(channel).submit(
                envelope(
                    actionRef = actionRef,
                    targetNodeRef = HackerAiEnvelopeTransport.TARGET_HACKERAI,
                    timestampMs = now,
                ),
            )
            val rejected = assertIs<HackerAiEnvelopeResult.Rejected>(result)
            assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, rejected.category)
            assertTrue(rejected.reason.contains("confirmation_required"), rejected.reason)
            val preparedJson = rejected.preparedJson
            assertNotNull(preparedJson)
            val prepared = BoundCompanionRemoteBinding.decodeJson(preparedJson)
            assertEquals(actionRef, prepared.actionRef)
            assertEquals(HackerAiEnvelopeTransport.TARGET_HACKERAI, prepared.targetNodeRef)
            assertFalse(preparedJson.contains("\"auth\""), preparedJson)
        }
        assertTrue(channel.envelopes.isEmpty(), "destructive actionRefs must not auto-execute")
        assertTrue(channel.taskIds.isEmpty())
    }

    @Test
    fun `capabilities get calls executeEnvelope with BoundCompanionCommand JSON`() {
        val channel = FakeChannel()
        val env = envelope(
            requestId = "req-hai-1",
            actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            targetNodeRef = HackerAiEnvelopeTransport.TARGET_HACKERAI,
            timestampMs = now,
        )
        val result = assertIs<HackerAiEnvelopeResult.Dispatched>(transport(channel).submit(env))
        assertEquals("""{"ok":true,"status":"done"}""", result.replyJson)
        assertEquals(1, channel.envelopes.size)
        val sent = channel.envelopes.single()
        assertTrue(sent.contains("\"requestId\":\"req-hai-1\""), sent)
        assertTrue(sent.contains("\"command\":\"capabilities.get\""), sent)
        assertTrue(sent.contains("\"targetNodeRef\":\"hackerai\""), sent)
        assertTrue(sent.contains("\"nonce\""), sent)
        assertTrue(sent.contains("\"timestampMs\":$now"), sent)
        assertTrue(sent.contains("\"ttlMs\""), sent)
        assertTrue(sent.contains("\"destructive\""), sent)
        assertTrue(sent.contains("\"argsJson\""), sent)
        assertFalse(sent.contains("\"auth\""), sent)
        assertFalse(sent.contains("\"actionRef\""), sent)
        val decoded = BoundCompanionRemoteBinding.decodeJson(sent)
        assertEquals(env.requestId, decoded.requestId)
        assertEquals(env.actionRef, decoded.actionRef)
        assertEquals(env.nonce, decoded.nonce)
        assertTrue(channel.taskIds.isEmpty())
    }

    @Test
    fun `node status get is dispatched and not confirmation gated`() {
        val channel = FakeChannel()
        val result = transport(channel).submit(
            envelope(
                actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
                targetNodeRef = HackerAiEnvelopeTransport.TARGET_HACKERAI,
                timestampMs = now,
            ),
        )
        assertIs<HackerAiEnvelopeResult.Dispatched>(result)
        assertEquals(1, channel.envelopes.size)
    }

    @Test
    fun `expired envelope is not sent`() {
        val channel = FakeChannel()
        val result = transport(channel).submit(
            envelope(
                actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
                timestampMs = now - 120_001,
                ttlMs = 120_000,
            ),
        )
        val rejected = assertIs<HackerAiEnvelopeResult.Rejected>(result)
        assertEquals(RemoteErrorCategory.EXPIRED, rejected.category)
        assertTrue(channel.envelopes.isEmpty())
    }

    @Test
    fun `pollTask calls getTaskResult once and never executeEnvelope`() {
        val channel = FakeChannel(taskReply = """{"status":"queued","result":"t-1"}""")
        val result = assertIs<HackerAiEnvelopeResult.Dispatched>(transport(channel).pollTask("t-1"))
        assertEquals("""{"status":"queued","result":"t-1"}""", result.replyJson)
        assertEquals(listOf("t-1"), channel.taskIds)
        assertTrue(channel.envelopes.isEmpty())
    }

    @Test
    fun `blank taskId is invalid_parameters`() {
        val channel = FakeChannel()
        val result = assertIs<HackerAiEnvelopeResult.Rejected>(transport(channel).pollTask("  "))
        assertEquals(RemoteErrorCategory.INVALID_PARAMETERS, result.category)
        assertTrue(channel.taskIds.isEmpty())
    }

    @Test
    fun `executeEnvelope binder death is an internal failure`() {
        val channel = FakeChannel(envelopeReply = "throw")
        val result = assertIs<HackerAiEnvelopeResult.Rejected>(
            transport(channel).submit(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
                    timestampMs = now,
                ),
            ),
        )
        assertEquals(RemoteErrorCategory.INTERNAL, result.category)
        assertTrue(result.reason.contains("binder died"), result.reason)
    }

    @Test
    fun `legacy tool input defaults to agent run and stays confirmation_required`() {
        val envelope = HackerAiEnvelopeTransport.envelopeFromToolInput(
            mapOf("task" to "find xss"),
            nowMs = now,
        )
        assertEquals(HackerAiEnvelopeTransport.ACTION_AGENT_RUN, envelope.actionRef)
        assertEquals("find xss", envelope.args["task"])
        assertEquals(HackerAiEnvelopeTransport.TARGET_HACKERAI, envelope.targetNodeRef)
        assertTrue(HackerAiEnvelopeTransport.isConfirmationRequired(envelope.actionRef))
        val channel = FakeChannel()
        val result = assertIs<HackerAiEnvelopeResult.Rejected>(transport(channel).submit(envelope))
        assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, result.category)
        assertTrue(channel.envelopes.isEmpty())
    }

    @Test
    fun `BoundCompanionCommand JSON input is accepted without inventing fields`() {
        val original = envelope(
            requestId = "r-json",
            actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            targetNodeRef = HackerAiEnvelopeTransport.TARGET_HACKERAI,
            timestampMs = now,
        )
        val json = BoundCompanionRemoteBinding.encodeJson(original)
        val parsed = HackerAiEnvelopeTransport.envelopeFromToolInput(
            mapOf("envelope" to json),
            nowMs = now,
        )
        assertEquals(original.requestId, parsed.requestId)
        assertEquals(original.actionRef, parsed.actionRef)
        assertEquals(original.nonce, parsed.nonce)
    }

    @Test
    fun `capability id maps skill catalog to capabilities get`() {
        assertEquals(
            FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            HackerAiEnvelopeTransport.defaultActionRefForCapability("ai.companion.hackerai.skill_catalog"),
        )
        assertEquals(
            HackerAiEnvelopeTransport.ACTION_FINDING_VALIDATE,
            HackerAiEnvelopeTransport.defaultActionRefForCapability("ai.companion.hackerai.validate_finding"),
        )
        assertEquals(
            HackerAiEnvelopeTransport.ACTION_AGENT_RUN,
            HackerAiEnvelopeTransport.defaultActionRefForCapability("ai.companion.hackerai.agent_task"),
        )
    }

    @Test
    fun `queuedTaskId reads a queued reply and ignores a done reply`() {
        assertEquals(
            "abc",
            HackerAiEnvelopeTransport.queuedTaskId("""{"status":"queued","taskId":"abc"}"""),
        )
        assertNull(HackerAiEnvelopeTransport.queuedTaskId("""{"status":"done","taskId":"abc"}"""))
        assertNull(HackerAiEnvelopeTransport.queuedTaskId("not-json"))
    }
}
