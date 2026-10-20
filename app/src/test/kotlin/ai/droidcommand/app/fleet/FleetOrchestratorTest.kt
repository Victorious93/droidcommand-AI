package ai.droidcommand.app.fleet

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.app.approval.ComposeApprovalPrompt
import ai.droidcommand.app.di.AppModule
import ai.droidcommand.rootforge.RootForgeClient
import ai.droidcommand.rootforge.RootForgeNodeConfig
import ai.droidcommand.rootforge.RootForgeNodeRegistry
import ai.droidcommand.security.FleetCommandOutcomes
import ai.droidcommand.security.FleetCommandStatus
import ai.droidcommand.security.FleetCommandTool
import ai.droidcommand.security.FleetControlMode
import ai.droidcommand.security.FleetControlPolicy
import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.FleetNodeBoard
import ai.droidcommand.security.FleetNodeState
import ai.droidcommand.security.FleetOrchestrator
import ai.droidcommand.security.FleetReachability
import ai.droidcommand.security.FleetTransportRouter
import ai.droidcommand.security.KnownFleet
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.termux.NullTermuxExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeLink(
    var installed: Boolean = true,
    var connects: Boolean = true,
    var domainHealthy: Boolean = true,
    var companionService: Boolean = false,
    var pings: Boolean = true,
    var response: String = """{"ok":true,"output":"hi"}""",
) : CompanionLink {
    var executed = 0

    override fun isInstalled(packageName: String) = installed

    override fun connect(packageName: String) = connects

    override fun domainHealthy(packageName: String) = domainHealthy

    override fun exposesCompanionService(packageName: String) = companionService

    override fun ping(packageName: String) = pings

    override fun executeCapability(packageName: String, capabilityId: String, inputJson: String): String {
        executed++
        if (response == "throw") error("binder died")
        return response
    }
}

private val HACKERAI_NODE = FleetNodes.nodes().single { it.id == "hackerai" }

class FleetNodesTest {
    @Test
    fun `the roster is exactly the catalogued members and nothing else`() {
        assertEquals(FleetCatalog.all.map { it.id }, FleetNodes.nodes().map { it.id })
        assertEquals(
            listOf("hackerai", "pentestswarm", "rootforge", "termux", "edl-commander", "kyc-auditlab", "file-manager", "keyboard", "mesh", "browser", "victorsms", "victorspoof"),
            FleetNodes.nodes().map { it.id },
        )
    }

    @Test
    fun `every node's connection class matches KnownFleet`() {
        for (node in FleetNodes.nodes()) {
            val knownId = if (node.id == "termux") "victorsuite" else node.id
            val known = KnownFleet.member(knownId)
            if (known == null) {
                assertEquals("hackerai", node.id)
                assertEquals(FleetIntegrationClass.BOUND_COMPANION, node.integrationClass)
            } else {
                assertEquals(known.integrationClass, node.integrationClass, node.id)
                assertEquals(known.status, node.status, node.id)
            }
        }
    }

    @Test
    fun `the four connection classes are all represented`() {
        assertEquals(FleetIntegrationClass.entries.toSet(), FleetNodes.nodes().map { it.integrationClass }.toSet())
    }

    @Test
    fun `wiring reflects which transports are real`() {
        assertEquals(setOf("hackerai", "pentestswarm"), FleetNodes.WIRING.boundCompanionIds)
        assertTrue(FleetNodes.WIRING.messengerTransportWired)
        assertFalse(FleetNodes.WIRING.termuxRunnerWired)
    }
}

class FleetRowsTest {
    private val control = FleetControlPolicy(FleetNodes.WIRING)
    private val board = FleetNodeBoard(FleetNodes.nodes(), control)

    private fun rows() = FleetRows.build(board.nodes, board.snapshot(), control, emptySet()).associateBy { it.node.id }

