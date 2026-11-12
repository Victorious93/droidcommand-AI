package ai.droidcommand.llm.routing

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.pchost.PcHostAddress
import ai.droidcommand.llm.pchost.PcHostAddressParse
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostTarget
import ai.droidcommand.llm.pchost.PcHostTimeouts
import ai.droidcommand.llm.pchost.PcModelLocation
import ai.droidcommand.llm.routing.FakeModelServer.Companion.dropAfter
import ai.droidcommand.llm.routing.FakeModelServer.Companion.text
import ai.droidcommand.llm.routing.FakeModelServer.Companion.toolCalls
import ai.droidcommand.remote.HttpUrlConnectionTransport
import ai.droidcommand.security.AuditEventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A helper bot's tools on a PC-hosted turn ([ChatSession.sendPcHost]), through the phone's transport against a FAKE
 * model server on a real socket, and through the real allow-list, secure executor, approval prompt and audit log.
 * No real model: what the "model" asks for is scripted.
 */
class PcHostToolsTest {
    private val transport = HttpUrlConnectionTransport()
    private val store = InMemoryConversationStore()

    private fun session() = ChatSession(
        InMemorySecretsVault(),
        store,
        transport = transport,
        pcHostTimeouts = PcHostTimeouts(connectMillis = 2_000, readMillis = 5_000),
    )

    private fun target(server: FakeModelServer, kind: PcHostKind = PcHostKind.OLLAMA) =
        PcHostTarget(kind, (PcHostAddress.parse(kind, server.address) as PcHostAddressParse.Ok).address, server.model, PcModelLocation.SERVER_LOCAL)

    private fun body(request: ai.droidcommand.llm.pchost.FakePcServer.Request): JsonObject = Json.parseToJsonElement(request.body).jsonObject

    private fun messages(request: ai.droidcommand.llm.pchost.FakePcServer.Request): JsonArray = body(request)["messages"]!!.jsonArray

    @Test
    fun `two streamed tool calls run once each and go back with their exact ids and arguments`() {
        FakeModelServer().use { server ->
            val tools = SecureTools(setOf("note_add", "list_notes"))
            val add = tools.add(SecureTools.Counting("note_add"))
            val list = tools.add(SecureTools.Counting("list_notes"))
            // Arguments arrive in pieces, the two calls interleaved, exactly as a server streams them.
            server.chat += toolCalls(
                StreamedCall("call_Aa1-9", "note_add", listOf("{\"title\":", " \"milk\", ", "\"n\": 2}")),
                StreamedCall("call_Bb2_7", "list_notes", listOf("{", "}")),
            )
            server.chat += text("Added ", "milk.")
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add", "list_notes"))

            val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "add milk to my notes"))

            assertEquals("Added milk.", reply.text)
            assertNull(reply.notice)
            assertEquals(listOf(mapOf("title" to "milk", "n" to "2")), add.inputs.toList())
            assertEquals(1, list.inputs.size)
            assertEquals(listOf("note_add", "list_notes"), tools.approvalsAsked.toList(), "each call is approved through the normal prompt")
            assertEquals(2, tools.audit.all().count { it.type == AuditEventType.ACCESS_GRANTED }, "both runs are in the audit log")

            assertEquals(2, server.chats.size)
            val first = body(server.chats[0])
            assertEquals(listOf("note_add", "list_notes"), first["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
            val second = messages(server.chats[1])
            val assistant = second[second.size - 3].jsonObject
            assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
            val calls = assistant["tool_calls"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("call_Aa1-9", "call_Bb2_7"), calls.map { it["id"]!!.jsonPrimitive.content })
            assertEquals(
                "{\"title\": \"milk\", \"n\": 2}",
                calls[0]["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content,
                "the argument text is sent back exactly as the server streamed it",
            )
            val results = second.takeLast(2).map { it.jsonObject }
            assertEquals(listOf("tool", "tool"), results.map { it["role"]!!.jsonPrimitive.content })
            assertEquals(listOf("call_Aa1-9", "call_Bb2_7"), results.map { it["tool_call_id"]!!.jsonPrimitive.content })
            assertEquals("note_add done #1", results[0]["content"]!!.jsonPrimitive.content)

            // Only what was typed and the final text are saved.
            assertEquals(listOf(Role.USER to "add milk to my notes", Role.ASSISTANT to "Added milk."), chat.history().map { it.role to it.content })
        }
    }

    @Test
    fun `a denied approval runs nothing and the model is told`() {
        FakeModelServer().use { server ->
            val tools = SecureTools(setOf("note_add"), approve = { false })
            val add = tools.add(SecureTools.Counting("note_add"))
            server.chat += toolCalls(StreamedCall("call_1", "note_add", listOf("{\"title\":\"x\"}")))
            server.chat += text("I was not allowed to do that.")
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "add x"))

            assertEquals("I was not allowed to do that.", reply.text)
            assertTrue(add.inputs.isEmpty(), "a denied call must not run")
            assertEquals(1, tools.audit.all().count { it.type == AuditEventType.ACCESS_DENIED })
            val result = messages(server.chats[1]).last().jsonObject
            assertEquals("call_1", result["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue(result["content"]!!.jsonPrimitive.content.startsWith("ERROR:"), result.toString())
        }
    }

    @Test
    fun `a tool outside the bot's allow-list is refused before the approval prompt`() {
        FakeModelServer().use { server ->
            val tools = SecureTools(setOf("list_notes"))
            val add = tools.add(SecureTools.Counting("note_add"))
            tools.add(SecureTools.Counting("list_notes"))
            server.chat += toolCalls(StreamedCall("call_1", "note_add", listOf("{}")))
            server.chat += text("ok")
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("list_notes"))

            assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "add x"))

