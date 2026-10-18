package ai.droidcommand.security

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
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

private class WiringCountingTool(override val spec: ToolSpec) : Tool {
    var invocations = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}

private class WiringHarness(spec: ToolSpec, policy: SecurityPolicy, answer: Boolean = false) {
    val tool = WiringCountingTool(spec)
    val audit = InMemoryAuditLog()
    var promptCalls = 0
        private set

    private val registry = ToolRegistry().apply { register(tool) }
    private val stateMachine = AgentStateMachine()
    private val executor = SecureToolExecutor(
        registry,
        ToolExecutor(registry, stateMachine, sleep = { }),
        stateMachine,
        SecurityPolicyEnforcer(policy),
        ApprovalPrompt {
            promptCalls++
            answer
        },
        auditLog = audit,
    )

    fun run(initiator: Initiator? = null, grantId: String? = null): ToolResult =
        executor.run(tool.spec.name, emptyMap(), initiator = initiator, grantId = grantId)
}

private val readOnlyDeclared = AutoApprovalPolicy(ActionRiskClassifier(mapOf("battery" to ActionRisk.READ_ONLY)))

private fun sensitiveView(name: String) = ToolSpec(
    name = name,
    description = "d",
    securityLevel = SecurityLevel.SENSITIVE,
    permissionCategory = PermissionCategory.VIEW,
)

class RiskAutoApprovalWiringTest {
    @Test
    fun `default policy has no auto-approval so behavior is unchanged`() {
        assertEquals(null, SecurityPolicy().autoApprovalPolicy)
        val h = WiringHarness(sensitiveView("battery"), SecurityPolicy(grantedCategories = setOf(PermissionCategory.VIEW)))
        assertIs<ToolResult.Failure>(h.run())
        assertEquals(1, h.promptCalls)
        assertEquals(0, h.tool.invocations)
    }

    @Test
    fun `a declared READ_ONLY SENSITIVE tool runs without a prompt and the audit trail says why`() {
        val h = WiringHarness(
            sensitiveView("battery"),
            SecurityPolicy(grantedCategories = setOf(PermissionCategory.VIEW), autoApprovalPolicy = readOnlyDeclared),
        )
        assertIs<ToolResult.Success>(h.run())
        assertEquals(0, h.promptCalls)
        assertEquals(1, h.tool.invocations)
        val granted = h.audit.all().single { it.type == AuditEventType.ACCESS_GRANTED }
        assertTrue(granted.detail.contains("auto-approved READ_ONLY"), granted.detail)
    }

    @Test
    fun `an undeclared tool still prompts, and a denial still blocks it`() {
        val h = WiringHarness(
            sensitiveView("unlisted"),
            SecurityPolicy(grantedCategories = setOf(PermissionCategory.VIEW), autoApprovalPolicy = readOnlyDeclared),
            answer = false,
        )
        assertIs<ToolResult.Failure>(h.run())
        assertEquals(1, h.promptCalls)
        assertEquals(0, h.tool.invocations)
    }

    @Test
    fun `a declared READ_ONLY name on a hard-gated spec still prompts`() {
        val gated = listOf(
            sensitiveView("battery").copy(requiresRoot = true, securityLevel = SecurityLevel.ROOT),
            sensitiveView("battery").copy(permissionCategory = PermissionCategory.TERMINAL),
            sensitiveView("battery").copy(requiredInitiator = setOf(Initiator.AI)),
            sensitiveView("battery").copy(allowedModes = emptySet()),
        )
        gated.forEach { spec ->
            val h = WiringHarness(
                spec,
                SecurityPolicy(
                    rootEnabled = true,
                    rootAvailable = { true },
                    grantedCategories = setOf(PermissionCategory.VIEW, PermissionCategory.TERMINAL),
                    autoApprovalPolicy = readOnlyDeclared,
                ),
                answer = false,
            )
            h.run(initiator = Initiator.AI)
            assertEquals(1, h.promptCalls, "spec $spec must still prompt")
            assertEquals(0, h.tool.invocations)
        }
    }

    @Test
    fun `auto-approval never overrides a hard deny from the permission-category gate`() {
        val h = WiringHarness(
            sensitiveView("battery"),
            SecurityPolicy(grantedCategories = emptySet(), autoApprovalPolicy = readOnlyDeclared),
        )
        val result = assertIs<ToolResult.Failure>(h.run())
        assertTrue(result.reason.contains("VIEW"), result.reason)
        assertEquals(0, h.promptCalls)
        assertEquals(0, h.tool.invocations)
    }

    @Test
    fun `an enforcer returns AutoApproved only for the confirmation step`() {
        val enforcer = SecurityPolicyEnforcer(
            SecurityPolicy(grantedCategories = setOf(PermissionCategory.VIEW), autoApprovalPolicy = readOnlyDeclared),
        )
        assertIs<PolicyDecision.AutoApproved>(enforcer.authorize(sensitiveView("battery")))
        assertIs<PolicyDecision.RequireApproval>(enforcer.authorize(sensitiveView("other")))
        assertEquals(PolicyDecision.Allow, enforcer.authorize(sensitiveView("battery").copy(securityLevel = SecurityLevel.NORMAL, requiresConfirmation = false)))
    }
}