    @Test
    fun `before any check nothing is reachable and no send button is enabled`() {
        val rows = rows()
        assertTrue(rows.values.none { it.state.reachability == FleetReachability.REACHABLE })
        assertTrue(rows.values.none { it.canSend })
        assertTrue(rows.values.all { it.state.lastCommand == null })
        assertEquals("No command sent yet", lastCommandLine(null) { "never" })
    }

    @Test
    fun `wired, no-runner and planned nodes say why`() {
        val rows = rows()
        // EXTERNAL_NODE now has a real transport (messengerTransportWired=true), so its control mode is
        // INTERACTIVE — but nothing is paired in this test, so sending is still refused: not by "Commands are
        // disabled" (that's for a class with no transport at all) but because status has never been checked.
        for (id in listOf("rootforge", "file-manager", "keyboard", "mesh", "browser", "victorsms", "victorspoof")) {
            assertEquals(FleetControlMode.INTERACTIVE, rows.getValue(id).control.mode, id)
            assertEquals("external node", rows.getValue(id).control.label, id)
            assertEquals("Node is not known to be reachable. Check status first.", rows.getValue(id).sendDisabledReason, id)
        }
        assertEquals("no runner yet", rows.getValue("termux").control.label)
        assertEquals(FleetControlMode.PLANNED, rows.getValue("kyc-auditlab").control.mode)
        assertEquals(FleetControlMode.PLANNED, rows.getValue("edl-commander").control.mode)
        for (id in listOf("termux", "kyc-auditlab", "edl-commander")) {
            assertTrue(rows.getValue(id).sendDisabledReason!!.startsWith("Commands are disabled"), id)
        }
        assertFalse(rows.getValue("kyc-auditlab").canCheckStatus)
        assertFalse(rows.getValue("edl-commander").canCheckStatus)
        assertTrue(rows.getValue("rootforge").canCheckStatus)
    }

    @Test
    fun `a busy row disables both buttons`() {
        val row = FleetRows.build(board.nodes, board.snapshot(), control, setOf("hackerai")).single { it.node.id == "hackerai" }
        assertFalse(row.canCheckStatus)
        assertFalse(row.canSend)
    }

    @Test
    fun `class labels cover every connection class`() {
        assertEquals(
            setOf("Bound companion (AIDL)", "External node (Messenger)", "Termux IPC", "Host process tool"),
            FleetIntegrationClass.entries.map(::connectionClassLabel).toSet(),
        )
    }
}

class BoundCompanionTransportTest {
    private fun transport(link: FakeLink) = BoundCompanionTransport(link)

    @Test
    fun `not installed is offline`() {
        val r = transport(FakeLink(installed = false)).probe(HACKERAI_NODE)
        assertEquals(FleetReachability.OFFLINE, r.reachability)
        assertTrue(r.detail.contains("not installed"))
    }

    @Test
    fun `installed but not bound is offline`() {
        val r = transport(FakeLink(connects = false)).probe(HACKERAI_NODE)
        assertEquals(FleetReachability.OFFLINE, r.reachability)
    }

    @Test
    fun `a companion that only has its domain AIDL is reachable with no command channel`() {
        val r = transport(FakeLink(companionService = false, domainHealthy = true)).probe(HACKERAI_NODE)
        assertEquals(FleetReachability.REACHABLE, r.reachability)
        assertFalse(r.commandChannel)
        assertTrue(r.detail.contains("does not implement ICompanionService"))
    }

    @Test
    fun `a failing domain health check is offline`() {
        val r = transport(FakeLink(companionService = false, domainHealthy = false)).probe(HACKERAI_NODE)
        assertEquals(FleetReachability.OFFLINE, r.reachability)
    }

    @Test
    fun `ICompanionService ping gives reachable with a command channel`() {
        val r = transport(FakeLink(companionService = true, pings = true)).probe(HACKERAI_NODE)
        assertEquals(FleetReachability.REACHABLE, r.reachability)
        assertTrue(r.commandChannel)
        assertEquals(FleetReachability.OFFLINE, transport(FakeLink(companionService = true, pings = false)).probe(HACKERAI_NODE).reachability)
    }

