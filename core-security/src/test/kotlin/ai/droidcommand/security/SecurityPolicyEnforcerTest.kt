package ai.droidcommand.security

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecurityPolicyEnforcerTest {
    @Test
    fun `allows a NORMAL tool with no requirements by default`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy())
        val decision = enforcer.authorize(ToolSpec(name = "echo", description = "d"))
        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun `denies a root tool when root is disabled`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false))
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val decision = assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
        assertTrue(decision.reason.contains("disabled"))
    }

    @Test
    fun `denies a root tool when root is enabled but unavailable on the device`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = true, rootAvailable = { false }))
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val decision = assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
        assertTrue(decision.reason.contains("unavailable"))
    }

    @Test
    fun `requires approval for a root tool when root is enabled and available`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = true, rootAvailable = { true }))
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        assertIs<PolicyDecision.RequireApproval>(enforcer.authorize(spec))
    }

    @Test
    fun `denies when a required permission is not granted`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedPermissions = setOf("CAMERA")))
        val spec = ToolSpec(name = "record", description = "d", requiredPermissions = setOf("CAMERA", "MICROPHONE"))
        val decision = assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
        assertTrue(decision.reason.contains("MICROPHONE"))
    }

    @Test
    fun `allows when every required permission is granted`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedPermissions = setOf("CAMERA", "MICROPHONE")))
        val spec = ToolSpec(name = "record", description = "d", requiredPermissions = setOf("CAMERA", "MICROPHONE"))
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }

    @Test
    fun `requires approval for a SENSITIVE tool by default`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy())
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        assertIs<PolicyDecision.RequireApproval>(enforcer.authorize(spec))
    }

    @Test
    fun `auto-approves a security level explicitly listed in policy autoApprove`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(autoApprove = setOf(SecurityLevel.NORMAL, SecurityLevel.SENSITIVE)))
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }

    @Test
    fun `permission and root checks are evaluated before confirmation`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false))
        val spec = ToolSpec(
            name = "rm",
            description = "d",
            requiresRoot = true,
            requiredPermissions = setOf("STORAGE"),
            securityLevel = SecurityLevel.ROOT,
        )
        // Root is denied outright, never downgraded to a mere approval prompt.
        assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
    }

    @Test
    fun `a CONTAINER-category tool is denied when root is disabled, treated as root-equivalent`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false))
        val spec = ToolSpec(name = "docker-exec", description = "d", requiredPermissionCategories = setOf(PermissionCategory.CONTAINER))
        val decision = assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
        assertTrue(decision.reason.contains("disabled"))
    }

    @Test
    fun `a CONTAINER-category tool is denied when root is enabled but unavailable`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = true, rootAvailable = { false }))
        val spec = ToolSpec(name = "docker-exec", description = "d", requiredPermissionCategories = setOf(PermissionCategory.CONTAINER))
        val decision = assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
        assertTrue(decision.reason.contains("unavailable"))
    }

    @Test
    fun `a ROOT-category tool is denied the same way as a CONTAINER-category one`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false))
        val spec = ToolSpec(name = "root-shell", description = "d", requiredPermissionCategories = setOf(PermissionCategory.ROOT))
        assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
    }

    @Test
    fun `non-root-equivalent permission categories never trigger the root gate`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false))
        val spec = ToolSpec(
            name = "view-status",
            description = "d",
            requiredPermissionCategories = setOf(PermissionCategory.VIEW, PermissionCategory.AUTOMATION),
        )
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }

    @Test
    fun `a CONTAINER-category tool passes the root gate once root is enabled and available`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = true, rootAvailable = { true }))
        val spec = ToolSpec(name = "docker-exec", description = "d", requiredPermissionCategories = setOf(PermissionCategory.CONTAINER))
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }
}
