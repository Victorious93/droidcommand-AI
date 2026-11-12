package ai.droidcommand.llm.routing

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.pchost.InMemoryPcHostSettingsStore
import ai.droidcommand.llm.pchost.PcHostAddress
import ai.droidcommand.llm.pchost.PcHostAddressParse
import ai.droidcommand.llm.pchost.PcHostClient
import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostTarget
import ai.droidcommand.llm.pchost.PcHostTimeouts
import ai.droidcommand.llm.pchost.PcModelLocation
import ai.droidcommand.remote.HttpUrlConnectionTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The inference policy and PC tool calls against a REAL model server (real inference). Skipped unless
 * `ROUTED_LIVE_URL` is set:
 *
 * ```
 * ROUTED_LIVE_URL=http://127.0.0.1:11434 ROUTED_LIVE_KIND=ollama ROUTED_LIVE_MODEL=qwen2.5:0.5b \
 *   ROUTED_LIVE_NO_TOOLS_MODEL=smollm2:135m ./gradlew :core-llm-factory:test --tests '*RoutedLiveServerTest*' -i
 * ```
 *
 * `ROUTED_LIVE_KIND=openai-compatible` for a llama.cpp `llama-server`. `ROUTED_LIVE_NO_TOOLS_MODEL` (Ollama) names a
 * model the server does not list tools for. Same transport class as the phone. It is not a phone: no Android, no
 * Wi-Fi. A real model decides whether to call a tool, so the tool test states what happened rather than assuming it.
 */
class RoutedLiveServerTest {
    private val url = System.getenv("ROUTED_LIVE_URL")?.takeIf { it.isNotBlank() }
    private val kind = PcHostKind.byId(System.getenv("ROUTED_LIVE_KIND")) ?: PcHostKind.OLLAMA
    private val model = System.getenv("ROUTED_LIVE_MODEL").orEmpty()
    private val noToolsModel = System.getenv("ROUTED_LIVE_NO_TOOLS_MODEL")?.takeIf { it.isNotBlank() }

    private fun address() = (PcHostAddress.parse(kind, url!!) as PcHostAddressParse.Ok).address

    private inner class World(policy: InferencePolicy, modelName: String = model) {
        // Anything that is not the server under test fails the run: this is what "internet unavailable" looks like.
        val transport = RecordingTransport(HttpUrlConnectionTransport()) { !it.startsWith(address().base) }
        val vault = InMemorySecretsVault()
        val target = PcHostTarget(kind, address(), modelName, PcModelLocation.UNKNOWN)
        val pcStore = InMemoryPcHostSettingsStore().also { it.save(kind, PcHostEndpoint(address().base, modelName, PcModelLocation.UNKNOWN)) }
        val inspect = { t: PcHostTarget, key: String? -> PcHostClient(transport, 4_000, 20_000).inspect(t, key) }
        var cloudBuilt = 0
        val session = ChatSession(
            vault,
            InMemoryConversationStore(),
            transport = transport,
            providerFor = { _, _ ->
                cloudBuilt++
                error("a cloud provider must not be built")
            },
            pcHostTimeouts = PcHostTimeouts(connectMillis = 5_000, readMillis = 180_000),
            router = RouteResolver(
                vault,
                InMemoryInferenceSettingsStore(InferenceSettings(policy)),
                pcStore,
                deviceModels = { null },
                cloudChoice = { CloudChoice(CloudProviderCatalog.all.first(), "x") },
                inspectPc = inspect,
            ),
            inspectPc = inspect,
        )
    }

    @Test
    fun `the server reports what routing needs - tools, window, location`() {
        if (url == null) return
        val world = World(InferencePolicy.PC_ONLY)
        val facts = world.inspect(world.target, null)
        println("LIVE facts for $model on ${kind.label}: $facts")
        assertTrue(facts.reachable, facts.problem)
        assertEquals(PcModelLocation.SERVER_LOCAL, facts.location, "a model installed on the server must be established as local")
        assertNotNull(facts.contextTokens, "the served window must be known")
    }

