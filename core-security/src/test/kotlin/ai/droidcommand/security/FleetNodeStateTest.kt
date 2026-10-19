package ai.droidcommand.security

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTransport(
    var probeResult: FleetProbeResult = FleetProbeResult(FleetReachability.REACHABLE, "pong", commandChannel = true),
    var sendResult: ToolResult = ToolResult.Success("done"),
) : FleetNodeTransport {
    var probes = 0
    var sends = 0
    var probeThrows: Exception? = null

    override fun probe(node: FleetNode): FleetProbeResult {
        probes++
        probeThrows?.let { throw it }
        return probeResult
    }

    override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult {
        sends++
        return sendResult
    }
}

private class CountingPrompt(var answer: Boolean) : ApprovalPrompt {
    var asked = 0
    var lastInput: Map<String, String> = emptyMap()

    override fun requestApproval(reason: String): Boolean {
        asked++
        return answer
    }

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
        lastInput = input
        return requestApproval(reason)
    }
}

private fun FleetNodeState.last(): FleetCommandRecord = checkNotNull(lastCommand) { "no command recorded for $nodeId" }

private val HACKERAI = FleetNode("hackerai", "HackerAI", FleetIntegrationClass.BOUND_COMPANION, FleetHonestyStatus.PARTIAL, "ai.hackerai.companion")
private val EDL = FleetNode.from(KnownFleet.EDL_COMMANDER, packageName = "ai.edlcommander.companion")
private val ROOTFORGE = FleetNode.from(KnownFleet.ROOTFORGE)
private val FILE_MANAGER = FleetNode.from(KnownFleet.FILE_MANAGER, packageName = "ai.victorious.filemanager")
private val VICTORSUITE = FleetNode.from(KnownFleet.VICTORSUITE, packageName = "com.termux")
private val KYC = FleetNode.from(KnownFleet.KYC_AUDITLAB)
private val ALL_NODES = listOf(HACKERAI, EDL, ROOTFORGE, FILE_MANAGER, VICTORSUITE, KYC)
private val WIRING = FleetWiring(boundCompanionIds = setOf("hackerai"))

private class Harness(
    val transport: FleetNodeTransport = FakeTransport(),
    val prompt: CountingPrompt = CountingPrompt(answer = true),
    nodes: List<FleetNode> = ALL_NODES,
    wiring: FleetWiring = WIRING,
) {
    val fake: FakeTransport get() = transport as FakeTransport
    var now = 1_000L
    val control = FleetControlPolicy(wiring)
    val board = FleetNodeBoard(nodes, control)
    val outcomes = FleetCommandOutcomes()
    val registry = ToolRegistry().apply { register(FleetCommandTool(board, control, transport, outcomes)) }
    val stateMachine = AgentStateMachine()
    val runner = SecureToolExecutor(
        registry,
        ToolExecutor(registry, stateMachine, sleep = { }),
        stateMachine,
        SecurityPolicyEnforcer(SecurityPolicy(grantedCategories = setOf(PermissionCategory.NETWORK))),
        prompt,
    )
    val orchestrator = FleetOrchestrator(board, control, transport, runner, outcomes, nowMs = { now }, newRequestId = { "req-${now++}" })
}

class FleetNodeStateTest {
    @Test
    fun `every node starts unknown with no command history and nothing is pre-filled`() {
        val h = Harness()
        for (state in h.board.snapshot()) {
            assertEquals(FleetReachability.UNKNOWN, state.reachability, state.nodeId)
            assertNull(state.lastCommand, state.nodeId)
            assertNull(state.checkedAtMs, state.nodeId)
            assertFalse(state.commandChannel, state.nodeId)
        }
        assertEquals(FleetNodeState.NOT_CHECKED, h.board.state("hackerai")!!.detail)
        assertEquals("planned: companion bind is not started in this build", h.board.state("edl-commander")!!.detail)
        assertEquals("planned: stub only", h.board.state("kyc-auditlab")!!.detail)
    }

