package ai.droidcommand.app.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkLinkCheckerTest {
    private fun link(source: ActiveNetworkSource) = NetworkLinkChecker(source).activeLink()

    @Test
    fun unmetered_network_is_unmetered() = assertEquals(NetworkLink.UNMETERED, link { ActiveNetwork(notMetered = true) })

    @Test
    fun metered_network_is_metered() = assertEquals(NetworkLink.METERED, link { ActiveNetwork(notMetered = false) })

    @Test
    fun no_active_network_or_capabilities_is_none() = assertEquals(NetworkLink.NONE, link { ActiveNetwork(notMetered = null) })

    @Test
    fun missing_connectivity_service_does_not_block_the_download() = assertEquals(NetworkLink.UNMETERED, link { null })
}
