package ai.droidcommand.hackerai

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.remotecontrol.FleetRemoteEnvelope
import ai.droidcommand.remotecontrol.HackerAiEnvelopeChannel
import ai.droidcommand.remotecontrol.HackerAiEnvelopeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HackerAiToolTest {
    private val now = 20_000_000L

    private class FakeChannel(
        override val connected: Boolean = true,
        var envelopeReply: String = """{"ok":true}""",
        var taskReply: String = """{"status":"done"}""",
    ) : HackerAiEnvelopeChannel {
        val envelopes = mutableListOf<String>()
        val taskIds = mutableListOf<String>()

        override fun executeEnvelope(envelopeJson: String): String {
            envelopes += envelopeJson
            return envelopeReply
        }

        override fun getTaskResult(taskId: String): String {
            taskIds += taskId
            return taskReply
        }
    }

    @Test
    fun `default tool reports not connected`() {
        val result = HackerAiTool().execute(mapOf("task" to "find xss"))
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("not connected"), failure.reason)
    }

    @Test
    fun `bound tool does not report not connected for agent run`() {
        val channel = FakeChannel()
        val result = HackerAiTool(channel, nowMs = { now }).execute(mapOf("task" to "find xss"))
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.startsWith("confirmation_required"), failure.reason)
        assertTrue(channel.envelopes.isEmpty())
    }

    @Test
    fun `bound tool dispatches capabilities get through executeEnvelope`() {
        val channel = FakeChannel()
        val result = HackerAiTool(channel, nowMs = { now }).execute(
            mapOf(
                "actionRef" to FleetRemoteEnvelope.OP_CAPABILITIES_GET,
                "targetNodeRef" to HackerAiEnvelopeTransport.TARGET_HACKERAI,
            ),
        )
        val success = assertIs<ToolResult.Success>(result)
        assertEquals("""{"ok":true}""", success.output)
        assertEquals(1, channel.envelopes.size)
        assertTrue(channel.envelopes.single().contains("\"command\":\"capabilities.get\""))
        assertTrue(channel.taskIds.isEmpty())
    }

    @Test
    fun `taskId without envelope polls getTaskResult once`() {
        val channel = FakeChannel(taskReply = """{"status":"running","result":"wait"}""")
        val result = HackerAiTool(channel, nowMs = { now }).execute(mapOf("taskId" to "task-9"))
        val success = assertIs<ToolResult.Success>(result)
        assertEquals("""{"status":"running","result":"wait"}""", success.output)
        assertEquals(listOf("task-9"), channel.taskIds)
        assertTrue(channel.envelopes.isEmpty())
    }

    @Test
    fun `disconnected injected channel still reports not connected`() {
        val result = HackerAiTool(FakeChannel(connected = false)).execute(
            mapOf("actionRef" to FleetRemoteEnvelope.OP_CAPABILITIES_GET),
        )
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("not connected"))
    }
}