    @Test
    fun `real catalog nodes are never reachable before a probe`() {
        val nodes = KnownFleet.ALL.map { FleetNode.from(it) }
        val board = FleetNodeBoard(nodes)
        assertTrue(board.snapshot().none { it.reachability == FleetReachability.REACHABLE })
        assertEquals(KnownFleet.ALL.map { it.id }, board.nodes.map { it.id })
    }

    @Test
    fun `control policy follows the integration class and the wiring that exists`() {
        val policy = FleetControlPolicy(WIRING)
        assertEquals(FleetControlMode.INTERACTIVE, policy.controlFor(HACKERAI).mode)
        assertEquals(FleetControlMode.PLANNED, policy.controlFor(EDL).mode)
        assertFalse(policy.controlFor(EDL).canCheckStatus)
        assertEquals(FleetControlMode.PAIRING_REQUIRED, policy.controlFor(ROOTFORGE).mode)
        assertEquals("pairing required", policy.controlFor(ROOTFORGE).label)
        assertTrue(policy.controlFor(ROOTFORGE).canCheckStatus)
        assertEquals(FleetControlMode.PAIRING_REQUIRED, policy.controlFor(FILE_MANAGER).mode)
        assertTrue(policy.controlFor(FILE_MANAGER).label.startsWith("pairing required"))
        assertEquals(FleetControlMode.NO_RUNNER_YET, policy.controlFor(VICTORSUITE).mode)
        assertEquals("no runner yet", policy.controlFor(VICTORSUITE).label)
        assertEquals(FleetControlMode.PLANNED, policy.controlFor(KYC).mode)
        assertFalse(policy.controlFor(KYC).canCheckStatus)
    }

    @Test
    fun `wiring a transport is what makes a class interactive`() {
        val wired = FleetControlPolicy(FleetWiring(boundCompanionIds = setOf("edl-commander"), messengerTransportWired = true, termuxRunnerWired = true))
        assertEquals(FleetControlMode.INTERACTIVE, wired.controlFor(EDL).mode)
        assertEquals(FleetControlMode.INTERACTIVE, wired.controlFor(ROOTFORGE).mode)
        assertEquals(FleetControlMode.INTERACTIVE, wired.controlFor(VICTORSUITE).mode)
        assertEquals(FleetControlMode.PLANNED, wired.controlFor(KYC).mode)
    }

    @Test
    fun `check status needs no approval and records the probe and a timestamped status record`() {
        val h = Harness()
        val state = h.orchestrator.checkStatus("hackerai")

        assertEquals(0, h.prompt.asked)
        assertEquals(0, h.fake.sends)
        assertEquals(1, h.fake.probes)
        assertEquals(FleetReachability.REACHABLE, state.reachability)
        assertTrue(state.commandChannel)
        assertEquals(1_000L, state.checkedAtMs)
        val last = assertNotNull(state.lastCommand)
        assertEquals(FleetCommandTier.READ_ONLY_STATUS, last.tier)
        assertEquals("check status", last.command)
        assertEquals(FleetCommandStatus.SUCCEEDED, last.status)
        assertEquals("pong", last.result)
        assertEquals(1_000L, last.atMs)
    }

    @Test
    fun `an offline probe is a successful check that reports offline`() {
        val h = Harness(FakeTransport(probeResult = FleetProbeResult(FleetReachability.OFFLINE, "package not installed")))
        val state = h.orchestrator.checkStatus("hackerai")
        assertEquals(FleetReachability.OFFLINE, state.reachability)
        assertEquals(FleetCommandStatus.SUCCEEDED, state.last().status)
        assertEquals("package not installed", state.last().result)
    }

    @Test
    fun `a probe that throws leaves the node unknown and records the failure`() {
        val h = Harness()
        h.fake.probeThrows = IllegalStateException("binder died")
        val state = h.orchestrator.checkStatus("hackerai")
        assertEquals(FleetReachability.UNKNOWN, state.reachability)
        assertEquals(FleetCommandStatus.FAILED, state.last().status)
        assertTrue(state.last().result.contains("binder died"))
    }

