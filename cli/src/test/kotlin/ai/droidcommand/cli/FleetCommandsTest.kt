package ai.droidcommand.cli

import ai.droidcommand.security.KnownFleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FleetCommandsTest {
    @Test
    fun `default session registers the planned stubs and no extra companion bind`() {
        val names = buildSession().registry.list().map { it.name }
        assertTrue(
            names.containsAll(
                listOf(
                    "pentest_swarm_status",
                    "kyc_auditlab_status",
                    "edl_commander_status",
                    "filemanager_status",
                    "keyboard_status",
                    "run_termux_command",
                ),
            ),
        )
        assertTrue(names.none { it.startsWith("rootforge_") })
    }

    @Test
    fun `list-fleet text is the KnownFleet catalog`() {
        assertEquals(7, KnownFleet.ALL.size)
        val text = KnownFleet.formatListing()
        assertTrue(text.contains("kyc-auditlab"))
        assertTrue(text.contains("edl-commander"))
        assertTrue(text.contains("file-manager"))
        assertTrue(text.contains("keyboard"))
        assertTrue(text.contains("aidl=false  dependencyGuard=false"))
        assertTrue(text.contains("ai.pentestswarm.companion"))
        assertTrue(text.contains("ai.rootforge.os.action.BRIDGE"))
        assertTrue(text.contains("ai.victorious.filemanager.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(text.contains("RootForge/File Manager/Keyboard Messenger is not that"))
    }
}
