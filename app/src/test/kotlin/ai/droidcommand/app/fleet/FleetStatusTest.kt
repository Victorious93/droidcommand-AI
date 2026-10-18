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
        assertEquals("ai.victorious.filemanager", FleetCatalog.FILEMANAGER_PACKAGE)
        assertEquals("com.victorious.keyboard", FleetCatalog.KEYBOARD_PACKAGE)
        assertEquals("com.victorious.mesh", FleetCatalog.MESH_PACKAGE)
        assertNull(FleetCatalog.ROOTFORGE.packageName)
        assertNull(FleetCatalog.KYC_AUDITLAB.packageName)
        assertEquals(FleetCatalog.FILEMANAGER_PACKAGE, FleetCatalog.FILE_MANAGER.packageName)
        assertEquals(FleetCatalog.KEYBOARD_PACKAGE, FleetCatalog.KEYBOARD.packageName)
        assertEquals(FleetCatalog.MESH_PACKAGE, FleetCatalog.MESH.packageName)
        assertEquals(
            listOf(
                "hackerai",
                "pentestswarm",
                "rootforge",
                "termux",
                "edl-commander",
                "kyc-auditlab",
                "file-manager",
                "keyboard",
                "mesh",
            ),
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
        val fileManager = rows.single { it.member.id == "file-manager" }
        val keyboard = rows.single { it.member.id == "keyboard" }
        val mesh = rows.single { it.member.id == "mesh" }

        assertEquals(FleetInstallState.INSTALLED, hacker.installed)
        assertTrue(hacker.status.contains("disconnected"))
        assertFalse(hacker.status.contains("ready", ignoreCase = true))

        assertEquals(FleetInstallState.NOT_INSTALLED, swarm.installed)
        assertTrue(swarm.status.contains("Not installed"))

        assertEquals(FleetInstallState.INSTALLED, termux.installed)
        assertTrue(termux.status.contains("RUN_COMMAND_PENDING_INTENT"))
        assertTrue(termux.status.contains("UNVERIFIED"))

        assertEquals(FleetInstallState.NOT_APPLICABLE, rootforge.installed)
        assertTrue(rootforge.status.contains("Not paired"))
        assertTrue(rootforge.status.contains("Messenger"))
        assertTrue(rootforge.member.summary.contains("Never DependencyGuard"))

        assertEquals(FleetInstallState.NOT_INSTALLED, edl.installed)
        assertTrue(edl.status.contains("has not landed"))
        assertTrue(edl.member.summary.contains("BOUND_COMPANION"))

        assertEquals(FleetInstallState.NOT_APPLICABLE, kyc.installed)
        assertTrue(kyc.status.contains("/scope admin"))

        assertEquals(FleetInstallState.NOT_INSTALLED, fileManager.installed)
        assertTrue(fileManager.status.contains("does not bind Messenger"))
        assertTrue(fileManager.status.contains("PLANNED"))
        assertTrue(fileManager.member.summary.contains("Never DependencyGuard"))
        assertTrue(fileManager.member.summary.contains("ai.victorious.filemanager.action.BRIDGE"))
        assertFalse(fileManager.status.contains("ready", ignoreCase = true), fileManager.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, keyboard.installed)
        assertTrue(keyboard.status.contains("does not bind Messenger"))
        assertTrue(keyboard.status.contains("PLANNED"))
        assertTrue(keyboard.member.summary.contains("Never DependencyGuard"))
        assertTrue(keyboard.member.summary.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(keyboard.member.summary.contains("Never forwards typed text"))
        assertFalse(keyboard.status.contains("ready", ignoreCase = true), keyboard.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, mesh.installed)
        assertTrue(mesh.status.contains("does not bind Messenger"))
        assertTrue(mesh.status.contains("PLANNED"))
        assertTrue(mesh.member.summary.contains("Never DependencyGuard"))
        assertTrue(mesh.member.summary.contains("com.victorious.mesh.action.BRIDGE"))
        assertTrue(mesh.member.summary.contains("own release key"))
        assertFalse(mesh.status.contains("ready", ignoreCase = true), mesh.status)

        rows.forEach { row ->
            assertFalse(row.status.contains("ready", ignoreCase = true), row.status)
        }

        val installedFm = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.FILEMANAGER_PACKAGE }
            .single { it.member.id == "file-manager" }
        assertEquals(FleetInstallState.INSTALLED, installedFm.installed)
        assertTrue(installedFm.status.contains("Package probe only"))
        assertTrue(installedFm.status.contains("PLANNED"))
        assertFalse(installedFm.status.contains("ready", ignoreCase = true), installedFm.status)

        val installedKb = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.KEYBOARD_PACKAGE }
            .single { it.member.id == "keyboard" }
        assertEquals(FleetInstallState.INSTALLED, installedKb.installed)
        assertTrue(installedKb.status.contains("Package probe only"))
        assertTrue(installedKb.status.contains("PLANNED"))
        assertFalse(installedKb.status.contains("ready", ignoreCase = true), installedKb.status)

        val installedMesh = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.MESH_PACKAGE }
            .single { it.member.id == "mesh" }
        assertEquals(FleetInstallState.INSTALLED, installedMesh.installed)
        assertTrue(installedMesh.status.contains("Package probe only"))
        assertTrue(installedMesh.status.contains("PLANNED"))
        assertFalse(installedMesh.status.contains("ready", ignoreCase = true), installedMesh.status)
    }
}