    @Test
    fun `nodes without a status check are not probed`() {
        val h = Harness()
        h.orchestrator.checkStatus("kyc-auditlab")
        h.orchestrator.checkStatus("edl-commander")
        assertEquals(0, h.fake.probes)
        assertNull(h.board.state("kyc-auditlab")!!.lastCommand)
        assertEquals(FleetReachability.UNKNOWN, h.board.state("edl-commander")!!.reachability)
    }

    @Test
    fun `check all probes only the nodes that can be checked`() {
        val h = Harness()
        h.orchestrator.checkAll()
        assertEquals(4, h.fake.probes) // hackerai, rootforge, file-manager, victorsuite
        assertEquals(0, h.prompt.asked)
    }

    @Test
    fun `an action command always asks for approval and runs only when approved`() {
        val h = Harness()
        h.orchestrator.checkStatus("hackerai")
        val state = h.orchestrator.sendCommand("hackerai", "ai.companion.hackerai.skill_catalog", "{}")

        assertEquals(1, h.prompt.asked)
        assertEquals("hackerai", h.prompt.lastInput["nodeId"])
        assertEquals("ai.companion.hackerai.skill_catalog", h.prompt.lastInput["capabilityId"])
        assertEquals(1, h.fake.sends)
        val last = assertNotNull(state.lastCommand)
        assertEquals(FleetCommandTier.ACTION, last.tier)
        assertEquals(FleetCommandStatus.SUCCEEDED, last.status)
        assertEquals("done", last.result)
        assertTrue(last.command.startsWith("ai.companion.hackerai.skill_catalog"))
    }

    @Test
    fun `a denied approval never reaches the transport`() {
        val h = Harness(prompt = CountingPrompt(answer = false))
        h.orchestrator.checkStatus("hackerai")
        val state = h.orchestrator.sendCommand("hackerai", "ai.companion.hackerai.agent_task", """{"goal":"x"}""")

        assertEquals(1, h.prompt.asked)
        assertEquals(0, h.fake.sends)
        assertEquals(FleetCommandStatus.DENIED, state.last().status)
        assertTrue(state.last().result.contains("denied", ignoreCase = true), state.last().result)
    }

    @Test
    fun `an approved command that fails is recorded as failed with the transport's reason`() {
        val h = Harness(FakeTransport(sendResult = ToolResult.Failure("binder error")))
        h.orchestrator.checkStatus("hackerai")
        val state = h.orchestrator.sendCommand("hackerai", "ai.companion.hackerai.skill_catalog", "{}")
        assertEquals(FleetCommandStatus.FAILED, state.last().status)
        assertTrue(state.last().result.contains("binder error"))
        assertEquals(1, h.prompt.asked)
    }

    @Test
    fun `a transport that throws on an approved command is recorded as failed`() {
        val throwing = object : FleetNodeTransport {
            override fun probe(node: FleetNode) = FleetProbeResult(FleetReachability.REACHABLE, "ok", commandChannel = true)

            override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult =
                throw IllegalStateException("kaboom")
        }
        val h = Harness(throwing)
        h.orchestrator.checkStatus("hackerai")
        val state = h.orchestrator.sendCommand("hackerai", "x", "{}")
        assertEquals(FleetCommandStatus.FAILED, state.last().status)
        assertTrue(state.last().result.contains("kaboom"))
        assertEquals(1, h.prompt.asked)
    }

    @Test
    fun `read-only nodes refuse before any approval prompt and never reach the transport`() {
        val h = Harness(FakeTransport(probeResult = FleetProbeResult(FleetReachability.REACHABLE, "claims ok", commandChannel = true)))
        for (id in listOf("rootforge", "file-manager", "victorsuite", "edl-commander", "kyc-auditlab")) {
            h.orchestrator.checkStatus(id)
            val state = h.orchestrator.sendCommand(id, "anything", "{}")
            assertEquals(FleetCommandStatus.REFUSED, state.last().status, id)
            assertEquals(FleetCommandTier.ACTION, state.last().tier, id)
            assertTrue(state.last().result.startsWith("Commands are disabled"), id)
        }
        assertEquals(0, h.prompt.asked)
        assertEquals(0, h.fake.sends)
    }

