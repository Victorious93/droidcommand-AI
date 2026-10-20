package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.messenger.ExternalNodeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetStatusTest {
    @Test
    fun `catalog package names match the companion protocol and KnownFleet`() {
        assertEquals("ai.hackerai.companion", FleetCatalog.HACKERAI_PACKAGE)
        assertEquals("ai.pentestswarm.companion", FleetCatalog.PENTESTSWARM_PACKAGE)
        assertEquals("com.termux", FleetCatalog.TERMUX_PACKAGE)
        assertEquals("ai.edlcommander.companion", FleetCatalog.EDLCOMMANDER_PACKAGE)
        assertEquals("ai.edlcommander.companion.BIND", FleetCatalog.EDLCOMMANDER_BIND_ACTION)
        assertEquals("ai.edlcommander.companion.permission.BIND_COMPANION", FleetCatalog.EDLCOMMANDER_BIND_PERMISSION)
        assertEquals("ai.rootforge.os", FleetCatalog.ROOTFORGE_PACKAGE)
        assertEquals("ai.victorious.filemanager", FleetCatalog.FILEMANAGER_PACKAGE)
        assertEquals("com.victorious.keyboard", FleetCatalog.KEYBOARD_PACKAGE)
        assertEquals("com.victorious.mesh", FleetCatalog.MESH_PACKAGE)
        assertEquals("com.victorious.browser", FleetCatalog.BROWSER_PACKAGE)
        assertEquals("com.victorious.sms", FleetCatalog.VICTORSMS_PACKAGE)
        assertEquals("com.victorious.spoof", FleetCatalog.VICTORSPOOF_PACKAGE)
        assertEquals("com.victorious.flash.android", FleetCatalog.FLASH_PACKAGE)
        assertEquals("com.identityshield.companion", FleetCatalog.KYC_PACKAGE)
        assertEquals(FleetCatalog.ROOTFORGE_PACKAGE, FleetCatalog.ROOTFORGE.packageName)
        assertEquals(ExternalNodeCatalog.ROOTFORGE.primaryPackage, FleetCatalog.ROOTFORGE_PACKAGE)
        assertEquals(ExternalNodeCatalog.FILE_MANAGER.primaryPackage, FleetCatalog.FILEMANAGER_PACKAGE)
        assertEquals(ExternalNodeCatalog.KEYBOARD.primaryPackage, FleetCatalog.KEYBOARD_PACKAGE)
        assertEquals(ExternalNodeCatalog.MESH.primaryPackage, FleetCatalog.MESH_PACKAGE)
        assertEquals(ExternalNodeCatalog.BROWSER.primaryPackage, FleetCatalog.BROWSER_PACKAGE)
        assertEquals(ExternalNodeCatalog.VICTOR_SMS.primaryPackage, FleetCatalog.VICTORSMS_PACKAGE)
        assertEquals(ExternalNodeCatalog.VICTOR_SPOOF.primaryPackage, FleetCatalog.VICTORSPOOF_PACKAGE)
        assertEquals(ExternalNodeCatalog.VICTORIOUS_FLASH.primaryPackage, FleetCatalog.FLASH_PACKAGE)
        assertEquals(ExternalNodeCatalog.KYC_AUDITLAB.primaryPackage, FleetCatalog.KYC_PACKAGE)
        assertEquals(FleetCatalog.KYC_PACKAGE, FleetCatalog.KYC_AUDITLAB.packageName)
        assertEquals(FleetCatalog.FILEMANAGER_PACKAGE, FleetCatalog.FILE_MANAGER.packageName)
        assertEquals(FleetCatalog.KEYBOARD_PACKAGE, FleetCatalog.KEYBOARD.packageName)
        assertEquals(FleetCatalog.MESH_PACKAGE, FleetCatalog.MESH.packageName)
        assertEquals(FleetCatalog.BROWSER_PACKAGE, FleetCatalog.BROWSER.packageName)
        assertEquals(FleetCatalog.VICTORSMS_PACKAGE, FleetCatalog.VICTOR_SMS.packageName)
        assertEquals(FleetCatalog.VICTORSPOOF_PACKAGE, FleetCatalog.VICTOR_SPOOF.packageName)
        assertEquals(FleetCatalog.FLASH_PACKAGE, FleetCatalog.VICTORIOUS_FLASH.packageName)
        assertEquals(
            listOf(FleetCatalog.ROOTFORGE_DEBUG_PACKAGE),
            FleetCatalog.ROOTFORGE.extraPackages,
        )
        assertEquals(
            listOf(FleetCatalog.FILEMANAGER_DEBUG_PACKAGE),
            FleetCatalog.FILE_MANAGER.extraPackages,
        )
        assertEquals(
            listOf(FleetCatalog.KEYBOARD_DEBUG_PACKAGE, FleetCatalog.KEYBOARD_BENCH_PACKAGE),
            FleetCatalog.KEYBOARD.extraPackages,
        )
        assertEquals(
            ExternalNodeCatalog.all.flatMap { it.packages }.toSet(),
            setOf(
                FleetCatalog.ROOTFORGE,
                FleetCatalog.FILE_MANAGER,
                FleetCatalog.KEYBOARD,
                FleetCatalog.MESH,
                FleetCatalog.BROWSER,
                FleetCatalog.VICTOR_SMS,
                FleetCatalog.VICTOR_SPOOF,
                FleetCatalog.VICTORIOUS_FLASH,
                FleetCatalog.KYC_AUDITLAB,
            ).flatMap { it.androidPackages }.toSet(),
        )
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
                "browser",
                "victorsms",
                "victorspoof",
                "victorious-flash",
            ),
            FleetCatalog.all.map { it.id },
        )
        assertTrue(FleetCatalog.queryPackages.containsAll(FleetCatalog.all.mapNotNull { it.packageName }))
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
        val browser = rows.single { it.member.id == "browser" }
        val victorSms = rows.single { it.member.id == "victorsms" }
        val victorSpoof = rows.single { it.member.id == "victorspoof" }
        val flash = rows.single { it.member.id == "victorious-flash" }

        assertEquals(FleetInstallState.INSTALLED, hacker.installed)
        assertTrue(hacker.status.contains("disconnected"))
        assertFalse(hacker.status.contains("ready", ignoreCase = true))

        assertEquals(FleetInstallState.NOT_INSTALLED, swarm.installed)
        assertTrue(swarm.status.contains("Not installed"))

        assertEquals(FleetInstallState.INSTALLED, termux.installed)
        assertTrue(termux.status.contains("RUN_COMMAND_PENDING_INTENT"))
        assertTrue(termux.status.contains("UNVERIFIED"))

        assertEquals(FleetInstallState.NOT_INSTALLED, rootforge.installed)
        assertTrue(rootforge.status.contains("not paired"))
        assertTrue(rootforge.status.contains("Messenger"))
        assertTrue(rootforge.status.contains("Not LIVE"))
        assertTrue(rootforge.member.summary.contains("Never DependencyGuard"))

        assertEquals(FleetInstallState.NOT_INSTALLED, edl.installed)
        assertTrue(edl.status.contains("IEdlCompanion"))
        assertTrue(edl.status.contains("ai.edlcommander.companion.BIND"))
        assertTrue(edl.status.contains("does not auto-bind"))
        assertTrue(edl.status.contains("Not LIVE"))
        assertTrue(edl.member.summary.contains("BOUND_COMPANION"))
        assertTrue(edl.member.summary.contains("ai.edlcommander.companion.permission.BIND_COMPANION"))

        assertEquals(FleetInstallState.NOT_INSTALLED, kyc.installed)
        assertTrue(kyc.status.contains("/scope admin"))
        assertTrue(kyc.status.contains("[provisional/pending-stable-cert]"))
        assertTrue(kyc.member.summary.contains("remoteAllowed:false"))
        assertTrue(kyc.member.summary.contains("fail-open"))
        assertTrue(kyc.member.summary.contains("com.identityshield.companion"))
        assertTrue(kyc.member.summary.contains("KYC PR #29"))
        assertTrue(kyc.status.contains("KYC PR #29"))

        assertEquals(FleetInstallState.NOT_INSTALLED, fileManager.installed)
        assertTrue(fileManager.status.contains("not paired"))
        assertTrue(fileManager.member.summary.contains("Never DependencyGuard"))
        assertTrue(fileManager.member.summary.contains("ai.victorious.filemanager.action.BRIDGE"))
        assertFalse(fileManager.status.contains("ready", ignoreCase = true), fileManager.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, keyboard.installed)
        assertTrue(keyboard.status.contains("not paired"))
        assertTrue(keyboard.member.summary.contains("Never DependencyGuard"))
        assertTrue(keyboard.member.summary.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(keyboard.member.summary.contains("Never forwards typed text"))
        assertFalse(keyboard.status.contains("ready", ignoreCase = true), keyboard.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, mesh.installed)
        assertTrue(mesh.status.contains("not paired"))
        assertTrue(mesh.status.contains("Not LIVE"))
        assertTrue(mesh.member.summary.contains("Never DependencyGuard"))
        assertTrue(mesh.member.summary.contains("com.victorious.mesh.action.BRIDGE"))
        assertTrue(mesh.member.summary.contains("own release key"))
        assertFalse(mesh.status.contains("ready", ignoreCase = true), mesh.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, browser.installed)
        assertTrue(browser.status.contains("not paired"))
        assertTrue(browser.status.contains("Not LIVE"))
        assertTrue(browser.member.summary.contains("Never DependencyGuard"))
        assertTrue(browser.member.summary.contains("com.victorious.browser.action.BRIDGE"))
        assertTrue(browser.member.summary.contains("own release key"))
        assertFalse(browser.status.contains("ready", ignoreCase = true), browser.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, victorSms.installed)
        assertTrue(victorSms.status.contains("not paired"))
        assertTrue(victorSms.status.contains("Not LIVE"))
        assertTrue(victorSms.member.summary.contains("Never DependencyGuard"))
        assertTrue(victorSms.member.summary.contains("com.victorious.sms.action.BRIDGE"))
        assertTrue(victorSms.member.summary.contains("fail-open"))
        assertTrue(victorSms.member.summary.contains("sms.remote_command.forward"))
        assertTrue(victorSms.member.summary.contains("[planned/pending-merge]"))
        assertFalse(victorSms.status.contains("ready", ignoreCase = true), victorSms.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, victorSpoof.installed)
        assertTrue(victorSpoof.status.contains("not paired"))
        assertTrue(victorSpoof.status.contains("Not LIVE"))
        assertTrue(victorSpoof.member.summary.contains("Never DependencyGuard"))
        assertTrue(victorSpoof.member.summary.contains("com.victorious.spoof.action.BRIDGE"))
        assertTrue(victorSpoof.member.summary.contains("fail-open"))
        assertFalse(victorSpoof.status.contains("ready", ignoreCase = true), victorSpoof.status)

        assertEquals(FleetInstallState.NOT_INSTALLED, flash.installed)
        assertTrue(flash.status.contains("not paired"))
        assertTrue(flash.status.contains("Not LIVE"))
        assertTrue(flash.member.summary.contains("Never DependencyGuard"))
        assertTrue(flash.member.summary.contains("com.victorious.flash.android.FLEET_BRIDGE"))
        assertTrue(flash.member.summary.contains("gadget.expose is DESTRUCTIVE"))
        assertTrue(flash.member.summary.contains("enforced"))
        assertFalse(flash.status.contains("ready", ignoreCase = true), flash.status)

        rows.forEach { row ->
            assertFalse(row.status.contains("ready", ignoreCase = true), row.status)
        }

        val installedFm = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.FILEMANAGER_PACKAGE }
            .single { it.member.id == "file-manager" }
        assertEquals(FleetInstallState.INSTALLED, installedFm.installed)
        assertTrue(installedFm.status.contains("Pair the observed signing-cert"))
        assertTrue(installedFm.status.contains("Not LIVE"))
        assertFalse(installedFm.status.contains("ready", ignoreCase = true), installedFm.status)

        val installedKb = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.KEYBOARD_PACKAGE }
            .single { it.member.id == "keyboard" }
        assertEquals(FleetInstallState.INSTALLED, installedKb.installed)
        assertTrue(installedKb.status.contains("Pair the observed signing-cert"))
        assertTrue(installedKb.status.contains("Not LIVE"))
        assertFalse(installedKb.status.contains("ready", ignoreCase = true), installedKb.status)

        val installedMesh = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.MESH_PACKAGE }
            .single { it.member.id == "mesh" }
        assertEquals(FleetInstallState.INSTALLED, installedMesh.installed)
        assertTrue(installedMesh.status.contains("Pair the observed signing-cert"))
        assertTrue(installedMesh.status.contains("Not LIVE"))
        assertFalse(installedMesh.status.contains("ready", ignoreCase = true), installedMesh.status)

        val installedBrowser = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.BROWSER_PACKAGE }
            .single { it.member.id == "browser" }
        assertEquals(FleetInstallState.INSTALLED, installedBrowser.installed)
        assertTrue(installedBrowser.status.contains("Pair the observed signing-cert"))
        assertTrue(installedBrowser.status.contains("Not LIVE"))
        assertFalse(installedBrowser.status.contains("ready", ignoreCase = true), installedBrowser.status)

        val installedFlash = FleetStatus.resolve(FleetCatalog.all) { it == FleetCatalog.FLASH_PACKAGE }
            .single { it.member.id == "victorious-flash" }
        assertEquals(FleetInstallState.INSTALLED, installedFlash.installed)
        assertTrue(installedFlash.status.contains("Pair the observed signing-cert"))
        assertTrue(installedFlash.status.contains("Not LIVE"))
        assertFalse(installedFlash.status.contains("ready", ignoreCase = true), installedFlash.status)
    }
}
