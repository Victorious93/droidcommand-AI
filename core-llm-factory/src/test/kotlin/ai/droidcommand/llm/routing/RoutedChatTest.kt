package ai.droidcommand.llm.routing

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.JsonFileConversationStore
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.LocalProviderResources
import ai.droidcommand.llm.factory.MemoryResult
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.pchost.FakePcServer
import ai.droidcommand.llm.pchost.FilePcHostSettingsStore
import ai.droidcommand.llm.pchost.InMemoryPcHostSettingsStore
import ai.droidcommand.llm.pchost.PcHostClient
import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostSettingsStore
import ai.droidcommand.llm.pchost.PcHostTimeouts
import ai.droidcommand.llm.pchost.PcModelLocation
import ai.droidcommand.llm.routing.FakeModelServer.Companion.dropAfter
import ai.droidcommand.llm.routing.FakeModelServer.Companion.text
import ai.droidcommand.llm.routing.FakeModelServer.Companion.toolCalls
import ai.droidcommand.remote.HttpUrlConnectionTransport
import ai.droidcommand.websearch.WebSearchClient
import ai.droidcommand.websearch.WebSearchOutcome
import ai.droidcommand.websearch.WebSearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's inference policy on a chat turn ([ChatSession.sendRouted]) and on everything else a chat session can
 * send. FAKE backends throughout: a fake on-device backend, a fake model server on a real loopback socket, and a
 * scripted cloud provider. Every request goes through a [RecordingTransport], so "this place received nothing" is an
 * assertion on what was actually sent.
 */
class RoutedChatTest {
    private val dir: File = Files.createTempDirectory("routed-chat").toFile().also { it.deleteOnExit() }
    private val groq = CloudProviderCatalog.byId("groq")!!
    private val cloudKey = "gsk-cloud-key-1234567890"

    private class Cloud : LlmProvider {
        val requests = mutableListOf<LlmRequest>()
        var built = 0
        var reply: (LlmRequest) -> LlmResponse = { LlmResponse.Text("cloud reply") }
        override val config = LlmConfig(provider = "groq", model = "m")

