package ai.droidcommand.integration

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.bots.ScopedToolRunner
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.openai.testing.StrictChatServer
import ai.droidcommand.llm.openai.testing.StrictReply
import ai.droidcommand.llm.openai.testing.StrictRequest
import ai.droidcommand.llm.pchost.PcHostAddress
import ai.droidcommand.llm.pchost.PcHostAddressParse
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostTarget
import ai.droidcommand.llm.pchost.PcHostTimeouts
import ai.droidcommand.llm.pchost.PcToolSupport
import ai.droidcommand.remote.HttpUrlConnectionTransport
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A helper bot's tools on a PC-hosted model, through the pieces the app wires together: [ChatSession.sendPcHost] →
 * the bot's [ScopedToolRunner] → the real [SecureToolExecutor] (policy, approval prompt, audit log) → a tool.
 *
 * The model is a controlled server ([StrictChatServer]) that refuses a malformed tool transcript with HTTP 400, over
 * the phone's own transport. It is not a model and proves nothing about what a real one chooses to call; it proves
 * what the app does with each kind of answer, and that everything it sends back is a valid exchange.
 */
class HelperBotPcToolsIntegrationTest {
    private class RecordingTool(override val spec: ToolSpec, private val onRun: (Map<String, String>) -> ToolResult) : Tool {
        val inputs = mutableListOf<Map<String, String>>()

        override fun execute(input: Map<String, String>): ToolResult {
            inputs += input
            return onRun(input)
        }
    }

