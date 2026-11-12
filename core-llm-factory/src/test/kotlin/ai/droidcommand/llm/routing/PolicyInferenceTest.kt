package ai.droidcommand.llm.routing

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.PlannerDecision
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.LocalProviderResources
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.pchost.InMemoryPcHostSettingsStore
import ai.droidcommand.llm.pchost.PcHostClient
import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcHostTimeouts
import ai.droidcommand.llm.pchost.PcModelLocation
import ai.droidcommand.llm.routing.FakeModelServer.Companion.toolCalls
import ai.droidcommand.remote.HttpUrlConnectionTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The policy on the features that are not a chat turn: a planner (Plan, Pilot/Forge) and one-prompt requests (the
 * code and companion assist pop-ups, automation prompts). FAKE backends; a recording transport proves what was not
 * contacted.
 */
class PolicyInferenceTest {
    private val dir: File = Files.createTempDirectory("policy-inference").toFile().also { it.deleteOnExit() }
    private val groq = CloudProviderCatalog.byId("groq")!!

    private class Cloud : LlmProvider {
        val requests = mutableListOf<LlmRequest>()
        override val config = LlmConfig(provider = "groq", model = "m")

        override fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return LlmResponse.Text("cloud reply")
        }
    }

    private inner class World(policy: InferencePolicy, confirmed: Boolean = false, pc: FakeModelServer? = null, forbid: (String) -> Boolean = { false }) {
        val backend = FakeBackend()
        val cloud = Cloud()
        var cloudBuilt = 0
        val vault = InMemorySecretsVault().also { it.putSecret(groq.secretId, "gsk-cloud-key-1234567890") }
        val transport = RecordingTransport(HttpUrlConnectionTransport(), forbid)
        val repository = ModelRepository(dir).also { repo ->
            val bytes = "weights".toByteArray()
            File(dir, "m.gguf").writeBytes(bytes)
            repo.register(ModelMetadata("tiny", "Tiny", "m.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 2048))
        }
        private val pcStore = InMemoryPcHostSettingsStore().also { store ->
            pc?.let { store.save(PcHostKind.OLLAMA, PcHostEndpoint(it.address, it.model, PcModelLocation.SERVER_LOCAL)) }
        }
        val resolver = RouteResolver(
            vault = vault,
            settings = InMemoryInferenceSettingsStore(InferenceSettings(policy, confirmed)),
            pcHosts = pcStore,
            deviceModels = { repository },
            cloudChoice = { CloudChoice(groq, "llama-x") },
            inspectPc = { target, key -> PcHostClient(transport, 1_500, 3_000).inspect(target, key) },
            fallbackDeviceModelId = { "tiny" },
        )
        val inference = PolicyInference(
            resolver,
            vault,
            transport,
            local = { LocalProviderResources(repository) { backend } },
            pcHostTimeouts = PcHostTimeouts(1_500, 4_000),
            cloudProviderFor = { _, _ ->
                cloudBuilt++
                cloud
            },
        )
    }

    @Test
    fun `a planner under this device only is refused with the reason and what to do, and nothing is contacted`() {
        val world = World(InferencePolicy.DEVICE_ONLY, forbid = { true })

        val refused = assertIs<RoutedPlanner.Refused>(world.inference.planner("Plan"))

        assertTrue(refused.message.contains("Plan was not sent anywhere"), refused.message)
        assertTrue(refused.message.contains("Plan is not run on an on-device model"), refused.message)
        assertTrue(refused.message.contains("choose another policy"), refused.message)
        assertTrue(world.transport.urls.isEmpty())
        assertEquals(0, world.cloudBuilt)
        assertTrue(world.backend.seen.isEmpty())
    }

    @Test
    fun `a planner under my PC only uses a tool-capable PC model and only the PC`() {
        FakeModelServer().use { pc ->
            pc.chat += toolCalls(StreamedCall("c1", "get_battery_status", listOf("{}")))
            val world = World(InferencePolicy.PC_ONLY, pc = pc, forbid = { !it.startsWith(pc.address) })

            val ready = assertIs<RoutedPlanner.Ready>(world.inference.planner("Plan"))
            val decision = ready.planner.decide("check the battery", ConversationContext(), listOf(ToolSpec("get_battery_status", "Battery level")), null)

            assertEquals(PlannerDecision.InvokeTool("get_battery_status", emptyMap()), decision)
            assertTrue(Json.parseToJsonElement(pc.chats.single().body).jsonObject.containsKey("tools"), "the planner's tools reach the PC model")
            assertEquals(0, world.cloudBuilt)
            assertTrue(world.cloud.requests.isEmpty())
        }
    }

    @Test
    fun `a planner under my PC only is refused when the PC model cannot call tools`() {
        FakeModelServer(capabilities = listOf("completion")).use { pc ->
            val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)

            val refused = assertIs<RoutedPlanner.Refused>(world.inference.planner("Pilot"))

            assertTrue(refused.message.contains("Pilot needs a model that can call tools"), refused.message)
            assertTrue(refused.message.contains("Ollama lists only completion"), refused.message)
            assertTrue(pc.chats.isEmpty())
            assertEquals(0, world.cloudBuilt)
        }
    }

    @Test
    fun `a planner with confirmed cloud fallback reaches the cloud only after the PC, and not without the confirmation`() {
        val pc = FakeModelServer()
        val unconfirmed = World(InferencePolicy.CLOUD_FALLBACK, confirmed = false, pc = pc)
        val confirmed = World(InferencePolicy.CLOUD_FALLBACK, confirmed = true, pc = pc)
        pc.close()

        assertIs<RoutedPlanner.Refused>(unconfirmed.inference.planner("Plan"))
        assertEquals(0, unconfirmed.cloudBuilt)

        val ready = assertIs<RoutedPlanner.Ready>(confirmed.inference.planner("Plan"))
        assertEquals(PlannerDecision.Complete("cloud reply"), ready.planner.decide("x", ConversationContext(), emptyList(), null))
        assertEquals(listOf(RouteEndpoint.Kind.CLOUD), ready.plan.candidates.map { it.endpoint.kind })
    }

    @Test
    fun `a one-prompt request under this device only is answered on the device with nothing sent`() {
        val world = World(InferencePolicy.DEVICE_ONLY, forbid = { true })

        val reply = assertIs<ChatResult.Reply>(world.inference.ask("Code assist", "explain this function"))

        assertEquals("device reply", reply.text)
        assertEquals("Answered by This device, model Tiny.", reply.notice)
        assertEquals(RouteEndpoint.Kind.DEVICE, reply.route?.chosen?.kind)
        assertTrue(world.transport.urls.isEmpty())
        assertEquals(0, world.cloudBuilt)
    }

    @Test
    fun `a one-prompt request falls from an unreachable PC to the device and never to the cloud`() {
        val pc = FakeModelServer()
        val world = World(InferencePolicy.PC_THEN_DEVICE, pc = pc)
        pc.close()

        val reply = assertIs<ChatResult.Reply>(world.inference.ask("Companion assist", "summarise"))

        assertEquals("device reply", reply.text)
        assertTrue(assertIs<String>(reply.route?.fallbackReason).contains("could not reach"))
        assertEquals(0, world.cloudBuilt)
    }

    @Test
    fun `a one-prompt request too large for every allowed place is refused before anything is sent`() {
        val world = World(InferencePolicy.DEVICE_ONLY, forbid = { true })

        val failure = assertIs<ChatResult.Failure>(world.inference.ask("Code assist", "word ".repeat(3_000)))

        assertTrue(failure.message.contains("context window (2048 tokens) is smaller than the request needs"), failure.message)
        assertTrue(world.backend.seen.isEmpty())
        assertNull(failure.route?.chosen)
    }

    @Test
    fun `the gate names the policy for a request a screen makes on its own`() {
        val strict = World(InferencePolicy.PC_ONLY)
        val message = assertIs<String>(strict.inference.refusalFor(ProviderType.CLOUD, "Asking Groq"))
        assertTrue(message.contains("Asking Groq uses an internet service") && message.contains("My PC only") && message.contains("Nothing was sent"), message)
        assertNull(strict.inference.refusalFor(ProviderType.SELF_HOSTED, "x"))
        assertNull(World(InferencePolicy.MANUAL).inference.refusalFor(ProviderType.CLOUD, "x"), "the manual policy restricts nothing")
        assertNull(World(InferencePolicy.CLOUD_FALLBACK, confirmed = true).inference.refusalFor(ProviderType.CLOUD, "x"))
        assertIs<String>(World(InferencePolicy.CLOUD_FALLBACK, confirmed = false).inference.refusalFor(ProviderType.CLOUD, "x"))
    }
}