    @Test
    fun `my PC only - a real reply arrives, only the server is contacted, and the route is reported`() {
        if (url == null) return
        val world = World(InferencePolicy.PC_ONLY)
        val shown = StringBuilder()

        val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("Reply with one short sentence about the sea.") { shown.append(it) })

        println("LIVE reply: ${reply.text.take(200)}")
        println("LIVE route: ${reply.route?.summary()}")
        assertTrue(reply.text.isNotBlank())
        assertEquals(reply.text, shown.toString(), "the streamed text is the saved reply")
        assertEquals(RouteEndpoint.Kind.PC, reply.route?.chosen?.kind)
        assertEquals(0, world.cloudBuilt)
        assertTrue(world.transport.urls.all { it.startsWith(address().base) }, world.transport.urls.toString())
    }

    @Test
    fun `a helper tool round on a real model - the call runs once through the secure path and its result is used`() {
        if (url == null) return
        val world = World(InferencePolicy.PC_ONLY)
        val tools = SecureTools(setOf("get_battery_level"))
        val battery = tools.add(SecureTools.Counting("get_battery_level"))
        world.session.setToolAccess(tools.runner, tools.specs("get_battery_level"))

        val result = world.session.sendRouted("Use the get_battery_level tool now, then tell me what it returned.")

        println("LIVE tool round: runs=${battery.inputs.size} approvals=${tools.approvalsAsked} result=$result")
        val reply = assertIs<ChatResult.Reply>(result)
        assertTrue(reply.notice == null, "the tools were offered: ${reply.notice}")
        assertEquals(1, battery.inputs.size, "the real model called the tool exactly once")
        assertEquals(listOf("get_battery_level"), tools.approvalsAsked.toList())
        assertTrue(reply.text.isNotBlank())
    }

    @Test
    fun `a real model the server lists no tools for answers without them and says so`() {
        if (url == null || noToolsModel == null) return
        val world = World(InferencePolicy.PC_ONLY, noToolsModel)
        val tools = SecureTools(setOf("get_battery_level"))
        val battery = tools.add(SecureTools.Counting("get_battery_level"))
        world.session.setToolAccess(tools.runner, tools.specs("get_battery_level"))

        val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("Say hello."))

        println("LIVE no-tools notice: ${reply.notice}")
        assertTrue(reply.notice.orEmpty().startsWith(ChatSession.PC_HOST_TOOLS_NOTICE), reply.notice)
        assertTrue(battery.inputs.isEmpty())
    }

    @Test
    fun `stopping a real reply saves nothing and the next request is answered`() {
        if (url == null) return
        val world = World(InferencePolicy.PC_ONLY)
        val stop = AtomicBoolean(false)
        var pieces = 0

        val stopped = world.session.sendRouted("Write a long story about a lighthouse, at least 400 words.", cancelled = stop::get) {
            if (++pieces == 3) {
                stop.set(true)
                world.session.requestStopPcHost()
            }
        }

        assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), stopped)
        assertTrue(world.session.history().isEmpty())
        stop.set(false)
        val reply = assertIs<ChatResult.Reply>(world.session.sendRouted("Say the single word: ready.", cancelled = stop::get))
        println("LIVE after stop: ${reply.text.take(80)}")
        assertEquals(2, world.session.history().size)
    }

    @Test
    fun `a message larger than the served window is not sent`() {
        if (url == null) return
        val world = World(InferencePolicy.PC_ONLY)
        val window = assertNotNull(world.inspect(world.target, null).contextTokens)

        val failure = assertIs<ChatResult.Failure>(world.session.sendRouted("word ".repeat(window)))

        println("LIVE oversized: ${failure.message.take(260)}")
        assertTrue(failure.message.contains("smaller than the request needs"), failure.message)
        assertTrue(world.transport.urls.none { it.endsWith("/v1/chat/completions") }, "no chat request for a message that cannot fit")
    }
}
