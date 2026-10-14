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
    fun `the room fleet is the five members in wiring order`() {
        assertEquals(
            listOf("pentestswarm", "rootforge", "victorsuite", "edl-commander", "kyc-auditlab"),
            KnownFleet.ALL.map { it.id },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), KnownFleet.ALL.map { it.wiringOrder })
    }

    @Test
    fun `none of the five is LIVE and the static checker never reports AVAILABLE`() {
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

        assertTrue(KnownFleet.PENTEST_SWARM.usesAidl)
        assertTrue(KnownFleet.PENTEST_SWARM.usesDependencyGuard)
        assertTrue(KnownFleet.EDL_COMMANDER.usesAidl)
        assertTrue(KnownFleet.EDL_COMMANDER.usesDependencyGuard)
        for (member in listOf(KnownFleet.ROOTFORGE, KnownFleet.VICTORSUITE, KnownFleet.KYC_AUDITLAB)) {
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
    }

    @Test
    fun `registerCapabilities fills the existing CapabilityRegistry`() {
        val registry = InMemoryCapabilityRegistry(StaticFleetHealthChecker())
        KnownFleet.registerCapabilities(registry, now = 1_000L)
        assertEquals(5, registry.listCapabilities().size)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, registry.getCapability(KnownFleet.PENTEST_SWARM.capabilityId)?.state)
        val reverified = registry.reverify(KnownFleet.KYC_AUDITLAB.capabilityId)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, reverified.state)
        assertFailsWith<UnknownCapabilityException> {
            StaticFleetHealthChecker().verify(CapabilityId("fleet.unknown"), "nope")
        }
    }

    @Test
    fun `planned stubs are Pentest EDL and KYC and never succeed`() {
        val tools = KnownFleet.plannedStubs()
        assertEquals(
            listOf("pentest_swarm_status", "edl_commander_status", "kyc_auditlab_status"),
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
    }

    @Test
    fun `listing names every member wiring order and the companion rule`() {
        val text = KnownFleet.formatListing()
        for (id in listOf("rootforge", "victorsuite", "kyc-auditlab", "edl-commander", "pentestswarm")) {
            assertTrue(text.contains(id), id)
        }
        assertTrue(text.contains("None is LIVE"))
        assertTrue(text.contains("Pentest (when repo exists)"))
        assertTrue(text.contains("RootForge Messenger is not that"))
        assertTrue(text.contains("HackerAI"))
    }
}
