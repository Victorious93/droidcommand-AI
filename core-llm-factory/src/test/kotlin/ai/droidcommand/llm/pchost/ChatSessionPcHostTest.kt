package ai.droidcommand.llm.pchost

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.MemoryResult
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.HttpUrlConnectionTransport
import ai.droidcommand.remote.JdkHttpTransport
import ai.droidcommand.remote.StreamingHttpTransport
import ai.droidcommand.websearch.WebSearchClient
import ai.droidcommand.websearch.WebSearchOutcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ChatSession.sendPcHost] end to end against a controlled server, through the same adapter and transports the app
 * uses. Every scenario runs on [HttpUrlConnectionTransport] (what the phone uses) and on [JdkHttpTransport] (the CLI).
 */
class ChatSessionPcHostTest {
    private val transports: List<Pair<String, StreamingHttpTransport>> =
        listOf("HttpUrlConnection" to HttpUrlConnectionTransport(), "Jdk" to JdkHttpTransport())

    private val key = "sk-home-server-key-123456"

    private class Fixture(val session: ChatSession, val store: InMemoryConversationStore, val vault: InMemorySecretsVault)

    private fun fixture(
        transport: HttpTransport,
        timeouts: PcHostTimeouts = PcHostTimeouts(connectMillis = 2_000, readMillis = 5_000),
        webSearch: WebSearchClient? = null,
        graph: Boolean = false,
    ): Fixture {
        val store = InMemoryConversationStore()
        val vault = InMemorySecretsVault()
        val session = ChatSession(
            vault,
            store,
            transport = transport,
            webSearch = webSearch,
            knowledgeGraph = if (graph) InMemoryKnowledgeGraph() else null,
            pcHostTimeouts = timeouts,
        )
        return Fixture(session, store, vault)
    }

    private fun target(
        base: String,
        kind: PcHostKind = PcHostKind.OLLAMA,
        model: String = "gemma4:latest",
        location: PcModelLocation = PcModelLocation.SERVER_LOCAL,
    ) = PcHostTarget(kind, (PcHostAddress.parse(kind, base) as PcHostAddressParse.Ok).address, model, location, "ollama.com".takeIf { location == PcModelLocation.CLOUD_BACKED })

    private fun eachTransport(block: (String, StreamingHttpTransport) -> Unit) = transports.forEach { (name, transport) ->
        try {
            block(name, transport)
        } catch (e: Throwable) {
            throw AssertionError("on $name transport: ${e.message}", e)
        }
    }

    private fun assertNothingSaved(f: Fixture) {
        assertTrue(f.session.history().isEmpty(), "history must be untouched")
        assertNull(f.store.load(ChatSession.DEFAULT_CONVERSATION_ID), "nothing must be written to the store")
    }

