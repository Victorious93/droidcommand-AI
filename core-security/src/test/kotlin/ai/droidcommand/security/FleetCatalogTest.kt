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
    fun `the room fleet is exactly the five named members`() {
        assertEquals(
            listOf("rootforge", "victorsuite", "kyc-auditlab", "edl-commander", "pentestswarm"),
            KnownFleet.ALL.map { it.id },
        )
    }

    @Test
    fun `none of the five is LIVE and the static checker never reports AVAILABLE`() {
        val checker = StaticFleetHealthChecker()
        for (member in KnownFleet.ALL) {
            assertNotEquals(FleetHonestyStatus.LIVE, member.status, member.id)
            val meta = checker.verify(member.capabilityId, member.providerId)
            assertNotEquals(CapabilityState.AVAILABLE, meta.state, member.id)
            assertEquals(member.capabilityId, meta.id)
            assertEquals(member.providerId, meta.providerId)
            assertEquals(member.riskTier, meta.riskTier)
            assertEquals(member.refusalMessage, meta.lastError)
        }
    }

    @Test
    fun `integration class matches the existing protocol and forbids AIDL on non-companions`() {
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, KnownFleet.ROOTFORGE.integrationClass)
        assertEquals(FleetIntegrationClass.TERMUX_IPC, KnownFleet.VICTORSUITE.integrationClass)
        assertEquals(FleetIntegrationClass.HOST_PROCESS_TOOL, KnownFleet.KYC_AUDITLAB.integrationClass)
        assertEquals(FleetIntegrationClass.HOST_PROCESS_TOOL, KnownFleet.EDL_COMMANDER.integrationClass)
        assertEquals(FleetIntegrationClass.BOUND_COMPANION, KnownFleet.PENTEST_SWARM.integrationClass)

        val nonCompanions = KnownFleet.ALL.filter { it.integrationClass != FleetIntegrationClass.BOUND_COMPANION }
        assertEquals(4, nonCompanions.size)
        for (member in nonCompanions) {
            assertFalse(member.usesAidl, member.id)
            assertFalse(member.usesDependencyGuard, member.id)
        }
        assertTrue(KnownFleet.PENTEST_SWARM.usesAidl)
        assertTrue(KnownFleet.PENTEST_SWARM.usesDependencyGuard)
    }

    @Test
    fun `descriptor construction rejects AIDL or DependencyGuard on a host or node member`() {
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.KYC_AUDITLAB.copy(usesAidl = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.ROOTFORGE.copy(usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.EDL_COMMANDER.copy(usesAidl = true, usesDependencyGuard = true)
        }
        assertFailsWith<IllegalArgumentException> {
            KnownFleet.VICTORSUITE.copy(usesAidl = true)
        }
    }

    @Test
    fun `capability states stay honest per class`() {
        val checker = StaticFleetHealthChecker()
        assertEquals(CapabilityState.REQUIRES_CONFIGURATION, checker.verify(KnownFleet.ROOTFORGE.capabilityId, "rootforge").state)
        assertEquals(CapabilityState.REQUIRES_TERMUX, checker.verify(KnownFleet.VICTORSUITE.capabilityId, "termux").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.KYC_AUDITLAB.capabilityId, "kyc-auditlab").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.EDL_COMMANDER.capabilityId, "edl-commander").state)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, checker.verify(KnownFleet.PENTEST_SWARM.capabilityId, "pentestswarm").state)
    }

    @Test
    fun `registerCapabilities fills the existing CapabilityRegistry`() {
        val registry = InMemoryCapabilityRegistry(StaticFleetHealthChecker())
        KnownFleet.registerCapabilities(registry, now = 1_000L)
        assertEquals(5, registry.listCapabilities().size)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, registry.getCapability(KnownFleet.KYC_AUDITLAB.capabilityId)?.state)
        val reverified = registry.reverify(KnownFleet.EDL_COMMANDER.capabilityId)
        assertEquals(CapabilityState.REQUIRES_EXTERNAL_SERVICE, reverified.state)
        assertFailsWith<UnknownCapabilityException> {
            StaticFleetHealthChecker().verify(CapabilityId("fleet.unknown"), "nope")
        }
    }

    @Test
    fun `planned host tools are the two missing process slots and never succeed`() {
        val tools = KnownFleet.plannedHostTools()
        assertEquals(listOf("kyc_auditlab_status", "edl_commander_status"), tools.map { it.spec.name })
        for (tool in tools) {
            val result = tool.execute(emptyMap())
            assertTrue(result is ToolResult.Failure, tool.spec.name)
            assertFalse((result as ToolResult.Failure).reason.contains("success", ignoreCase = true))
        }
        assertTrue((tools[1].execute(emptyMap()) as ToolResult.Failure).reason.contains("does not flash"))
    }

    @Test
    fun `planned stub refuses to wrap a live or companion member`() {
        assertFailsWith<IllegalArgumentException> {
            PlannedHostProcessTool(KnownFleet.PENTEST_SWARM)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedHostProcessTool(KnownFleet.ROOTFORGE)
        }
        assertFailsWith<IllegalArgumentException> {
            PlannedHostProcessTool(KnownFleet.KYC_AUDITLAB.copy(status = FleetHonestyStatus.LIVE))
        }
    }

    @Test
    fun `VictorSuite stays on the existing Termux tool and package`() {
        assertEquals("com.termux", KnownFleet.VICTORSUITE.packageOrBinary)
        assertEquals(listOf("run_termux_command"), KnownFleet.VICTORSUITE.existingToolNames)
        assertNull(KnownFleet.KYC_AUDITLAB.repo)
        assertNull(KnownFleet.EDL_COMMANDER.repo)
    }

    @Test
    fun `listing names every member and the companion-protocol rule`() {
        val text = KnownFleet.formatListing()
        for (id in listOf("rootforge", "victorsuite", "kyc-auditlab", "edl-commander", "pentestswarm")) {
            assertTrue(text.contains(id), id)
        }
        assertTrue(text.contains("None is LIVE"))
        assertTrue(text.contains("AIDL + DependencyGuard apply only to BOUND_COMPANION"))
        assertTrue(text.contains("HackerAI"))
    }
}