            assertTrue(add.inputs.isEmpty())
            assertTrue(tools.approvalsAsked.isEmpty(), "no approval is asked for a tool the bot may not use")
            assertTrue(messages(server.chats[1]).last().jsonObject["content"]!!.jsonPrimitive.content.contains("allowed-tool list"))
        }
    }

    @Test
    fun `a malformed call is not run, the rest of the reply's calls are, and the model is told which`() {
        FakeModelServer().use { server ->
            val tools = SecureTools(setOf("note_add", "list_notes"))
            val add = tools.add(SecureTools.Counting("note_add"))
            val list = tools.add(SecureTools.Counting("list_notes"))
            // The first call's arguments are cut off (not a JSON object); the server sent no id for the second.
            server.chat += toolCalls(
                StreamedCall("call_bad", "note_add", listOf("{\"title\": \"mil")),
                StreamedCall(null, "list_notes", listOf("{}")),
            )
            server.chat += text("done")
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add", "list_notes"))

            assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "go"))

            assertTrue(add.inputs.isEmpty(), "a call whose arguments cannot be read must not run")
            assertEquals(1, list.inputs.size)
            val sent = messages(server.chats[1])
            val results = sent.takeLast(2).map { it.jsonObject }
            assertEquals("call_bad", results[0]["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue(results[0]["content"]!!.jsonPrimitive.content.contains("was not run"), results[0].toString())
            // A missing id is replaced by one made here, used consistently for the call and its result.
            val madeId = sent[sent.size - 3].jsonObject["tool_calls"]!!.jsonArray[1].jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(madeId.isNotBlank())
            assertEquals(madeId, results[1]["tool_call_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `access revoked between two calls stops the second, the first is not undone`() {
        FakeModelServer().use { server ->
            val revoked = AtomicBoolean(false)
            val tools = SecureTools(setOf("note_add", "list_notes"), approve = { !revoked.get() })
            val add = tools.add(SecureTools.Counting("note_add") { revoked.set(true) })
            val list = tools.add(SecureTools.Counting("list_notes"))
            server.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{}")), StreamedCall("c2", "list_notes", listOf("{}")))
            server.chat += text("partly done")
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add", "list_notes"))

            assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "go"))

            assertEquals(1, add.inputs.size)
            assertTrue(list.inputs.isEmpty(), "the call after the revocation must not run")
            assertTrue(messages(server.chats[1]).last().jsonObject["content"]!!.jsonPrimitive.content.startsWith("ERROR:"))
        }
    }

    @Test
    fun `stopping during a tool round runs nothing further and saves nothing, and the next message works`() {
        FakeModelServer().use { server ->
            val stop = AtomicBoolean(false)
            val tools = SecureTools(setOf("note_add", "list_notes"))
            val add = tools.add(SecureTools.Counting("note_add") { stop.set(true) })
            val list = tools.add(SecureTools.Counting("list_notes"))
            server.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{}")), StreamedCall("c2", "list_notes", listOf("{}")))
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add", "list_notes"))

            assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), chat.sendPcHost(target(server), "go", cancelled = stop::get))

            assertEquals(1, add.inputs.size)
            assertTrue(list.inputs.isEmpty(), "a call after the stop must not run")
            assertTrue(chat.history().isEmpty())
            assertNull(store.load(ChatSession.DEFAULT_CONVERSATION_ID))
            assertEquals(1, server.chats.size, "no further request is made after the stop")

            // A new, different request right after: answered normally.
            stop.set(false)
            server.chat += text("hello again")
            val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "something else", cancelled = stop::get))
            assertEquals("hello again", reply.text)
            assertEquals(listOf("something else", "hello again"), chat.history().map { it.content })
        }
    }

    @Test
    fun `a connection lost after a tool ran does not run it again when the same message is sent again`() {
        FakeModelServer().use { server ->
            val tools = SecureTools(setOf("note_add"))
            val add = tools.add(SecureTools.Counting("note_add"))
            server.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{\"title\":\"milk\"}")))
            server.chat += dropAfter("Add") // the PC vanishes while writing the answer
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add"))

            val failure = assertIs<ChatResult.Failure>(chat.sendPcHost(target(server), "add milk"))
            assertTrue(failure.message.contains("was lost while the reply was arriving"), failure.message)
            assertTrue(failure.message.contains(ChatSession.TOOLS_KEPT_NOTICE), failure.message)
            assertEquals(1, add.inputs.size)
            assertTrue(chat.history().isEmpty())

            // Sent again. The request already shows the call and its result; a model that asks again anyway gets the stored result.
            server.chat += toolCalls(StreamedCall("c9", "note_add", listOf("{\"title\":\"milk\"}")))
            server.chat += text("Added milk.")
            val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "add milk"))

            assertEquals("Added milk.", reply.text)
            assertEquals(1, add.inputs.size, "the action must not run a second time")
            assertEquals(1, tools.approvalsAsked.size, "and no second approval is asked for it")
            val resent = messages(server.chats[2])
            assertEquals("c1", resent.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content, "the retry carries the completed call")
            assertEquals("note_add done #1", messages(server.chats[3]).last().jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(listOf("add milk", "Added milk."), chat.history().map { it.content })
        }
    }

    @Test
    fun `a model the server does not list tools for is not sent any, and the reply says why and what to do`() {
        FakeModelServer(capabilities = listOf("completion", "vision")).use { server ->
            val tools = SecureTools(setOf("note_add"))
            tools.add(SecureTools.Counting("note_add"))
            server.chat += text("I cannot add notes.")
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server), "add milk"))

            assertFalse(body(server.chats.single()).containsKey("tools"), "no tool schema is sent to a model that cannot use it")
            val notice = reply.notice.orEmpty()
            assertTrue(notice.startsWith(ChatSession.PC_HOST_TOOLS_NOTICE), notice)
            assertTrue(notice.contains("completion, vision"), notice)
            assertTrue(notice.contains(ChatSession.PC_HOST_TOOLS_ACTION), notice)
        }
    }

    @Test
    fun `a llama-cpp server is offered tools only when its chat template supports them`() {
        for (supports in listOf(true, false)) {
            FakeModelServer(llamaCpp = true, llamaSupportsTools = supports).use { server ->
                val tools = SecureTools(setOf("note_add"))
                val add = tools.add(SecureTools.Counting("note_add"))
                if (supports) server.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{}")))
                server.chat += text("fine")
                val chat = session()
                chat.setToolAccess(tools.runner, tools.specs("note_add"))

                val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server, PcHostKind.OPENAI_COMPATIBLE), "go"))

                assertEquals(supports, body(server.chats.first()).containsKey("tools"))
                assertEquals(if (supports) 1 else 0, add.inputs.size)
                assertEquals(supports, reply.notice == null, reply.notice)
            }
        }
    }

    @Test
    fun `a server that reports nothing about tools is treated as unsupported and named as such`() {
        FakeModelServer(reportsAnything = false).use { server ->
            val tools = SecureTools(setOf("note_add"))
            tools.add(SecureTools.Counting("note_add"))
            val chat = session()
            chat.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(chat.sendPcHost(target(server, PcHostKind.OPENAI_COMPATIBLE), "go"))

            assertFalse(body(server.chats.single()).containsKey("tools"))
            assertTrue(reply.notice.orEmpty().contains("does not report whether the model can call tools"), reply.notice)
        }
    }

    @Test
    fun `plain chat with no helper tools asks the server nothing extra`() {
        FakeModelServer().use { server ->
            assertIs<ChatResult.Reply>(session().sendPcHost(target(server), "hi"))
            assertEquals(listOf("/v1/chat/completions"), server.server.requests.map { it.path })
        }
    }

    @Test
    fun `an unreachable PC is reported before any tool or chat request`() {
        val server = FakeModelServer()
        val target = target(server)
        server.close()
        val tools = SecureTools(setOf("note_add"))
        val add = tools.add(SecureTools.Counting("note_add"))
        val chat = session()
        chat.setToolAccess(tools.runner, tools.specs("note_add"))

        val failure = assertIs<ChatResult.Failure>(chat.sendPcHost(target, "add milk"))

        assertTrue(failure.message.contains("could not reach"), failure.message)
        assertTrue(add.inputs.isEmpty())
        assertTrue(chat.history().isEmpty())
    }
}