    @Test
    fun `executeCapability results are mapped honestly`() {
        val ok = transport(FakeLink(companionService = true)).sendCommand(HACKERAI_NODE, "x", "{}")
        assertIs<ToolResult.Success>(ok)
        val refused = transport(FakeLink(companionService = true, response = """{"ok":false,"error":"nope"}""")).sendCommand(HACKERAI_NODE, "x", "{}")
        assertIs<ToolResult.Failure>(refused)
        val garbage = transport(FakeLink(companionService = true, response = "hello")).sendCommand(HACKERAI_NODE, "x", "{}")
        assertIs<ToolResult.Failure>(garbage)
        val thrown = transport(FakeLink(companionService = true, response = "throw")).sendCommand(HACKERAI_NODE, "x", "{}")
        assertIs<ToolResult.Failure>(thrown)
        assertTrue(thrown.reason.contains("binder died"))
    }

    @Test
    fun `no command is sent to a companion without ICompanionService`() {
        val link = FakeLink(companionService = false)
        assertIs<ToolResult.Failure>(transport(link).sendCommand(HACKERAI_NODE, "x", "{}"))
        assertEquals(0, link.executed)
    }
}

class ExternalAndTermuxTransportTest {
    private val rootforge = FleetNodes.nodes().single { it.id == "rootforge" }
    private val fileManager = FleetNodes.nodes().single { it.id == "file-manager" }
    private val termux = FleetNodes.nodes().single { it.id == "termux" }
    private val emptyRegistry = RootForgeNodeRegistry(emptyList()) { RootForgeClient(it, UnwiredMessengerTransport()) }

    @Test
    fun `RootForge with no paired node is pairing required and never reachable`() {
        val r = ExternalNodeTransport(emptyRegistry) { true }.probe(rootforge)
        assertEquals(FleetReachability.UNKNOWN, r.reachability)
        assertTrue(r.detail.startsWith("Pairing required"))
        assertFalse(r.commandChannel)
    }

    @Test
    fun `a paired RootForge node still cannot be reached without the Messenger transport`() {
        val node = RootForgeNodeConfig(
            nodeId = "rf-" + "a".repeat(32),
            host = "192.0.2.1",
            user = "rootforge",
            identityFile = "/nonexistent",
            hostKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIexample",
        )
        val registry = RootForgeNodeRegistry(listOf(node)) { RootForgeClient(it, UnwiredMessengerTransport()) }
        val r = ExternalNodeTransport(registry) { true }.probe(rootforge)
        assertEquals(FleetReachability.OFFLINE, r.reachability)
        assertTrue(r.detail.contains("not wired"))
    }

    @Test
    fun `planned external nodes are a package probe only`() {
        assertEquals(FleetReachability.OFFLINE, ExternalNodeTransport(emptyRegistry) { false }.probe(fileManager).reachability)
        val installed = ExternalNodeTransport(emptyRegistry) { true }.probe(fileManager)
        assertEquals(FleetReachability.UNKNOWN, installed.reachability)
        assertTrue(installed.detail.contains("does not bind"))
    }

    @Test
    fun `external nodes refuse commands`() {
        assertIs<ToolResult.Failure>(ExternalNodeTransport(emptyRegistry) { true }.sendCommand(rootforge, "x", "{}"))
    }

    @Test
    fun `Termux with the Null executor is no runner yet`() {
        val t = TermuxFleetTransport(NullTermuxExecutor()) { true }
        val r = t.probe(termux)
        assertEquals(FleetReachability.UNKNOWN, r.reachability)
        assertTrue(r.detail.startsWith("No runner yet"))
        assertEquals(FleetReachability.OFFLINE, TermuxFleetTransport(NullTermuxExecutor()) { false }.probe(termux).reachability)
        assertIs<ToolResult.Failure>(t.sendCommand(termux, "x", "{}"))
    }
}