    @Test
    fun `a reply streams from the PC, is saved, and the request is the OpenAI chat shape`() = eachTransport { _, transport ->
        FakePcServer { request, conn ->
            assertEquals("/v1/chat/completions", request.path)
            conn.startStream()
            conn.delta("Hello")
            conn.delta(" from")
            conn.delta(" the PC")
            conn.finish()
        }.use { server ->
            val f = fixture(transport)
            val deltas = mutableListOf<String>()
            val reply = assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "hi", onDelta = { deltas += it }))
            assertEquals("Hello from the PC", reply.text)
            assertEquals(listOf("Hello", " from", " the PC"), deltas)
            assertNull(reply.notice)
            assertEquals(listOf(Role.USER to "hi", Role.ASSISTANT to "Hello from the PC"), f.session.history().map { it.role to it.content })
            val sent = server.requests.single()
            assertTrue(sent.body.contains("\"model\":\"gemma4:latest\"") && sent.body.contains("\"stream\":true"), sent.body)
            // No key saved: none is sent.
            assertFalse(sent.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `the saved key is sent as a bearer token and a rejected key is explained without leaking it`() = eachTransport { _, transport ->
        FakePcServer { request, conn ->
            if (request.headers["authorization"] == "Bearer $key") {
                conn.startStream()
                conn.delta("ok")
                conn.finish()
            } else {
                conn.respond(401, """{"error":{"message":"Invalid API Key ${request.headers["authorization"]}","type":"authentication_error"}}""")
            }
        }.use { server ->
            val t = target(server.address, PcHostKind.OPENAI_COMPATIBLE, "qwen")
            val f = fixture(transport)
            val missing = assertIs<ChatResult.Failure>(f.session.sendPcHost(t, "hi"))
            assertTrue(missing.message.contains("expects an API key"), missing.message)
            assertNothingSaved(f)

            f.vault.putSecret(PcHostKind.OPENAI_COMPATIBLE.secretId, "sk-wrong-key-abcdefgh")
            val wrong = assertIs<ChatResult.Failure>(f.session.sendPcHost(t, "hi"))
            assertTrue(wrong.message.contains("saved key was not accepted"), wrong.message)
            assertFalse(wrong.message.contains("sk-wrong-key-abcdefgh"), "the key must be scrubbed: ${wrong.message}")

            f.vault.putSecret(PcHostKind.OPENAI_COMPATIBLE.secretId, key)
            assertIs<ChatResult.Reply>(f.session.sendPcHost(t, "hi"))
        }
    }

    @Test
    fun `a model the PC does not have is named, with what to do`() = eachTransport { _, transport ->
        // Ollama's OpenAI endpoint answers 404 for an unknown model.
        FakePcServer { _, conn ->
            conn.respond(404, """{"error":{"message":"model \"llama9\" not found, try pulling it first","type":"api_error"}}""")
        }.use { server ->
            val f = fixture(transport)
            val failure = assertIs<ChatResult.Failure>(f.session.sendPcHost(target(server.address, model = "llama9"), "hi"))
            assertTrue(failure.message.contains("does not have the model \"llama9\""), failure.message)
            assertTrue(failure.message.contains("ollama pull llama9"), failure.message)
            assertTrue(failure.message.contains("try pulling it first"), failure.message)
            assertNothingSaved(f)
        }
    }

    @Test
    fun `a PC that is off or not listening fails fast and says it is not about internet`() = eachTransport { _, transport ->
        val f = fixture(transport)
        val started = System.nanoTime()
        val failure = assertIs<ChatResult.Failure>(f.session.sendPcHost(target("127.0.0.1:${FakePcServer.closedPort()}"), "hi"))
        assertTrue(failure.message.startsWith(PcHostErrors.PREFIX + "could not reach"), failure.message)
        assertTrue(failure.message.contains("not about internet access"), failure.message)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000, "one attempt, no retry backoff")
        assertNothingSaved(f)
    }

    @Test
    fun `a PC that accepts the request and then says nothing is a timeout, not an unreachable`() = eachTransport { _, transport ->
        FakePcServer { _, conn -> conn.awaitClientGone(10_000) }.use { server ->
            val f = fixture(transport, PcHostTimeouts(connectMillis = 2_000, readMillis = 700))
            val failure = assertIs<ChatResult.Failure>(f.session.sendPcHost(target(server.address), "hi"))
            assertTrue(failure.message.contains("accepted the request but sent nothing"), failure.message)
            assertNothingSaved(f)
        }
    }