    @Test
    fun `a command is refused until the node was checked and reachable with a command channel`() {
        val h = Harness()
        val unchecked = h.orchestrator.sendCommand("hackerai", "x", "{}")
        assertEquals(FleetCommandStatus.REFUSED, unchecked.last().status)
        assertTrue(unchecked.last().result.contains("Check status first"))

        h.fake.probeResult = FleetProbeResult(FleetReachability.REACHABLE, "pong, but no ICompanionService")
        h.orchestrator.checkStatus("hackerai")
        val noChannel = h.orchestrator.sendCommand("hackerai", "x", "{}")
        assertEquals(FleetCommandStatus.REFUSED, noChannel.last().status)
        assertTrue(noChannel.last().result.contains("no command channel"))

        h.fake.probeResult = FleetProbeResult(FleetReachability.OFFLINE, "gone")
        h.orchestrator.checkStatus("hackerai")
        assertEquals(FleetCommandStatus.REFUSED, h.orchestrator.sendCommand("hackerai", "x", "{}").last().status)

        assertEquals(0, h.prompt.asked)
        assertEquals(0, h.fake.sends)
    }

    @Test
    fun `the tool itself refuses a read-only node even when called through the approval gate directly`() {
        val h = Harness()
        val result = h.runner.run(
            FleetCommandTool.NAME,
            mapOf("nodeId" to "rootforge", "capabilityId" to "x", "requestId" to "direct"),
        )
        assertEquals(1, h.prompt.asked) // the gate still asked first
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, h.fake.sends)
    }

    @Test
    fun `the command tool is sensitive, confirmed, and hidden from both planners`() {
        val h = Harness()
        val spec = h.registry.get(FleetCommandTool.NAME).spec
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertTrue(spec.requiresConfirmation)
        assertTrue(spec.allowedModes.isEmpty())
        assertTrue(h.registry.list(AgentMode.PILOT).none { it.name == FleetCommandTool.NAME })
        assertTrue(h.registry.list(AgentMode.FORGE).none { it.name == FleetCommandTool.NAME })
    }

    @Test
    fun `unknown node ids are rejected`() {
        val h = Harness()
        assertFailsWith<IllegalArgumentException> { h.orchestrator.checkStatus("nope") }
        assertFailsWith<IllegalArgumentException> { h.orchestrator.sendCommand("nope", "x", "{}") }
    }

    @Test
    fun `a class with no transport reports unknown and refuses commands`() {
        val router = FleetTransportRouter(emptyMap())
        assertEquals(FleetReachability.UNKNOWN, router.probe(HACKERAI).reachability)
        assertIs<ToolResult.Failure>(router.sendCommand(HACKERAI, "x", "{}"))
        val routed = FleetTransportRouter(mapOf(FleetIntegrationClass.BOUND_COMPANION to FakeTransport()))
        assertEquals(FleetReachability.REACHABLE, routed.probe(HACKERAI).reachability)
        assertEquals(FleetReachability.UNKNOWN, routed.probe(ROOTFORGE).reachability)
    }

    @Test
    fun `a command channel cannot exist on a node that is not reachable`() {
        assertFailsWith<IllegalArgumentException> {
            FleetProbeResult(FleetReachability.OFFLINE, "x", commandChannel = true)
        }
    }

    @Test
    fun `the board keeps node order and rejects duplicates`() {
        val board = FleetNodeBoard(ALL_NODES)
        assertEquals(ALL_NODES.map { it.id }, board.snapshot().map { it.nodeId })
        assertFailsWith<IllegalArgumentException> { FleetNodeBoard(listOf(HACKERAI, HACKERAI)) }
    }
}
