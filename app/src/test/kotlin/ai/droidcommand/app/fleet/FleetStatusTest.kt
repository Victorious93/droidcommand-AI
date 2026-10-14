package ai.droidcommand.app.fleet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetStatusTest {
    @Test
    fun `catalog package names match the companion protocol`() {
        assertEquals("ai.hackerai.companion", FleetCatalog.HACKERAI_PACKAGE)
        assertEquals("ai.pentestswarm.companion", FleetCatalog.PENTESTSWARM_PACKAGE)
        assertEquals("com.termux", FleetCatalog.TERMUX_PACKAGE)
        assertNull(FleetCatalog.ROOTFORGE.packageName)
        assertEquals(listOf("hackerai", "pentestswarm", "termux", "rootforge"), FleetCatalog.all.map { it.id })
    }

    @Test
    fun `installed companions stay disconnected in the status line`() {
        val rows = FleetStatus.resolve(FleetCatalog.all) { pkg ->
            pkg == FleetCatalog.HACKERAI_PACKAGE || pkg == FleetCatalog.TERMUX_PACKAGE
        }
        val hacker = rows.single { it.member.id == "hackerai" }
        val swarm = rows.single { it.member.id == "pentestswarm" }
        val termux = rows.single { it.member.id == "termux" }
        val rootforge = rows.single { it.member.id == "rootforge" }

        assertEquals(FleetInstallState.INSTALLED, hacker.installed)
        assertTrue(hacker.status.contains("disconnected"))
        assertFalse(hacker.status.contains("ready", ignoreCase = true))

        assertEquals(FleetInstallState.NOT_INSTALLED, swarm.installed)
        assertTrue(swarm.status.contains("Not installed"))

        assertEquals(FleetInstallState.INSTALLED, termux.installed)
        assertTrue(termux.status.contains("NullTermuxExecutor"))

        assertEquals(FleetInstallState.NOT_APPLICABLE, rootforge.installed)
        assertTrue(rootforge.status.contains("Not paired"))
        rows.forEach { row ->
            assertFalse(row.status.contains("Ready"), row.status)
        }
    }
}
