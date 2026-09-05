package ai.droidcommand.security

import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CountingTool(override val spec: ToolSpec) : Tool {
    var invocations = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}

private fun newHarness(
    spec: ToolSpec,
    policy: SecurityPolicy,
    approvalPrompt: ApprovalPrompt = ApprovalPrompt { true },
): Quad {
    val tool = CountingTool(spec)
    val registry = ToolRegistry().apply { register(tool) }
    val stateMachine = AgentStateMachine()
    val delegate = ToolExecutor(registry, stateMachine, sleep = { })
    val secure = SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    return Quad(secure, tool, stateMachine, registry)
}

private data class Quad(
    val executor: SecureToolExecutor,
    val tool: CountingTool,
    val stateMachine: AgentStateMachine,
    val registry: ToolRegistry,
)

class SecureToolExecutorTest {
    @Test
    fun `denies a root tool when root is unavailable and never invokes it`() {
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(rootEnabled = true, rootAvailable = { false }))

        val result = secure.run("rm", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `runs a root tool when root is available and approval is granted`() {
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val (secure, tool, _, _) = newHarness(
            spec,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }),
            approvalPrompt = ApprovalPrompt { true },
        )

        val result = secure.run("rm", emptyMap())

        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `never invokes the tool when the user denies authorization`() {
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(), approvalPrompt = ApprovalPrompt { false })

        val result = secure.run("delete-app", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertTrue((result as ToolResult.Failure).reason.contains("denied"))
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `denies and never invokes when a required permission is missing`() {
        val spec = ToolSpec(name = "record", description = "d", requiredPermissions = setOf("MICROPHONE"))
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(grantedPermissions = emptySet()))

        val result = secure.run("record", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `transitions to AwaitingApproval before prompting, and to Observing once resolved`() {
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val registry = ToolRegistry().apply { register(CountingTool(spec)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        var stateAtPromptTime: AgentState? = null
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy()),
            approvalPrompt = ApprovalPrompt { reason ->
                stateAtPromptTime = stateMachine.state
                true
            },
        )

        secure.run("delete-app", emptyMap())

        assertIs<AgentState.AwaitingApproval>(stateAtPromptTime)
        assertIs<AgentState.Observing>(stateMachine.state)
    }

    @Test
    fun `auto-approved security levels never invoke the approval prompt`() {
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        var promptCalls = 0
        val (secure, tool, _, _) = newHarness(
            spec,
            SecurityPolicy(autoApprove = setOf(SecurityLevel.NORMAL, SecurityLevel.SENSITIVE)),
            approvalPrompt = ApprovalPrompt { promptCalls++; true },
        )

        val result = secure.run("delete-app", emptyMap())

        assertIs<ToolResult.Success>(result)
        assertEquals(0, promptCalls)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `a grant-gated tool is denied without a configured grant store, and never invoked`() {
        val spec = ToolSpec(
            name = "rm",
            description = "d",
            securityLevel = SecurityLevel.SENSITIVE,
            grantCapability = "root",
        )
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(autoApprove = setOf(SecurityLevel.SENSITIVE)))

        val result = secure.run("rm", emptyMap(), grantId = "g1")

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("no grant store"))
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a grant-gated tool is denied without a live grant id, and never invoked`() {
        val spec = ToolSpec(
            name = "rm",
            description = "d",
            securityLevel = SecurityLevel.SENSITIVE,
            grantCapability = "root",
        )
        val tool = CountingTool(spec)
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val grantStore = InMemoryGrantStore()
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy(autoApprove = setOf(SecurityLevel.SENSITIVE))),
            ApprovalPrompt { true },
            grantStore = grantStore,
        )

        val result = secure.run("rm", emptyMap(), grantId = null)

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a live single-use grant permits exactly one invocation, then is consumed`() {
        val spec = ToolSpec(
            name = "rm",
            description = "d",
            securityLevel = SecurityLevel.SENSITIVE,
            grantCapability = "root",
        )
        val tool = CountingTool(spec)
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val grantStore = InMemoryGrantStore().apply { issue(Grant(id = "g1", capability = "root")) }
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy(autoApprove = setOf(SecurityLevel.SENSITIVE))),
            ApprovalPrompt { true },
            grantStore = grantStore,
        )

        val first = secure.run("rm", emptyMap(), grantId = "g1")
        assertIs<ToolResult.Success>(first)
        assertEquals(1, tool.invocations)

        val second = secure.run("rm", emptyMap(), grantId = "g1")
        assertIs<ToolResult.Failure>(second)
        assertEquals(1, tool.invocations) // never invoked a second time — grant was already spent
    }

