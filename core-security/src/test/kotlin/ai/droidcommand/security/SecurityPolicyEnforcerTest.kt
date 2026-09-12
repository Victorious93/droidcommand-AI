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
    fun `a tool with no permissionCategory is unaffected by grantedCategories`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedCategories = emptySet()))
        val spec = ToolSpec(name = "echo", description = "d")
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }

    @Test
    fun `denies a tool whose permissionCategory is not granted`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedCategories = emptySet()))
        val spec = ToolSpec(name = "list-files", description = "d", permissionCategory = PermissionCategory.FILES)
        val decision = assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
        assertTrue(decision.reason.contains("FILES"))
    }

    @Test
    fun `allows a tool whose permissionCategory is granted`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedCategories = setOf(PermissionCategory.FILES)))
        val spec = ToolSpec(name = "list-files", description = "d", permissionCategory = PermissionCategory.FILES)
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }

    @Test
    fun `denies a CONTAINER-category tool even when listed in grantedCategories if root is disabled`() {
        val enforcer = SecurityPolicyEnforcer(
            SecurityPolicy(rootEnabled = false, grantedCategories = setOf(PermissionCategory.CONTAINER)),
        )
        val spec = ToolSpec(name = "docker-exec", description = "d", permissionCategory = PermissionCategory.CONTAINER)
        assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
    }

    @Test
    fun `allows a CONTAINER-category tool when granted and root is enabled and available`() {
        val enforcer = SecurityPolicyEnforcer(
            SecurityPolicy(
                rootEnabled = true,
                rootAvailable = { true },
                grantedCategories = setOf(PermissionCategory.CONTAINER),
            ),
        )
        val spec = ToolSpec(name = "docker-exec", description = "d", permissionCategory = PermissionCategory.CONTAINER)
        assertEquals(PolicyDecision.Allow, enforcer.authorize(spec))
    }

    @Test
    fun `permission category is checked before confirmation`() {
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(grantedCategories = emptySet()))
        val spec = ToolSpec(
            name = "delete-app",
            description = "d",
            securityLevel = SecurityLevel.SENSITIVE,
            permissionCategory = PermissionCategory.FILES,
        )
        // A missing category is a hard denial, never downgraded to a mere approval prompt.
        assertIs<PolicyDecision.Deny>(enforcer.authorize(spec))
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
}
