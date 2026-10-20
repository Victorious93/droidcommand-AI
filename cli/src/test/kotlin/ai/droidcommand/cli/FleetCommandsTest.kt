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
                    "mesh_status",
                    "browser_status",
                    "victorsms_status",
                    "victorspoof_status",
                    "run_termux_command",
                ),
            ),
        )
        assertTrue(names.none { it.startsWith("rootforge_") })
    }

    @Test
    fun `list-fleet text is the KnownFleet catalog`() {
        assertEquals(11, KnownFleet.ALL.size)
        val text = KnownFleet.formatListing()
        assertTrue(text.contains("kyc-auditlab"))
        assertTrue(text.contains("edl-commander"))
        assertTrue(text.contains("file-manager"))
        assertTrue(text.contains("keyboard"))
        assertTrue(text.contains("mesh"))
        assertTrue(text.contains("browser"))
        assertTrue(text.contains("aidl=false  dependencyGuard=false"))
        assertTrue(text.contains("ai.pentestswarm.companion"))
        assertTrue(text.contains("ai.rootforge.os.action.BRIDGE"))
        assertTrue(text.contains("ai.victorious.filemanager.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.mesh.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.browser.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.sms.action.BRIDGE"))
        assertTrue(text.contains("victorsms"))
        assertTrue(text.contains("com.victorious.spoof.action.BRIDGE"))
        assertTrue(text.contains("victorspoof"))
        assertTrue(text.contains("RootForge/File Manager/Keyboard/Mesh/Browser/VictorSMS/VictorSpoof Messenger is not that"))
        assertTrue(text.contains("signingCertSha256: [Unknown]"))
        assertTrue(text.contains("com.victorious.mesh"))
        assertTrue(text.contains("com.victorious.browser"))
        assertTrue(text.contains("ai.rootforge.os.debug"))
        assertTrue(text.contains("[planned/unverified]"))
    }
}