/** The wired stack: real FleetOrchestrator, real SecureToolExecutor, the app's own SecurityPolicy, and the real ComposeApprovalPrompt. */
class FleetOrchestratorWiringTest {
    private val control = FleetControlPolicy(FleetNodes.WIRING)
    private val board = FleetNodeBoard(FleetNodes.nodes(), control)
    private val outcomes = FleetCommandOutcomes()
    private val link = FakeLink(companionService = true)
    private val router = FleetTransportRouter(
        mapOf(
            FleetIntegrationClass.BOUND_COMPANION to BoundCompanionTransport(link),
            FleetIntegrationClass.EXTERNAL_NODE to ExternalNodeTransport(RootForgeNodeRegistry(emptyList())) { true },
            FleetIntegrationClass.TERMUX_IPC to TermuxFleetTransport(NullTermuxExecutor()) { true },
        ),
    )
    private val prompt = ComposeApprovalPrompt()
    private val registry = ToolRegistry().apply { register(FleetCommandTool(board, control, router, outcomes)) }
    private val machine = AgentStateMachine()
    private val runner = SecureToolExecutor(
        registry,
        ToolExecutor(registry, machine, sleep = { }),
        machine,
        SecurityPolicyEnforcer(AppModule.provideSecurityPolicy()),
        prompt,
    )
    private val orchestrator = FleetOrchestrator(board, control, router, runner, outcomes)

    private fun awaitPending() {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (prompt.pending.value == null && System.nanoTime() < end) Thread.sleep(5)
    }

    @Test
    fun `check status never opens the approval dialog`() {
        val state = orchestrator.checkStatus("hackerai")
        assertEquals(FleetReachability.REACHABLE, state.reachability)
        assertNull(prompt.pending.value)
        assertEquals(0, link.executed)
    }

    @Test
    fun `an action command waits on the compose approval dialog and sends only after Approve`() {
        orchestrator.checkStatus("hackerai")
        var finished: FleetNodeState? = null
        val t = Thread { finished = orchestrator.sendCommand("hackerai", "ai.companion.hackerai.skill_catalog", "{}") }.apply {
            isDaemon = true
            start()
        }
        awaitPending()
        val pending = assertNotNull(prompt.pending.value)
        assertTrue(pending.reason.contains("fleet_send_command"))
        assertTrue(pending.reason.contains("ai.companion.hackerai.skill_catalog"))
        assertEquals(0, link.executed, "nothing is sent while the dialog is open")
        prompt.resolve(true)
        t.join(5000)
        assertEquals(1, link.executed)
        assertEquals(FleetCommandStatus.SUCCEEDED, finished!!.lastCommand!!.status)
    }

    @Test
    fun `Deny sends nothing and is recorded as denied`() {
        orchestrator.checkStatus("pentestswarm")
        var finished: FleetNodeState? = null
        val t = Thread { finished = orchestrator.sendCommand("pentestswarm", "ai.companion.pentestswarm.run_chain", "{}") }.apply {
            isDaemon = true
            start()
        }
        awaitPending()
        prompt.resolve(false)
        t.join(5000)
        assertEquals(0, link.executed)
        assertEquals(FleetCommandStatus.DENIED, finished!!.lastCommand!!.status)
    }

    @Test
    fun `read-only nodes refuse without ever opening the dialog`() {
        for (id in listOf("rootforge", "termux", "kyc-auditlab", "edl-commander", "file-manager", "keyboard", "mesh", "browser", "victorsms", "victorspoof")) {
            orchestrator.checkStatus(id)
            val state = orchestrator.sendCommand(id, "x", "{}")
            assertEquals(FleetCommandStatus.REFUSED, state.lastCommand!!.status, id)
        }
        assertNull(prompt.pending.value)
        assertEquals(0, link.executed)
    }

    @Test
    fun `the app policy does not auto-approve sensitive tools`() {
        assertFalse(SecurityLevel.SENSITIVE in AppModule.provideSecurityPolicy().autoApprove)
    }
}