        override fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return reply(request)
        }
    }

    private class Search : WebSearchClient {
        val queries = mutableListOf<String>()

        override fun search(query: String, count: Int): WebSearchOutcome {
            queries += query
            return WebSearchOutcome.Success(listOf(WebSearchResult("t", "https://example.com", "s")))
        }
    }

    private inner class World(
        policy: InferencePolicy,
        cloudConfirmed: Boolean = false,
        val pc: FakeModelServer? = null,
        pcKind: PcHostKind = PcHostKind.OLLAMA,
        pcLocation: PcModelLocation = PcModelLocation.SERVER_LOCAL,
        deviceContext: Int = 2048,
        withDevice: Boolean = true,
        withCloudKey: Boolean = true,
        val store: ai.droidcommand.agent.ConversationStore = InMemoryConversationStore(),
        val settings: InferenceSettingsStore = InMemoryInferenceSettingsStore(InferenceSettings(policy, cloudConfirmed)),
        val pcStore: PcHostSettingsStore = InMemoryPcHostSettingsStore(),
        forbid: (String) -> Boolean = { false },
        graph: Boolean = false,
    ) {
        val backend = FakeBackend()
        val cloud = Cloud()
        val search = Search()
        val vault = InMemorySecretsVault().also { if (withCloudKey) it.putSecret(groq.secretId, cloudKey) }
        val transport = RecordingTransport(HttpUrlConnectionTransport(), forbid)
        val repository = ModelRepository(dir).also { repo ->
            val bytes = "weights".toByteArray()
            File(dir, "m.gguf").writeBytes(bytes)
            if (withDevice) repo.register(ModelMetadata("tiny", "Tiny", "m.gguf", ModelRepository.sha256Hex(bytes.inputStream()), deviceContext))
        }
        val resources = LocalProviderResources(repository) { backend }

        init {
            if (pc != null && pcStore.load(pcKind) == null) pcStore.save(pcKind, PcHostEndpoint(pc.address, pc.model, pcLocation))
        }

        val resolver = RouteResolver(
            vault = vault,
            settings = settings,
            pcHosts = pcStore,
            deviceModels = { repository },
            cloudChoice = { CloudChoice(groq, "llama-x") },
            inspectPc = { target, key -> PcHostClient(transport, 1_500, 3_000).inspect(target, key) },
            fallbackDeviceModelId = { "tiny".takeIf { withDevice } },
        )
        val session = ChatSession(
            vault,
            store,
            transport = transport,
            providerFor = { _, _ ->
                cloud.built++
                cloud
            },
            webSearch = search,
            local = resources,
            knowledgeGraph = if (graph) InMemoryKnowledgeGraph() else null,
            pcHostTimeouts = PcHostTimeouts(connectMillis = 1_500, readMillis = 4_000),
            router = resolver,
            inspectPc = { target, key -> PcHostClient(transport, 1_500, 3_000).inspect(target, key) },
        )

        fun assertCloudUntouched() {
            assertEquals(0, cloud.built, "no cloud provider may even be built")
            assertTrue(cloud.requests.isEmpty(), "the cloud provider must receive nothing")
            assertTrue(search.queries.isEmpty(), "the search service must receive nothing")
        }
    }

    // ---- 1. On-device inference with networking disabled

    @Test
    fun `this device only - the device answers and nothing at all is sent over the network`() {
        FakeModelServer().use { pc ->
            // Any request, to anywhere, fails the test: this stands in for a phone with networking off.
            val world = World(InferencePolicy.DEVICE_ONLY, pc = pc, forbid = { true })

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello", useWebSearch = true))

            assertEquals("device reply", reply.text)
            assertTrue(world.transport.urls.isEmpty(), "no request of any kind: ${world.transport.urls}")
            assertTrue(pc.server.requests.isEmpty(), "the PC is not even asked whether it is there")
            world.assertCloudUntouched()
            val route = assertNotNull(reply.route)
            assertEquals(InferencePolicy.DEVICE_ONLY, route.policy)
            assertEquals(RouteEndpoint(RouteEndpoint.Kind.DEVICE, "This device", "Tiny"), route.chosen)
            assertNull(route.fallbackReason)
            assertEquals(ChatSession.WEB_SEARCH_POLICY_NOTICE, reply.notice)
            assertEquals(listOf("hello", "device reply"), world.session.history().map { it.content })
        }
    }

    @Test
    fun `this device only - with no model on the device nothing is sent anywhere and the refusal says what to do`() {
        FakeModelServer().use { pc ->
            val world = World(InferencePolicy.DEVICE_ONLY, pc = pc, withDevice = false, forbid = { true })

            val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("hello"))

            assertTrue(failure.message.contains("This device only"), failure.message)
            assertTrue(failure.message.contains("pick one in Models"), failure.message)
            assertTrue(world.transport.urls.isEmpty())
            world.assertCloudUntouched()
            assertTrue(world.session.history().isEmpty())
        }
    }

    // ---- 2. PC-local inference over LAN with internet unavailable

    @Test
    fun `my PC only - the PC answers and only the PC is contacted`() {
        FakeModelServer().use { pc ->
            pc.chat += text("PC ", "reply")
            // Internet unavailable: any destination other than the PC's own address fails the test.
            val world = World(InferencePolicy.PC_ONLY, pc = pc, forbid = { !it.startsWith(pc.address) })

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))

            assertEquals("PC reply", reply.text)
            assertEquals(setOf("127.0.0.1:${pc.port}"), world.transport.hosts())
            assertTrue(world.backend.seen.isEmpty(), "the device model is not part of this policy")
            world.assertCloudUntouched()
            val route = assertNotNull(reply.route)
            assertEquals("My PC (Ollama at ${pc.address})", route.chosen?.where)
            assertEquals(pc.model, route.chosen?.model)
            assertEquals(4096, route.windowTokens)
            assertTrue(route.summary().contains("Answered by My PC (Ollama at ${pc.address}), model ${pc.model}."), route.summary())
        }
    }

    // ---- 3. PC unreachable before sending

    @Test
    fun `prefer PC - an unreachable PC is skipped before anything is sent and this device answers, with the reason`() {
        val pc = FakeModelServer()
        val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)
        pc.close() // the PC is off

        val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))

        assertEquals("device reply", reply.text)
        world.assertCloudUntouched()
        val route = assertNotNull(reply.route)
        assertEquals(RouteEndpoint.Kind.DEVICE, route.chosen?.kind)
        val reason = assertNotNull(route.fallbackReason)
        assertTrue(reason.contains("My PC (Ollama at ${pc.address}) was not used"), reason)
        assertTrue(reason.contains("could not reach"), reason)
        assertTrue(pc.chats.isEmpty(), "no chat request was sent to the PC")
    }

    @Test
    fun `my PC only - an unreachable PC means nothing is sent anywhere, not even to this device`() {
        val pc = FakeModelServer()
        val world = World(InferencePolicy.PC_ONLY, pc = pc)
        pc.close()

        val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("hello"))

        assertTrue(failure.message.contains("My PC only"), failure.message)
        assertTrue(failure.message.contains("could not reach"), failure.message)
        assertTrue(world.backend.seen.isEmpty(), "the device must not answer under My PC only")
        world.assertCloudUntouched()
        assertTrue(world.session.history().isEmpty())
        assertNull(assertNotNull(failure.route).chosen)
    }

    // ---- 4. Connection lost during streaming

    @Test
    fun `prefer PC - a connection lost mid-reply discards the partial text and this device answers`() {
        FakeModelServer().use { pc ->
            pc.chat += dropAfter("Half an ans")
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)
            val shown = StringBuilder()
            var restarts = 0

            val reply = assertIs<ChatResult.Reply>(
                world.session.sendRouted(
                    "hello",
                    onRestart = {
                        restarts++
                        shown.clear()
                    },
                ) { shown.append(it) },
            )

            assertEquals("device reply", reply.text)
            assertEquals(1, restarts, "the caller is told to clear the partial text exactly once")
            assertEquals("device reply", shown.toString(), "what is on screen is only the reply that was saved")
            assertEquals(listOf("hello", "device reply"), world.session.history().map { it.content })
            val reason = assertNotNull(reply.route?.fallbackReason)
            assertTrue(reason.contains("was lost while the reply was arriving"), reason)
            world.assertCloudUntouched()
        }
    }

    @Test
    fun `my PC only - a connection lost mid-reply saves nothing, and the same message then succeeds`() {
        FakeModelServer().use { pc ->
            pc.chat += dropAfter("Half")
            val world = World(InferencePolicy.PC_ONLY, pc = pc)

            val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("hello"))
            assertTrue(failure.message.contains("was lost while the reply was arriving"), failure.message)
            assertTrue(world.session.history().isEmpty())
            assertNull(world.store.load(ChatSession.DEFAULT_CONVERSATION_ID))
            assertTrue(world.backend.seen.isEmpty())

            pc.chat += text("whole answer")
            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))
            assertEquals("whole answer", reply.text)
            assertEquals(listOf("hello", "whole answer"), world.session.history().map { it.content })
        }
    }

    // ---- Cancellation followed by a successful new request

    @Test
    fun `a stopped routed turn saves nothing and does not fall back, and the next request succeeds`() {
        FakeModelServer().use { pc ->
            val stop = AtomicBoolean(false)
            pc.chat += { _, connection ->
                connection.startStream()
                connection.delta("so")
                stop.set(true)
                connection.delta("me more")
                connection.awaitClientGone(3_000)
            }
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)

            val stopped = world.session.sendRouted("hello", cancelled = { stop.get().also { if (it) world.session.requestStopPcHost() } })

            assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), stopped)
            assertTrue(world.backend.seen.isEmpty(), "a stop is not a failure to fall back from")
            assertTrue(world.session.history().isEmpty())

            stop.set(false)
            pc.chat += text("second try")
            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("again", cancelled = stop::get))
            assertEquals("second try", reply.text)
            assertEquals(listOf("again", "second try"), world.session.history().map { it.content })
        }
    }

    // ---- Cloud policy

    @Test
    fun `cloud fallback that was never confirmed behaves as prefer PC then device - the cloud gets nothing`() {
        val pc = FakeModelServer()
        val world = World(InferencePolicy.CLOUD_FALLBACK, cloudConfirmed = false, pc = pc, withDevice = false)
        pc.close()

        val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("hello", useWebSearch = true))

        assertTrue(failure.message.contains(InferencePolicy.PC_THEN_DEVICE.label), failure.message)
        world.assertCloudUntouched()
    }

    @Test
    fun `confirmed cloud fallback - the cloud answers only after PC and device could not, and the reply says so`() {
        val pc = FakeModelServer()
        val world = World(InferencePolicy.CLOUD_FALLBACK, cloudConfirmed = true, pc = pc, withDevice = false)
        pc.close()

        val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))

        assertEquals("cloud reply", reply.text)
        assertEquals(1, world.cloud.requests.size)
        val route = assertNotNull(reply.route)
        assertEquals(RouteEndpoint(RouteEndpoint.Kind.CLOUD, "Cloud (${groq.label})", "llama-x"), route.chosen)
        val reason = assertNotNull(route.fallbackReason)
        assertTrue(reason.contains("My PC") && reason.contains("This device"), reason)
        assertTrue(reply.notice.orEmpty().contains("cloud fallback is on"), reply.notice)
    }

    @Test
    fun `confirmed cloud fallback - while the PC answers, the cloud receives nothing`() {
        FakeModelServer().use { pc ->
            val world = World(InferencePolicy.CLOUD_FALLBACK, cloudConfirmed = true, pc = pc)
            assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))
            assertEquals(1, pc.chats.size)
            assertEquals(0, world.cloud.built)
            assertTrue(world.cloud.requests.isEmpty())
        }
    }

    @Test
    fun `a strict policy refuses a direct cloud send before a provider is built`() {
        for (policy in listOf(InferencePolicy.DEVICE_ONLY, InferencePolicy.PC_ONLY, InferencePolicy.PC_THEN_DEVICE)) {
            val world = World(policy)
            val failure = assertIs<ChatResult.Failure>(world.session.send(groq, "m", "hello", useWebSearch = true))
            assertTrue(failure.message.contains(policy.label), failure.message)
            assertTrue(failure.message.contains("Nothing was sent"), failure.message)
            world.assertCloudUntouched()
            assertTrue(world.transport.urls.isEmpty())
        }
    }

    @Test
    fun `the manual policy leaves a direct cloud send as it was`() {
        val world = World(InferencePolicy.MANUAL)
        val reply = assertIs<ChatResult.Reply>(world.session.send(groq, "m", "hello"))
        assertEquals("cloud reply", reply.text)
        assertNull(reply.route)
    }

    // ---- Location: cloud-backed and unestablished PC models

    @Test
    fun `a cloud-backed PC model is refused under every strict PC policy without a chat request`() {
        for (policy in listOf(InferencePolicy.PC_ONLY, InferencePolicy.PC_THEN_DEVICE)) {
            FakeModelServer(remoteHost = "https://ollama.com:443").use { pc ->
                val world = World(policy, pc = pc, withDevice = false)

                val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("hello"))

                assertTrue(failure.message.contains("cloud-backed"), failure.message)
                assertTrue(failure.message.contains("ollama.com"), failure.message)
                assertTrue(pc.chats.isEmpty(), "the conversation must not be sent to a PC that would forward it")
                world.assertCloudUntouched()
            }
        }
    }

    @Test
    fun `a model saved as cloud-backed stays refused even if the server now reports nothing about it`() {
        FakeModelServer().use { pc ->
            val world = World(InferencePolicy.PC_ONLY, pc = pc, pcLocation = PcModelLocation.CLOUD_BACKED)
            val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("hello"))
            assertTrue(failure.message.contains("cloud-backed"), failure.message)
            assertTrue(pc.chats.isEmpty())
        }
    }

    @Test
    fun `a server that does not establish where the model runs is refused under strict policies`() {
        FakeModelServer(reportsAnything = false).use { pc ->
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc, pcKind = PcHostKind.OPENAI_COMPATIBLE, pcLocation = PcModelLocation.UNKNOWN)

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))

            assertEquals("device reply", reply.text)
            assertTrue(pc.chats.isEmpty())
            assertTrue(assertNotNull(reply.route?.fallbackReason).contains("does not establish where the model runs"), reply.route?.fallbackReason)
        }
    }

    @Test
    fun `a llama-cpp server establishes a local model and is used under my PC only`() {
        FakeModelServer(llamaCpp = true, loadedContext = 8192).use { pc ->
            pc.chat += text("llama reply")
            val world = World(InferencePolicy.PC_ONLY, pc = pc, pcKind = PcHostKind.OPENAI_COMPATIBLE, pcLocation = PcModelLocation.UNKNOWN)

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))

            assertEquals("llama reply", reply.text)
            assertEquals(8192, reply.route?.windowTokens)
        }
    }

    @Test
    fun `with confirmed cloud fallback an unestablished server is a cloud recipient, tried after this device`() {
        FakeModelServer(reportsAnything = false).use { pc ->
            val world = World(
                InferencePolicy.CLOUD_FALLBACK,
                cloudConfirmed = true,
                pc = pc,
                pcKind = PcHostKind.OPENAI_COMPATIBLE,
                pcLocation = PcModelLocation.UNKNOWN,
            )
            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("hello"))
            assertEquals("device reply", reply.text, "this device comes before any cloud recipient")
            assertTrue(pc.chats.isEmpty())
        }
    }

    // ---- Unsupported capabilities, tools, and no double execution across a fallback

    @Test
    fun `tools are offered to a capable PC model and not to this device, which says so`() {
        FakeModelServer().use { pc ->
            val tools = SecureTools(setOf("note_add"))
            val add = tools.add(SecureTools.Counting("note_add"))
            pc.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{\"title\":\"milk\"}")))
            pc.chat += text("Added.")
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)
            world.session.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("add milk"))
            assertEquals("Added.", reply.text)
            assertEquals(1, add.inputs.size)
            assertNull(reply.notice)

            // The same bot under "this device only": no tool is offered, and the reply says so.
            world.settings.save(InferenceSettings(InferencePolicy.DEVICE_ONLY))
            val local = assertIs<ChatResult.Reply>(world.session.sendRouted("add eggs"))
            assertEquals(ChatSession.LOCAL_TOOLS_NOTICE, local.notice)
            assertEquals(1, add.inputs.size)
        }
    }

    @Test
    fun `a PC model without tool support still answers, without tools, and the reply names the limitation`() {
        FakeModelServer(capabilities = listOf("completion")).use { pc ->
            val tools = SecureTools(setOf("note_add"))
            tools.add(SecureTools.Counting("note_add"))
            val world = World(InferencePolicy.PC_ONLY, pc = pc)
            world.session.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("add milk"))

            assertFalse(Json.parseToJsonElement(pc.chats.single().body).jsonObject.containsKey("tools"))
            assertTrue(reply.notice.orEmpty().startsWith(ChatSession.PC_HOST_TOOLS_NOTICE), reply.notice)
        }
    }

    @Test
    fun `a tool that ran on the PC is not run again when the turn falls back to this device`() {
        FakeModelServer().use { pc ->
            val tools = SecureTools(setOf("note_add"))
            val add = tools.add(SecureTools.Counting("note_add"))
            pc.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{\"title\":\"milk\"}")))
            pc.chat += dropAfter() // the PC vanishes after the tool ran, before it wrote the answer
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)
            world.session.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("add milk"))

            assertEquals("device reply", reply.text)
            assertEquals(1, add.inputs.size, "the action ran exactly once")
            val seen = world.backend.seen.single().messages
            assertTrue(seen.any { it.role == Role.TOOL && it.content == "note_add done #1" }, "the device is given the completed tool result: $seen")
        }
    }

    @Test
    fun `a tool that ran on the PC is not run again by the cloud fallback, even if the cloud model asks for it again`() {
        FakeModelServer().use { pc ->
            val tools = SecureTools(setOf("note_add"))
            val add = tools.add(SecureTools.Counting("note_add"))
            pc.chat += toolCalls(StreamedCall("c1", "note_add", listOf("{\"title\":\"milk\"}")))
            pc.chat += dropAfter()
            val world = World(InferencePolicy.CLOUD_FALLBACK, cloudConfirmed = true, pc = pc, withDevice = false)
            var asked = false
            world.cloud.reply = { request ->
                if (!asked) {
                    asked = true
                    LlmResponse.ToolCall("note_add", mapOf("title" to "milk"))
                } else {
                    assertTrue(request.messages.count { it.role == Role.TOOL } == 2, request.messages.toString())
                    LlmResponse.Text("cloud: added")
                }
            }
            world.session.setToolAccess(tools.runner, tools.specs("note_add"))

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("add milk"))

            assertEquals("cloud: added", reply.text)
            assertEquals(1, add.inputs.size, "the replayed call returned the stored result instead of running")
            assertEquals(1, tools.approvalsAsked.size)
        }
    }

    // ---- Oversized context and the request budget

    @Test
    fun `history that does not fit the model's window is shortened oldest-first and the reply says how much`() {
        FakeModelServer(loadedContext = 1024).use { pc ->
            val store = InMemoryConversationStore()
            val old = ConversationContext()
            repeat(12) { i -> old.append(Role.USER, "question $i " + "x".repeat(240)).append(Role.ASSISTANT, "answer $i " + "y".repeat(240)) }
            store.save(ChatSession.DEFAULT_CONVERSATION_ID, old)
            val world = World(InferencePolicy.PC_ONLY, pc = pc, store = store)

            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("and now?"))

            val route = assertNotNull(reply.route)
            assertTrue(route.droppedMessages > 0, "some history had to be left out")
            assertEquals(1024, route.windowTokens)
            val sent = Json.parseToJsonElement(pc.chats.single().body).jsonObject["messages"]!!.jsonArray
            assertEquals(24 - route.droppedMessages + 1, sent.size)
            assertEquals("user", sent.first().jsonObject["role"]!!.jsonPrimitive.content, "the request starts on a question, not an orphaned answer")
            assertEquals("and now?", sent.last().jsonObject["content"]!!.jsonPrimitive.content)
            val budget = assertNotNull(route.budget)
            assertTrue(budget.historyTokens > 1024, "the estimate covers the whole stored history")
            assertEquals(ChatSession.OUTPUT_RESERVE_TOKENS, budget.outputReserveTokens)
            assertTrue(route.summary().contains("left out of this request"), route.summary())
            // The stored conversation keeps everything; only the request was shortened.
            assertEquals(26, world.session.history().size)
        }
    }

    @Test
    fun `a message too large for the PC's window goes to the next place that can hold it, or nowhere`() {
        val huge = "word ".repeat(1_500) // about 2,500 estimated tokens
        FakeModelServer(loadedContext = 1024).use { pc ->
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc, deviceContext = 8192)
            val reply = assertIs<ChatResult.Reply>(world.session.sendRouted(huge))
            assertEquals("device reply", reply.text)
            assertTrue(pc.chats.isEmpty(), "a request that cannot fit is not sent")
            assertTrue(assertNotNull(reply.route?.fallbackReason).contains("context window (1024 tokens) is smaller than the request needs"), reply.route?.fallbackReason)
        }
        FakeModelServer(loadedContext = 1024).use { pc ->
            val world = World(InferencePolicy.PC_ONLY, pc = pc)
            val failure = assertIs<ChatResult.Failure>(world.session.sendRouted(huge))
            assertTrue(failure.message.contains("context window (1024 tokens) is smaller"), failure.message)
            assertTrue(pc.chats.isEmpty())
            assertTrue(world.session.history().isEmpty())
        }
    }

    @Test
    fun `tool schemas and tool results count against the window`() {
        val budget = RequestBudget.of(
            systemPrompt = "be brief",
            history = listOf(ai.droidcommand.agent.Message(Role.USER, "a".repeat(3000)), ai.droidcommand.agent.Message(Role.ASSISTANT, "b".repeat(3000))),
            userText = "c".repeat(30),
            outgoing = "excerpt ".repeat(40) + "c".repeat(30),
            tools = SecureTools(setOf("note_add")).let { it.add(SecureTools.Counting("note_add")); it.specs("note_add") },
            exchange = listOf(ai.droidcommand.agent.Message(Role.TOOL, "r".repeat(90), toolCallId = "c1")),
            outputReserveTokens = 512,
        )
        assertTrue(budget.systemTokens > 0 && budget.historyTokens >= 2000 && budget.messageTokens >= 10)
        assertTrue(budget.retrievedTokens >= 100, "retrieved content is counted apart from what was typed")
        assertTrue(budget.toolSchemaTokens > 0 && budget.toolResultTokens >= 30)
        assertEquals(budget.fixedTokens + budget.historyTokens + 512, budget.totalTokens)
        assertTrue(budget.fitsWhole(8192))
        assertFalse(budget.fitsWhole(budget.totalTokens - 1))
        assertTrue(budget.fitsWithoutHistory(budget.minimumWindowTokens()))
        assertFalse(budget.fitsWithoutHistory(budget.minimumWindowTokens() / 2))
    }

    // ---- Remember this chat / auto-remember under a policy

    @Test
    fun `remembering under this device only is done by the device model and never by the cloud`() {
        val world = World(InferencePolicy.DEVICE_ONLY, graph = true, forbid = { true })
        assertIs<ChatResult.Reply>(world.session.sendRouted("my dog is called Rex"))

        val result = world.session.rememberConversation()

        // The fake device model does not write the JSON extraction needs; what matters is who was asked.
        assertIs<MemoryResult.Failure>(result)
        assertEquals(2, world.backend.seen.size, "the extraction request went to the device model")
        world.assertCloudUntouched()
        assertTrue(world.transport.urls.isEmpty())
    }

    @Test
    fun `remembering under my PC only with the PC off sends nothing anywhere and says why`() {
        val pc = FakeModelServer()
        pc.chat += text("noted")
        val world = World(InferencePolicy.PC_ONLY, pc = pc, graph = true)
        assertIs<ChatResult.Reply>(world.session.sendRouted("my dog is called Rex"))
        pc.close()

        val result = assertIs<MemoryResult.Failure>(world.session.rememberConversation())

        assertTrue(result.message.contains("Remembering this chat was not sent anywhere"), result.message)
        assertTrue(world.backend.seen.isEmpty())
        world.assertCloudUntouched()
    }

    // ---- Restart with persisted settings

    @Test
    fun `after a restart the saved policy, PC server, model and conversation are used again`() {
        val files = Files.createTempDirectory("routed-restart")
        FakeModelServer().use { pc ->
            pc.chat += text("first")
            pc.chat += text("second")
            fun start(): World = World(
                InferencePolicy.MANUAL, // ignored: the file store below is what is read
                pc = pc,
                store = JsonFileConversationStore(files.resolve("chats")),
                settings = FileInferenceSettingsStore(files.resolve("inference.properties")),
                pcStore = FilePcHostSettingsStore(files.resolve("pc.properties")),
            )
            val before = start()
            before.settings.save(InferenceSettings(InferencePolicy.PC_ONLY, deviceModelId = "tiny", pcKind = PcHostKind.OLLAMA))
            assertEquals("first", assertIs<ChatResult.Reply>(before.session.sendRouted("one")).text)

            val after = start() // new stores, new resolver, new session: only the files carry over
            assertEquals(InferenceSettings(InferencePolicy.PC_ONLY, deviceModelId = "tiny", pcKind = PcHostKind.OLLAMA), after.settings.load())
            val reply = assertIs<ChatResult.Reply>(after.session.sendRouted("two"))
            assertEquals("second", reply.text)
            assertEquals(InferencePolicy.PC_ONLY, reply.route?.policy)
            assertEquals(listOf("one", "first", "two", "second"), after.session.history().map { it.content })
            assertTrue(after.backend.seen.isEmpty())
            after.assertCloudUntouched()
        }
    }

    @Test
    fun `settings migration - no file is the old behaviour, an unreadable or unknown one is the strictest policy`() {
        val files = Files.createTempDirectory("routed-migration")
        val file = files.resolve("inference.properties")
        val store = FileInferenceSettingsStore(file)
        assertEquals(InferencePolicy.MANUAL, store.load().policy, "an install from before policies keeps behaving as it did")

        Files.writeString(file, "policy=some-future-policy\ncloudFallbackConfirmed=true\n")
        assertEquals(InferenceSettings(InferencePolicy.DEVICE_ONLY), store.load(), "an unknown policy must not be read as one that allows more")

        Files.writeString(file, "policy=cloud-fallback\n")
        assertEquals(InferencePolicy.PC_THEN_DEVICE, store.load().effective, "cloud fallback without the confirmation flag is not cloud fallback")

        store.save(InferenceSettings(InferencePolicy.CLOUD_FALLBACK, cloudFallbackConfirmed = true, deviceModelId = "m", pcKind = PcHostKind.OPENAI_COMPATIBLE))
        assertEquals(InferenceSettings(InferencePolicy.CLOUD_FALLBACK, true, "m", PcHostKind.OPENAI_COMPATIBLE), FileInferenceSettingsStore(file).load())
        assertTrue(FileInferenceSettingsStore(file).load().effective.permits(ProviderType.CLOUD))
    }
}