    @Test
    fun `a failed invocation does not consume a single-use grant`() {
        val spec = ToolSpec(
            name = "always-fails",
            description = "d",
            securityLevel = SecurityLevel.SENSITIVE,
            grantCapability = "root",
        )
        val registry = ToolRegistry().apply {
            register(
                object : Tool {
                    override val spec = spec
                    override fun execute(input: Map<String, String>) = ToolResult.Failure("nope")
                },
            )
        }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val grantStore = InMemoryGrantStore().apply { issue(Grant(id = "g1", capability = "root")) }
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy(autoApprove = setOf(SecurityLevel.SENSITIVE))),
            ApprovalPrompt { true },
            grantStore = grantStore,
        )

        secure.run("always-fails", emptyMap(), grantId = "g1")
        // The grant must still be live — a failed attempt never spends a single-use grant.
        assertIs<GrantCheck.Live>(grantStore.check("g1", "root"))
    }

    @Test
    fun `a sensitive tool is denied unrun when the audit log is at capacity`() {
        val spec = ToolSpec(name = "rm", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val tool = CountingTool(spec)
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val auditLog = InMemoryAuditLog(capacity = 0)
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy(autoApprove = setOf(SecurityLevel.SENSITIVE))),
            ApprovalPrompt { true },
            auditLog = auditLog,
        )

        val result = secure.run("rm", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("Audit log is at capacity"))
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a NORMAL tool still runs even when the audit log is at capacity`() {
        val spec = ToolSpec(name = "echo", description = "d") // SecurityLevel.NORMAL
        val registry = ToolRegistry().apply { register(CountingTool(spec)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy()),
            ApprovalPrompt { true },
            auditLog = InMemoryAuditLog(capacity = 0),
        )

        val result = secure.run("echo", emptyMap())
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `denies an initiator-restricted tool invoked by a disallowed initiator, without ever calling it`() {
        val spec = ToolSpec(
            name = "wipe-device",
            description = "d",
            securityLevel = SecurityLevel.ROOT,
            requiresRoot = true,
            requiredInitiator = setOf(Initiator.DEVICE_OWNER),
        )
        val (secure, tool, _, _) = newHarness(
            spec,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }, autoApprove = setOf(SecurityLevel.ROOT)),
        )

        val result = secure.run("wipe-device", emptyMap(), initiator = Initiator.AI)

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("requires initiator"))
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `an initiator check is enforced before any grant check, and never invokes the tool`() {
        val spec = ToolSpec(
            name = "wipe-device",
            description = "d",
            securityLevel = SecurityLevel.SENSITIVE,
            grantCapability = "root",
            requiredInitiator = setOf(Initiator.DEVICE_OWNER),
        )
        val tool = CountingTool(spec)
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        // No grant store configured at all — if the initiator check didn't run first, this
        // would fail for "no grant store configured" instead of the initiator mismatch.
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy(autoApprove = setOf(SecurityLevel.SENSITIVE))),
            ApprovalPrompt { true },
        )

        val result = secure.run("wipe-device", emptyMap(), initiator = Initiator.AI)

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("requires initiator"))
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `permits an initiator-restricted tool invoked by an allowed initiator`() {
        val spec = ToolSpec(
            name = "wipe-device",
            description = "d",
            securityLevel = SecurityLevel.ROOT,
            requiresRoot = true,
            requiredInitiator = setOf(Initiator.DEVICE_OWNER),
        )
        val (secure, tool, _, _) = newHarness(
            spec,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }, autoApprove = setOf(SecurityLevel.ROOT)),
        )

        val result = secure.run("wipe-device", emptyMap(), initiator = Initiator.DEVICE_OWNER)

        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `unrestricted default (AI) still runs a tool with no requiredInitiator`() {
        val spec = ToolSpec(name = "echo2", description = "d")
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy())

        val result = secure.run("echo2", emptyMap())

        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }
}
