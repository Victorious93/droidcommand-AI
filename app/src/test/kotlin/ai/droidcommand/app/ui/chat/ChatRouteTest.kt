package ai.droidcommand.app.ui.chat

import ai.droidcommand.llm.pchost.PcHostEndpoint
import ai.droidcommand.llm.pchost.PcHostKind
import ai.droidcommand.llm.pchost.PcModelLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRouteTest {
    private val installed = setOf("user-aaaa", "hf-bbbb")

    @Test
    fun `a selected model that is installed answers on the device`() {
        assertEquals(ChatRoute.OnDevice("user-aaaa"), ChatRoute.decide("user-aaaa", installed, slashCommandNamesCloud = false))
    }

    @Test
    fun `with nothing selected on the device the cloud provider answers`() {
        assertEquals(ChatRoute.Cloud, ChatRoute.decide(null, installed, slashCommandNamesCloud = false))
    }

    @Test
    fun `a selected model whose file is gone is never replaced by the cloud provider`() {
        // Before: the saved choice was dropped at start-up and the next message went to the cloud provider.
        val route = ChatRoute.decide("user-gone", installed, slashCommandNamesCloud = false)
        assertEquals(ChatRoute.OnDeviceMissing(ChatRoute.MISSING_MESSAGE), route)
        assertEquals(ChatRoute.OnDeviceMissing(ChatRoute.MISSING_MESSAGE), ChatRoute.decide("user-gone", emptySet(), false))
    }

    @Test
    fun `a provider slash command sends that one message to the cloud on purpose`() {
        assertEquals(ChatRoute.Cloud, ChatRoute.decide("user-aaaa", installed, slashCommandNamesCloud = true))
        assertEquals(ChatRoute.Cloud, ChatRoute.decide("user-gone", installed, slashCommandNamesCloud = true))
    }

    private val pc = PcHostEndpoint("http://192.168.1.20:11434", "gemma4:latest", PcModelLocation.SERVER_LOCAL)

    @Test
    fun `a selected PC server answers, and wins over a leftover on-device choice`() {
        val route = ChatRoute.decide("user-aaaa", installed, slashCommandNamesCloud = false, pcHost = PcHostKind.OLLAMA, pcHostEndpoint = pc)
        val target = (route as ChatRoute.PcHosted).target
        assertEquals("http://192.168.1.20:11434", target.address.base)
        assertEquals("gemma4:latest", target.model)
        assertEquals(PcModelLocation.SERVER_LOCAL, target.location)
    }

    @Test
    fun `a selected PC server with nothing usable saved is never replaced by the cloud provider`() {
        val expected = ChatRoute.PcHostNotReady(ChatRoute.pcNotReady(PcHostKind.OLLAMA))
        assertEquals(expected, ChatRoute.decide(null, installed, false, pcHost = PcHostKind.OLLAMA, pcHostEndpoint = null))
        assertEquals(expected, ChatRoute.decide(null, installed, false, pcHost = PcHostKind.OLLAMA, pcHostEndpoint = pc.copy(model = "")))
        assertEquals(expected, ChatRoute.decide(null, installed, false, pcHost = PcHostKind.OLLAMA, pcHostEndpoint = pc.copy(address = "http://8.8.8.8:11434")))
    }

    @Test
    fun `a provider slash command still goes to the cloud while a PC server is selected`() {
        assertEquals(ChatRoute.Cloud, ChatRoute.decide(null, installed, slashCommandNamesCloud = true, pcHost = PcHostKind.OLLAMA, pcHostEndpoint = pc))
    }
}
