package ai.droidcommand.security

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetCatalogTest {
    @Test
    fun `the room fleet is the seven members in wiring order`() {
        assertEquals(
            listOf(
                "pentestswarm",
                "rootforge",
                "victorsuite",
                "edl-commander",
                "kyc-auditlab",
                "file-manager",
                "keyboard",
            ),
            KnownFleet.ALL.map { it.id },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), KnownFleet.ALL.map { it.wiringOrder })
    }

    @Test
    fun `none of the seven is LIVE and the static checker never reports AVAILABLE`() {
        val checker = StaticFleetHealthChecker()
        for (member in KnownFleet.ALL) {
            assertNotEquals(FleetHonestyStatus.LIVE, member.status, member.id)
            val meta = checker.verify(member.capabilityId, member.providerId)
            assertNotEquals(CapabilityState.AVAILABLE, meta.state, member.id)
            assertEquals(member.capabilityId, meta.id)
            assertEquals(member.refusalMessage, meta.lastError)
        }
    }

    @Test
    fun `latest consensus classes and DependencyGuard rules hold`() {
        assertEquals(FleetIntegrationClass.BOUND_COMPANION, KnownFleet.PENTEST_SWARM.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.PENTEST_SWARM.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.ROOTFORGE.integrationClass)
        assertEquals(FleetIntegrationClass.TERMUX_IPC, KnownFleet.VICTORSUITE.integrationClass)
        assertEquals(FleetIntegrationClass.BOUND_COMPANION, KnownFleet.EDL_COMMANDER.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.EDL_COMMANDER.status)
        assertEquals(FleetIntegrationClass.HOST_PROCESS_TOOL, KnownFleet.KYC_AUDITLAB.integrationClass)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.FILE_MANAGER.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.FILE_MANAGER.status)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.KEYBOARD.integrationClass)
        assertEquals(FleetHonestyStatus.PLANNED, KnownFleet.KEYBOARD.status)

        assertTrue(KnownFleet.PENTEST_SWARM.usesAidl)
        assertTrue(KnownFleet.PENTEST_SWARM.usesDependencyGuard)
        assertTrue(KnownFleet.EDL_COMMANDER.usesAidl)
        assertTrue(KnownFleet.EDL_COMMANDER.usesDependencyGuard)
        for (member in listOf(
            KnownFleet.ROOTFORGE,
            KnownFleet.VICTORSUITE,
            KnownFleet.KYC_AUDITLAB,
            KnownFleet.FILE_MANAGER,
            KnownFleet.KEYBOARD,
        )) {
            assertFalse(member.usesAidl, member.id)
            assertFalse(member.usesDependencyGuard, member.id)
        }
    }

    @Test
    fun `descriptor construction rejects mixed AIDL flags`() {
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.KYC_AUDITLAB.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.ROOTFORGE.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.FILE_MANAGER.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.KEYBOARD.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.EDL_COMMANDER.copy(usesAidl = false, usesDependencyGuard = false)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.PENTEST_SWARM.copy(usesDependencyGuard = false)
        }
    }

    @Test
    fun `capability states stay honest`() {
        val checker = StaticFleetHealthChecker()
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.PENTEST_SWARM.capabilityId, "pentestswarm").state)
        assertEquals(CapabilityState.REQUIRES_CONFIGURATION, checker.verify(KnownFleet.ROOTFORGE.capabilityId, "rootforge").state)
        assertEquals(CapabilityState.REQUIRES_TERMUX, checker.verify(KnownFleet.VICTORSUITE.capabilityId, "termux").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.EDL_COMMANDER.capabilityId, "edl-commander").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.KYC_AUDITLAB.capabilityId, "kyc-auditlab").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.FILE_MANAGER.capabilityId, "file-manager").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.KEYBOARD.capabilityId, "keyboard").state)
    }

    @Test
    fun `registerCapabilities fills the existing CapabilityRegistry`() {
        val registry = InMemoryCapabilityRegistry(StaticFleetHealthChecker())
        KnownFleet.registerCapabilities(registry, now = 1_000L)
        assertEquals(7, registry.listCapabilities().size)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, registry.getCapability(KnownFleet.PENTEST_SWARM.capabilityId)?.state)
        val reverified = registry.reverify(KnownFleet.KYC_AUDITLAB.capabilityId)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, reverified.state)
        assertFailsWith<UnknownCapabilityException> {
            StaticFleetHealthChecker().verify(CapabilityId("fleet.unknown"), "nope")
        }
    }

    @Test
    fun `planned stubs are Pentest EDL KYC File Manager and Keyboard and never succeed`() {
        val tools = KnownFleet.plannedStubs()
        assertEquals(
            listOf(
                "pentest_swarm_status",
                "edl_commander_status",
                "kyc_auditlab_status",
                "filemanager_status",
                "keyboard_status",
            ),
            tools.map { it.spec.name },
        )
        for (tool in tools) {
            val result = tool.execute(emptyMap())
            assertTrue(result is ToolResult.Failure, tool.spec.name)
        }
        val edl = tools.first { it.spec.name == "edl_commander_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(edl.reason.contains("companion APK has not landed"))
        assertTrue(edl.reason.contains("does not bind, flash"))
        val kyc = tools.first { it.spec.name == "kyc_auditlab_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(kyc.reason.contains("/scope admin"))
        val pentest = tools.first { it.spec.name == "pentest_swarm_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(pentest.reason.contains("awaiting the base companion repo"))
        val fileManager = tools.first { it.spec.name == "filemanager_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(fileManager.reason.contains("does not bind Messenger"))
        assertTrue(fileManager.reason.contains("ai.victorious.filemanager.action.BRIDGE"))
        val keyboard = tools.first { it.spec.name == "keyboard_status" }.execute(emptyMap()) as ToolResult.Failure
        assertTrue(keyboard.reason.contains("does not bind Messenger"))
        assertTrue(keyboard.reason.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(keyboard.reason.contains("Never forwards typed text"))
    }

    @Test
    fun `planned stub refuses to wrap a member that is not PLANNED`() {
        assertFailsWith<IllegalArgumentException> {
            PlannedFleetTool(KnownFleet.ROOTFORGE)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFleetTool(KnownFleet.VICTORSUITE)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedFleetTool(KnownFleet.KYC_AUDITLAB.copy(status = FleetHonestyStatus.LIVE))
        }
    }

    @Test
    fun `RootForge is not SSH-only and VictorSuite stays on Termux`() {
        assertTrue(KnownFleet.ROOTFORGE.transports.any { it.contains("ai.rootforge.os.action.BRIDGE") })
        assertTrue(KnownFleet.ROOTFORGE.transports.any { it.contains("localhost") })
        assertTrue(KnownFleet.ROOTFORGE.transports.any { it.contains("fallback") })
        assertTrue(KnownFleet.ROOTFORGE.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.ROOTFORGE.notes.contains("never shared signing key"))
        assertEquals("com.termux", KnownFleet.VICTORSUITE.packageOrBinary)
        assertTrue(KnownFleet.VICTORSUITE.notes.contains("PR #15"))
        assertNull(KnownFleet.KYC_AUDITLAB.repo)
        assertEquals("Victorious93/EDL-Commander", KnownFleet.EDL_COMMANDER.repo)
        assertEquals("Victorious93/File-Manager", KnownFleet.FILE_MANAGER.repo)
        assertTrue(KnownFleet.FILE_MANAGER.packageOrBinary.contains("ai.victorious.filemanager"))
        assertTrue(KnownFleet.FILE_MANAGER.transports.any { it.contains("ai.victorious.filemanager.action.BRIDGE") })
        assertTrue(KnownFleet.FILE_MANAGER.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.FILE_MANAGER.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.FILE_MANAGER.notes.contains("dca_required:false"))
        assertEquals("Victorious93/Victorious-Keyboard", KnownFleet.KEYBOARD.repo)
        assertTrue(KnownFleet.KEYBOARD.packageOrBinary.contains("com.victorious.keyboard"))
        assertTrue(KnownFleet.KEYBOARD.packageOrBinary.contains("com.victorious.keyboard.debug"))
        assertTrue(KnownFleet.KEYBOARD.packageOrBinary.contains("com.victorious.keyboard.bench"))
        assertTrue(KnownFleet.KEYBOARD.transports.any { it.contains("com.victorious.keyboard.action.BRIDGE") })
        assertTrue(KnownFleet.KEYBOARD.notes.contains("Never DependencyGuard"))
        assertTrue(KnownFleet.KEYBOARD.notes.contains("never KnownCompanions"))
        assertTrue(KnownFleet.KEYBOARD.notes.contains("Never forward typed text"))
        assertTrue(KnownFleet.KEYBOARD.notes.contains("undecided by Don"))
    }

    @Test
    fun `listing names every member wiring order and the companion rule`() {
        val text = KnownFleet.formatListing()
        for (id in listOf(
            "rootforge",
            "victorsuite",
            "kyc-auditlab",
            "edl-commander",
            "pentestswarm",
            "file-manager",
            "keyboard",
        )) {
            assertTrue(text.contains(id), id)
        }
        assertTrue(text.contains("None is LIVE"))
        assertTrue(text.contains("Pentest (when repo exists)"))
        assertTrue(text.contains("RootForge/File Manager/Keyboard Messenger is not that"))
        assertTrue(text.contains("ai.victorious.filemanager.action.BRIDGE"))
        assertTrue(text.contains("com.victorious.keyboard.action.BRIDGE"))
        assertTrue(text.contains("HackerAI"))
        assertTrue(text.contains("signingCertSha256: [Unknown]"))
        assertTrue(text.contains("Controller-side node signature check"))
        assertTrue(text.contains("com.victorious.mesh"))
        assertTrue(text.contains("com.victorious.browser"))
        assertTrue(text.contains("ai.rootforge.os.debug"))
        assertTrue(text.contains("[unstable/CI-secrets]"))
        assertTrue(text.contains("[planned/unverified]"))
    }

    @Test
    fun `fleet members expose android packages and the catalogued expected certs`() {
        assertEquals(listOf("ai.pentestswarm.companion"), KnownFleet.PENTEST_SWARM.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.PENTEST_SWARM_DEBUG_SHA256),
            KnownFleet.PENTEST_SWARM.expectedSigningCertSha256,
        )
        assertEquals(listOf("ai.edlcommander.companion"), KnownFleet.EDL_COMMANDER.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256),
            KnownFleet.EDL_COMMANDER.expectedSigningCertSha256,
        )
        assertEquals(listOf("ai.rootforge.os.debug"), KnownFleet.ROOTFORGE.androidPackages)
        assertEquals(
            listOf(KnownNodeSigningCatalog.ROOTFORGE_DEBUG_SHA256),
            KnownFleet.ROOTFORGE.expectedSigningCertSha256,
        )
        assertEquals(
            listOf(
                KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
            ),
            KnownFleet.VICTORSUITE.expectedSigningCertSha256,
        )
        assertEquals(
            listOf(KnownNodeSigningCatalog.KEYBOARD_DEBUG_SHA256),
            KnownFleet.KEYBOARD.expectedSigningCertSha256,
        )
        assertTrue(KnownFleet.FILE_MANAGER.expectedSigningCertSha256.isEmpty())
        val catalog = KnownNodeSigningCatalog.all()
        assertTrue(catalog.any { it.packageName == "com.victorious.mesh" && it.expectedCerts.isNotEmpty() })
        assertTrue(catalog.any { it.packageName == "com.victorious.browser" && it.expectedCerts.isNotEmpty() })
        assertTrue(
            catalog.first { it.packageName == "com.victorious.keyboard.bench" }.expectedCerts.isEmpty(),
        )
        assertTrue(
            catalog.first { it.packageName == "ai.victorious.filemanager" }.expectedCerts.isEmpty(),
        )
    }
}