    private class Harness(
        approve: Boolean = true,
        allowed: Set<String> = setOf("clock", "note_write"),
        clockRun: (Harness) -> ToolResult = { ToolResult.Success("12:00") },
    ) {
        val registry = ToolRegistry()
        val audit = InMemoryAuditLog()
        val approvals = mutableListOf<Pair<String, Map<String, String>>>()
        val stop = AtomicBoolean(false)
        val store = InMemoryConversationStore()

        val clock = RecordingTool(ToolSpec(name = "clock", description = "Tells the time")) { clockRun(this) }
        val noteWrite = RecordingTool(ToolSpec(name = "note_write", description = "Saves a note", securityLevel = SecurityLevel.SENSITIVE)) { ToolResult.Success("saved") }
        val shell = RecordingTool(ToolSpec(name = "shell", description = "Runs a command", securityLevel = SecurityLevel.SENSITIVE)) { ToolResult.Success("ran") }

        private val stateMachine = AgentStateMachine()
        private val secure = SecureToolExecutor(
            registry,
            ToolExecutor(registry, stateMachine, sleep = { }),
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy()),
            object : ApprovalPrompt {
                override fun requestApproval(reason: String): Boolean = approve

                override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
                    approvals += toolName to input
                    return approve
                }
            },
            auditLog = audit,
        )

        val session = ChatSession(
            InMemorySecretsVault(),
            store,
            transport = HttpUrlConnectionTransport(),
            pcHostTimeouts = PcHostTimeouts(connectMillis = 2_000, readMillis = 5_000),
        )

        init {
            listOf(clock, noteWrite, shell).forEach(registry::register)
            // Exactly what ChatViewModel.pickUpBot does through HelperBotTools.
            session.setToolAccess(ScopedToolRunner(secure, allowed), registry.list().filter { it.name in allowed })
        }

        fun send(server: StrictChatServer, kind: PcHostKind = PcHostKind.OPENAI_COMPATIBLE, support: PcToolSupport = PcToolSupport.SUPPORTED, forced: Boolean = false) =
            session.sendPcHost(
                PcHostTarget(kind, (PcHostAddress.parse(kind, server.baseUrl) as PcHostAddressParse.Ok).address, "qwen2.5:7b", toolSupport = support, toolsForced = forced),
                "what time is it? save it as a note",
                cancelled = stop::get,
            )

        fun auditTypes(tool: String) = audit.all().filter { it.subject == tool }.map { it.type }
    }

    private fun call(id: String, name: String, arguments: String = "{}") = StrictReply.Call(id, name, arguments)

    private fun StrictRequest.toolResults(): Map<String, String> =
        messages.filter { (it["role"] as JsonPrimitive).content == "tool" }
            .associate { (it["tool_call_id"] as JsonPrimitive).content to (it["content"] as JsonPrimitive).content }

    private fun assertNothingSaved(h: Harness) {
        assertTrue(h.session.history().isEmpty())
        assertNull(h.store.load(ChatSession.DEFAULT_CONVERSATION_ID))
    }

    @Test
    fun `allowed calls run through approval and audit, and the model continues from their results`() {
        for (kind in PcHostKind.entries) {
            val h = Harness()
            StrictChatServer { request ->
                when (request.toolResults().size) {
                    0 -> StrictReply.ToolCalls(listOf(call("call_1", "clock"), call("call_2", "note_write", """{"text":"It is noon","tags":["time"]}""")), text = "One moment.")
                    else -> StrictReply.Text("It is 12:00 and I saved the note.")
                }
            }.use { server ->
                val reply = assertIs<ChatResult.Reply>(h.send(server, kind))
                assertEquals("It is 12:00 and I saved the note.", reply.text)
                assertNull(reply.notice)

                assertTrue(server.rejections.isEmpty(), server.rejections.toString())
                assertEquals(2, server.requests.size)
                assertEquals(listOf("clock", "note_write"), server.requests[0].toolNames, "only the bot's allow-list is offered; 'shell' is not")
                assertEquals(mapOf("call_1" to "12:00", "call_2" to "saved"), server.requests[1].toolResults())
                val assistant = server.requests[1].messages.single { (it["role"] as JsonPrimitive).content == "assistant" }
                assertEquals("One moment.", (assistant["content"] as JsonPrimitive).content)
                assertEquals(listOf("call_1", "call_2"), (assistant["tool_calls"] as JsonArray).map { ((it as JsonObject)["id"] as JsonPrimitive).content })

                assertEquals(1, h.clock.inputs.size)
                assertEquals(listOf(mapOf("text" to "It is noon", "tags" to """["time"]""")), h.noteWrite.inputs)
                assertEquals(listOf("note_write" to mapOf("text" to "It is noon", "tags" to """["time"]""")), h.approvals, "the prompt shows the exact input; the NORMAL tool needs none")
                assertTrue(AuditEventType.ACCESS_GRANTED in h.auditTypes("note_write"), h.audit.all().toString())

                // Only the user's message and the final text are stored: saved conversations keep their shape.
                assertEquals(listOf(Role.USER, Role.ASSISTANT), h.session.history().map { it.role })
                assertTrue(h.session.history().all { it.toolCalls.isEmpty() && it.toolCallId == null })
            }
        }
    }

    @Test
    fun `a denied approval runs nothing, is audited, and the model is told and carries on`() {
        val h = Harness(approve = false)
        StrictChatServer { request ->
            if (request.toolResults().isEmpty()) StrictReply.ToolCalls(listOf(call("call_1", "note_write", """{"text":"x"}"""))) else StrictReply.Text("I could not save the note: it was not approved.")
        }.use { server ->
            val reply = assertIs<ChatResult.Reply>(h.send(server))
            assertEquals("I could not save the note: it was not approved.", reply.text)
            assertTrue(h.noteWrite.inputs.isEmpty(), "a denied tool must not run")
            assertEquals(1, h.approvals.size)
            assertTrue(AuditEventType.ACCESS_DENIED in h.auditTypes("note_write"), h.audit.all().toString())
            assertTrue(server.requests[1].toolResults().getValue("call_1").startsWith("ERROR:"), server.requests[1].toolResults().toString())
            assertTrue(server.rejections.isEmpty(), server.rejections.toString())
        }
    }

    @Test
    fun `a tool the bot may not use is answered with an error without reaching approval or the tool`() {
        val h = Harness()
        StrictChatServer { request ->
            if (request.toolResults().isEmpty()) {
                StrictReply.ToolCalls(listOf(call("call_1", "shell", """{"command":"id"}"""), call("call_2", "no_such_tool"), call("call_3", "clock")))
            } else {
                StrictReply.Text("Only the clock worked.")
            }
        }.use { server ->
            assertIs<ChatResult.Reply>(h.send(server))
            val results = server.requests[1].toolResults()
            assertTrue(results.getValue("call_1").contains("'shell' is not available"), results.toString())
            assertTrue(results.getValue("call_2").contains("'no_such_tool' is not available"), results.toString())
            assertEquals("12:00", results.getValue("call_3"))
            assertTrue(h.shell.inputs.isEmpty())
            assertTrue(h.approvals.isEmpty(), "a registered but not allowed tool never reaches the approval prompt")
            assertTrue(h.audit.all().none { it.subject == "shell" || it.subject == "no_such_tool" })
            assertTrue(server.rejections.isEmpty(), server.rejections.toString())
        }
    }

    @Test
    fun `the bot's scoped runner refuses a tool that is offered but not on its list`() {
        // The offered list and the allow-list are built from the same set in the app; this keeps them apart on purpose
        // to show the runner is a second, independent check.
        val h = Harness()
        h.session.setToolAccess(ScopedToolRunner(SecureRunnerProbe.unreachable, setOf("clock")), h.registry.list().filter { it.name == "shell" })
        StrictChatServer { request ->
            if (request.toolResults().isEmpty()) StrictReply.ToolCalls(listOf(call("call_1", "shell"))) else StrictReply.Text("Refused.")
        }.use { server ->
            assertIs<ChatResult.Reply>(h.send(server))
            assertTrue(server.requests[1].toolResults().getValue("call_1").contains("not in this bot's allowed-tool list"))
            assertTrue(h.shell.inputs.isEmpty())
        }
    }

    @Test
    fun `malformed arguments run nothing and save nothing`() {
        val h = Harness()
        StrictChatServer { StrictReply.ToolCalls(listOf(call("call_1", "clock"), call("call_2", "note_write", """{"text": unquoted}"""))) }.use { server ->
            val failure = assertIs<ChatResult.Failure>(h.send(server))
            assertTrue(failure.message.contains("Malformed tool_call arguments for 'note_write'"), failure.message)
            assertTrue(h.clock.inputs.isEmpty(), "the well-formed call of a malformed response must not run either")
            assertTrue(h.noteWrite.inputs.isEmpty())
            assertTrue(h.approvals.isEmpty())
            assertEquals(1, server.requests.size, "nothing is sent again")
            assertNothingSaved(h)
        }
    }

    @Test
    fun `a tool revoked while the turn runs is not run, and the model is told`() {
        // Revocation the way McpToolCatalog does it: the tool leaves the registry. Here the first call triggers it.
        val h = Harness(clockRun = {
            it.registry.unregister("note_write")
            ToolResult.Success("12:00")
        })
        StrictChatServer { request ->
            if (request.toolResults().isEmpty()) StrictReply.ToolCalls(listOf(call("call_1", "clock"), call("call_2", "note_write", """{"text":"x"}"""))) else StrictReply.Text("The note tool is gone.")
        }.use { server ->
            assertIs<ChatResult.Reply>(h.send(server))
            assertEquals("12:00", server.requests[1].toolResults().getValue("call_1"))
            assertTrue(server.requests[1].toolResults().getValue("call_2").startsWith("ERROR:"))
            assertTrue(h.noteWrite.inputs.isEmpty())
            assertTrue(h.approvals.isEmpty())
            assertTrue(server.rejections.isEmpty(), server.rejections.toString())
        }
    }

    @Test
    fun `clearing tool access stops tools being offered on the next turn`() {
        val h = Harness()
        StrictChatServer { request -> if (request.toolNames.isEmpty()) StrictReply.Text("plain") else StrictReply.ToolCalls(listOf(call("c", "clock"))) }.use { server ->
            h.session.setToolAccess(null)
            assertEquals("plain", assertIs<ChatResult.Reply>(h.send(server)).text)
            assertTrue(server.requests.single().body["tools"] == null)
            assertTrue(h.clock.inputs.isEmpty())
        }
    }

    @Test
    fun `stopping between two calls runs no more of them, saves nothing, and says what already ran`() {
        val h = Harness(clockRun = {
            it.stop.set(true)
            ToolResult.Success("12:00")
        })
        StrictChatServer { StrictReply.ToolCalls(listOf(call("call_1", "clock"), call("call_2", "note_write", """{"text":"x"}"""))) }.use { server ->
            val failure = assertIs<ChatResult.Failure>(h.send(server))
            assertTrue(failure.message.startsWith(ChatSession.STOPPED_MESSAGE), failure.message)
            assertTrue(failure.message.contains("1 tool action had already run in this turn (clock)"), failure.message)
            assertTrue(failure.message.contains("Nothing is repeated automatically"), failure.message)
            assertEquals(1, h.clock.inputs.size)
            assertTrue(h.noteWrite.inputs.isEmpty())
            assertTrue(h.approvals.isEmpty())
            assertEquals(1, server.requests.size, "no request follows a stop")
            assertNothingSaved(h)
        }
    }

    @Test
    fun `a request that fails after a tool ran is not repeated and the action is not run a second time`() {
        val h = Harness()
        StrictChatServer { request ->
            if (request.toolResults().isEmpty()) StrictReply.ToolCalls(listOf(call("call_1", "note_write", """{"text":"x"}"""))) else StrictReply.Http(500, """{"error":{"message":"out of memory"}}""")
        }.use { server ->
            val failure = assertIs<ChatResult.Failure>(h.send(server))
            assertTrue(failure.message.contains("out of memory"), failure.message)
            assertTrue(failure.message.contains("1 tool action had already run in this turn (note_write)"), failure.message)
            assertEquals(1, h.noteWrite.inputs.size, "the action ran once")
            assertEquals(2, server.requests.size, "the failed request was not sent again")
            assertNothingSaved(h)
        }
    }

    @Test
    fun `tools are offered only when the server reported support or the owner switched them on`() {
        for ((support, forced, offered) in listOf(
            Triple(PcToolSupport.UNKNOWN, false, false),
            Triple(PcToolSupport.UNSUPPORTED, false, false),
            Triple(PcToolSupport.SUPPORTED, false, true),
            Triple(PcToolSupport.UNKNOWN, true, true),
        )) {
            val h = Harness()
            StrictChatServer { StrictReply.Text("hello") }.use { server ->
                val reply = assertIs<ChatResult.Reply>(h.send(server, support = support, forced = forced))
                assertEquals(offered, server.requests.single().toolNames.isNotEmpty(), "$support forced=$forced")
                if (offered) assertNull(reply.notice) else assertTrue(reply.notice!!.contains("without the helper bot's tools"), reply.notice)
            }
        }
    }

    @Test
    fun `a server that refuses tools gets a clear message, nothing runs, and no tool-less retry is made`() {
        val h = Harness()
        StrictChatServer { StrictReply.Http(400, """{"error":{"message":"registry.ollama.ai/library/gemma3:4b does not support tools"}}""") }.use { server ->
            val failure = assertIs<ChatResult.Failure>(h.send(server, PcHostKind.OLLAMA, support = PcToolSupport.UNKNOWN, forced = true))
            assertTrue(failure.message.contains("refused a request that offered the helper bot's tools (HTTP 400)"), failure.message)
            assertTrue(failure.message.contains("does not support tools"), failure.message)
            assertTrue(failure.message.contains("Offer tools anyway"), failure.message)
            assertEquals(1, server.requests.size)
            assertTrue(h.clock.inputs.isEmpty() && h.noteWrite.inputs.isEmpty())
            assertNothingSaved(h)
        }
    }

    /** A runner that fails the test if it is ever reached. */
    private object SecureRunnerProbe {
        val unreachable = object : ai.droidcommand.agent.ToolRunner {
            override fun run(
                toolName: String,
                input: Map<String, String>,
                retryPolicy: ai.droidcommand.agent.RetryPolicy,
                isCancelled: () -> Boolean,
                mode: ai.droidcommand.agent.AgentMode?,
                initiator: ai.droidcommand.agent.Initiator?,
                grantId: String?,
            ): ToolResult = error("the delegate must not be reached for '$toolName'")
        }
    }
}