    @Test
    fun `losing the PC in the middle of a reply discards the partial reply and keeps the conversation`() = eachTransport { _, transport ->
        val healthy = AtomicBoolean(true)
        FakePcServer { _, conn ->
            conn.startStream()
            conn.delta("First")
            if (healthy.get()) {
                conn.delta(" answer")
                conn.finish()
            } else {
                conn.delta(" half of the sec")
                Thread.sleep(150)
                conn.drop()
            }
        }.use { server ->
            val f = fixture(transport)
            assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "one"))
            healthy.set(false)
            val seen = StringBuilder()
            val failure = assertIs<ChatResult.Failure>(f.session.sendPcHost(target(server.address), "two") { seen.append(it) })
            assertTrue(seen.startsWith("First"), "partial text was streamed before the drop")
            assertTrue(failure.message.contains("was lost while the reply was arriving"), failure.message)
            // The earlier turn is intact, the failed one left no trace, and the same text can be sent again.
            assertEquals(listOf("one", "First answer"), f.session.history().map { it.content })
            assertEquals(2, f.store.load(ChatSession.DEFAULT_CONVERSATION_ID)!!.messages.size)
            healthy.set(true)
            assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "two"))
            assertEquals(listOf("one", "First answer", "two", "First answer"), f.session.history().map { it.content })
        }
    }

    @Test
    fun `a stream that ends cleanly but without its end marker is not saved as a complete reply`() = eachTransport { _, transport ->
        FakePcServer { _, conn ->
            conn.startStream()
            conn.delta("Half an ans")
            conn.endStream() // the server process was stopped: the HTTP body closes properly, the reply never finished
        }.use { server ->
            val f = fixture(transport)
            val failure = assertIs<ChatResult.Failure>(f.session.sendPcHost(target(server.address), "hi"))
            assertTrue(failure.message.contains("lost while the reply was arriving"), failure.message)
            assertNothingSaved(f)
        }
    }

    @Test
    fun `stop before the first word returns at once, saves nothing, and the server sees the hang-up`() = eachTransport { _, transport ->
        val waiting = CountDownLatch(1)
        lateinit var server: FakePcServer
        server = FakePcServer { _, conn ->
            waiting.countDown()
            if (conn.awaitClientGone(10_000)) server.clientGone.countDown()
        }
        server.use {
            val f = fixture(transport, PcHostTimeouts(connectMillis = 2_000, readMillis = 30_000))
            var result: ChatResult? = null
            val worker = thread { result = f.session.sendPcHost(target(server.address), "hi") }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            val stopAt = System.nanoTime()
            f.session.requestStopPcHost()
            worker.join(5_000)
            assertFalse(worker.isAlive, "the turn must end when stopped, not at the read timeout")
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopAt) < 2_000)
            assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), result)
            assertTrue(server.awaitClientGone(), "the connection is closed, which is what makes the PC stop generating")
            assertEquals(1, server.requests.size, "a stopped request is not retried")
            assertNothingSaved(f)
        }
    }

    @Test
    fun `stop in the middle of a reply discards it and the next message works`() = eachTransport { _, transport ->
        val endless = AtomicBoolean(true)
        lateinit var server: FakePcServer
        server = FakePcServer { _, conn ->
            conn.startStream()
            if (!endless.get()) {
                conn.delta("done")
                conn.finish()
                return@FakePcServer
            }
            try {
                repeat(2_000) {
                    conn.delta("word ")
                    Thread.sleep(10)
                }
            } catch (_: java.io.IOException) {
                server.clientGone.countDown()
            }
        }
        server.use {
            val f = fixture(transport)
            val stop = AtomicBoolean(false)
            val got = CountDownLatch(3)
            var result: ChatResult? = null
            var afterStop = 0
            val worker = thread {
                result = f.session.sendPcHost(target(server.address), "hi", cancelled = stop::get) {
                    if (stop.get()) afterStop++
                    got.countDown()
                }
            }
            assertTrue(got.await(5, TimeUnit.SECONDS))
            stop.set(true)
            f.session.requestStopPcHost()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), result)
            assertEquals(0, afterStop, "no text is delivered after the stop")
            assertTrue(server.awaitClientGone())
            assertNothingSaved(f)

            endless.set(false)
            assertEquals("done", assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "again")).text)
        }
    }

    @Test
    fun `a cloud-backed model is labelled on every reply, and its upstream failure is told apart from the PC`() = eachTransport { _, transport ->
        val upstreamUp = AtomicBoolean(true)
        FakePcServer { _, conn ->
            if (upstreamUp.get()) {
                conn.startStream()
                conn.delta("from the cloud")
                conn.finish()
            } else {
                conn.respond(502, """{"error":{"message":"Post \"https://ollama.com/api/chat\": dial tcp: lookup ollama.com: no such host"}}""")
            }
        }.use { server ->
            val f = fixture(transport)
            val cloud = target(server.address, model = "gpt-oss:120b-cloud", location = PcModelLocation.CLOUD_BACKED)
            val reply = assertIs<ChatResult.Reply>(f.session.sendPcHost(cloud, "hi"))
            assertTrue(reply.notice!!.contains("Cloud-backed: your PC forwards the chat to ollama.com"), reply.notice)

            upstreamUp.set(false)
            val failure = assertIs<ChatResult.Failure>(f.session.sendPcHost(cloud, "again"))
            assertTrue(failure.message.contains("could not reach the internet service behind the cloud-backed model"), failure.message)
            assertTrue(failure.message.contains("A model installed on the PC keeps working"), failure.message)
            // A server error is not retried: a second request would make the PC generate the reply twice.
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `with no internet at all a server-local model still answers`() = eachTransport { _, real ->
        // Every host except the PC is unreachable, as in an internet outage with the LAN still up.
        val contacted = mutableListOf<String>()
        val lanOnly = object : StreamingHttpTransport {
            private fun check(request: HttpRequestSpec) {
                contacted += request.url
                if (!request.url.startsWith("http://127.0.0.1")) throw java.net.UnknownHostException("no internet")
            }

            override fun send(request: HttpRequestSpec): HttpResponseSpec {
                check(request)
                return real.send(request)
            }

            override fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec {
                check(request)
                return real.sendStreaming(request, onLine)
            }
        }
        val offlineSearch = object : WebSearchClient {
            override fun search(query: String, count: Int): WebSearchOutcome = WebSearchOutcome.Failure("no internet")
        }
        FakePcServer { _, conn ->
            conn.startStream()
            conn.delta("still here")
            conn.finish()
        }.use { server ->
            val f = fixture(lanOnly, webSearch = offlineSearch)
            val plain = assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "hi"))
            assertEquals("still here", plain.text)
            assertNull(plain.notice)
            assertEquals(listOf("${server.address}/v1/chat/completions"), contacted, "a PC turn contacts the PC and nothing else")

            // With the web-search toggle on, the search fails (no internet) but the PC still answers, with a notice.
            val searched = assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "hi again", useWebSearch = true))
            assertEquals("still here", searched.text)
            assertTrue(searched.notice!!.contains("Web search failed"), searched.notice)
        }
    }

    @Test
    fun `no model, or plain http to a public name, is refused before anything is sent`() {
        val sent = AtomicBoolean(false)
        val transport = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec {
                sent.set(true)
                return HttpResponseSpec(200, emptyMap(), "{}")
            }
        }
        val f = fixture(transport)
        val noModel = assertIs<ChatResult.Failure>(f.session.sendPcHost(target("192.168.1.20", model = " "), "hi"))
        assertTrue(noModel.message.contains("no model is chosen"), noModel.message)
        assertFalse(sent.get())
    }

    @Test
    fun `remembering after a PC turn says why it cannot, instead of picking a cloud provider`() {
        FakePcServer { _, conn ->
            conn.startStream()
            conn.delta("hello")
            conn.finish()
        }.use { server ->
            val f = fixture(HttpUrlConnectionTransport(), graph = true)
            assertIs<ChatResult.Reply>(f.session.sendPcHost(target(server.address), "hi"))
            val result = assertIs<MemoryResult.Failure>(f.session.rememberConversation())
            assertTrue(result.message.contains("PC-hosted model"), result.message)
            assertEquals(1, server.requests.size)
        }
    }
}
