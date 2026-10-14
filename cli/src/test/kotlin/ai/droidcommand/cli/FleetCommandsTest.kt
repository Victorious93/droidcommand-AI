package ai.droidcommand.cli

import ai.droidcommand.security.KnownFleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FleetCommandsTest {
    @Test
    fun `default session registers the three planned stubs and no extra companion bind`() {
        val names = buildSession().registry.list().map { it.name }
        assertTrue(
            names.containsAll(
                listOf(
                    "pentest_swarm_status",
                    "kyc_auditlab_status",
                    "edl_commander_status",
                    "run_termux_command",
                ),
            ),
        )
        assertTrue(names.none { it.startsWith("rootforge_") })
    }

    @Test
    fun `list-fleet text is the KnownFleet catalog`() {
        assertEquals(5, KnownFleet.ALL.size)
        val text = KnownFleet.formatListing()
        assertTrue(text.contains("kyc-auditlab"))
        assertTrue(text.contains("edl-commander"))
        assertTrue(text.contains("aidl=false  dependencyGuard=false"))
        assertTrue(text.contains("ai.pentestswarm.companion"))
        assertTrue(text.contains("ai.rootforge.os.action.BRIDGE"))
        assertTrue(text.contains("RootForge Messenger is not that"))
    }
}
