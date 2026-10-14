package ai.droidcommand.app.fleet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetStatusTest {
    @Test
    fun `catalog package names match the companion protocol and KnownFleet`() {
        assertEquals("ai.hackerai.companion", FleetCatalog.HACKERAI_PACKAGE)
        assertEquals("ai.pentestswarm.companion", FleetCatalog.PENTESTSWARM_PACKAGE)
        assertEquals("com.termux", FleetCatalog.TERMUX_PACKAGE)
        assertEquals("ai.edlcommander.companion", FleetCatalog.EDLCOMMANDER_PACKAGE)
        assertNull(FleetCatalog.ROOTFORGE.packageName)
        assertNull(FleetCatalog.KYC_AUDITLAB.packageName)
        assertEquals(
            listOf("hackerai", "pentestswarm", "rootforge", "termux", "edl-commander", "kyc-auditlab"),
            FleetCatalog.all.map { it.id },
        )
    }

    @Test
    fun `installed companions stay disconnected and nothing is LIVE`() {
        val rows = FleetStatus.resolve(FleetCatalog.all) { pkg ->
            pkg == FleetCatalog.HACKERAI_PACKAGE || pkg == FleetCatalog.TERMUX_PACKAGE
        }
        val hacker = rows.single { it.member.id == "hackerai" }
        val swarm = rows.single { it.member.id == "pentestswarm" }
        val termux = rows.single { it.member.id == "termux" }
        val rootforge = rows.single { it.member.id == "rootforge" }
        val edl = rows.single { it.member.id == "edl-commander" }
        val kyc = rows.single { it.member.id == "kyc-auditlab" }

        assertEquals(FleetInstallState.INSTALLED, hacker.installed)
        assertTrue(hacker.status.contains("disconnected"))
        assertFalse(hacker.status.contains("ready", ignoreCase = true))

        assertEquals(FleetInstallState.NOT_INSTALLED, swarm.installed)
        assertTrue(swarm.status.contains("Not installed"))

        assertEquals(FleetInstallState.INSTALLED, termux.installed)
        assertTrue(termux.status.contains("NullTermuxExecutor"))

        assertEquals(FleetInstallState.NOT_APPLICABLE, rootforge.installed)
        assertTrue(rootforge.status.contains("Not paired"))
        assertTrue(rootforge.status.contains("Messenger"))
        assertTrue(rootforge.member.summary.contains("Never DependencyGuard"))

        assertEquals(FleetInstallState.NOT_INSTALLED, edl.installed)
        assertTrue(edl.status.contains("has not landed"))
        assertTrue(edl.member.summary.contains("BOUND_COMPANION"))

        assertEquals(FleetInstallState.NOT_APPLICABLE, kyc.installed)
        assertTrue(kyc.status.contains("/scope admin"))

        rows.forEach { row ->
            assertFalse(row.status.contains("ready", ignoreCase = true), row.status)
        }
    }
}
