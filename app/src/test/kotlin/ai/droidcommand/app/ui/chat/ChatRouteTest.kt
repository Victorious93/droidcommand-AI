package ai.droidcommand.app.ui.chat

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
}
